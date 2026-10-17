package com.zmlworks.qrtransfer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the A0/A1/B0/B1 ack beeps and the DONE beep (0.3.1).
 * <ul>
 *   <li>One streaming AudioTrack, {@code USAGE_MEDIA} — the media volume (the one the volume keys change on the receive
 *       screen) applies, and it plays in silent / vibrate mode. Native output rate (no resampler).</li>
 *   <li>While the receive screen is open the track runs continuously and writes silence between tones. 0.3.0 started a
 *       short static track per beep; many phones power the speaker amplifier down when idle and need tens of ms to
 *       wake it, which could swallow much of an 80 ms beep. A running stream keeps the output path awake, and the
 *       tones (now 120 ms) start at full level.</li>
 *   <li>Tones are serialised (FIFO, {@link Tone#GAP_MS} apart) on the player thread; a tone already queued is not
 *       queued twice. {@link Callback#onPlayed} reports each tone as it is written (for the on-screen indicator).</li>
 * </ul>
 */
final class AckPlayer {
    interface Callback { void onPlayed(int tone); }

    static final double AMP = 0.8;
    private static final int SILENCE = -1000;       // queue entries ≤ SILENCE: pause of (SILENCE - entry) ms
    private final int rate;
    private final short[][] pcm = new short[Tone.COUNT][];
    private final short[] tick, gap;
    private final AtomicBoolean[] queued = new AtomicBoolean[Tone.COUNT];
    private final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
    private final Callback cb;
    private final AudioTrack track;
    private final Thread thread;
    private volatile boolean running = true;

    AckPlayer(Callback cb) {
        this.cb = cb;
        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        rate = r >= 8000 && r <= 192_000 ? r : 48_000;
        for (int k = 0; k < Tone.COUNT; k++) {
            pcm[k] = Tone.synth(Tone.HZ[k], k == Tone.DONE ? Tone.DONE_MS : Tone.ACK_MS, rate, AMP);
            queued[k] = new AtomicBoolean();
        }
        tick = new short[rate / 100];                       // 10 ms of silence
        gap = new short[rate * Tone.GAP_MS / 1000];
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = null;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, rate * 2 * 40 / 1000))   // ~40 ms: low latency, no underruns
                    .build();
            if (t.getState() != AudioTrack.STATE_INITIALIZED) { t.release(); t = null; }
        } catch (IllegalArgumentException | UnsupportedOperationException e) { t = null; }
        track = t;
        thread = new Thread(this::loop, "ack-player");
        if (track != null) thread.start();
    }

    /** false if the audio output could not be opened at all */
    boolean ok() { return track != null; }
    int sampleRate() { return rate; }

    private void loop() {
        try {
            track.play();
            while (running) {
                Integer k = queue.poll();
                if (k == null) { write(tick); continue; }     // blocking write of silence paces the loop
                if (k <= SILENCE) { write(new short[rate * (SILENCE - k) / 1000]); continue; }
                queued[k].set(false);
                if (cb != null) cb.onPlayed(k);
                write(pcm[k]);
                write(gap);
            }
        } catch (IllegalStateException ignored) {
            // track released underneath us
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    private void write(short[] s) {
        int off = 0;
        while (off < s.length && running) {
            int n = track.write(s, off, s.length - off);
            if (n <= 0) { try { TimeUnit.MILLISECONDS.sleep(5); } catch (InterruptedException e) { return; } if (n < 0) return; }
            else off += n;
        }
    }

    private void enqueue(int k) {
        if (track == null || !queued[k].compareAndSet(false, true)) return;
        queue.offer(k);
    }

    /** tone = Tone.A0..B1 (from Tone.toneOf(chunk index)) */
    void ack(int tone) { if (tone >= 0 && tone < Tone.DONE) enqueue(tone); }
    void done() { enqueue(Tone.DONE); }

    /** Self-test for the sender's microphone: A0, A1, B0, B1, DONE with 350 ms pauses (the sender page lists them). */
    void testSequence() {
        if (track == null) return;
        for (int k = 0; k < Tone.COUNT; k++) {
            if (k > 0) queue.offer(SILENCE - 350);
            queued[k].set(true);
            queue.offer(k);
        }
    }

    void release() {
        running = false;
        queue.clear();
        if (track != null) { try { thread.join(300); } catch (InterruptedException ignored) { } }
    }
}
