package com.zmlworks.qrtransfer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the A0/A1/B0/B1 ack beeps and the DONE beep (static AudioTracks, prepared once). Tones are played one after
 * another on a private thread (FIFO, with a short gap) so two acknowledgments from the same camera frame never
 * overlap and arrive in the order they were decided; a tone that is already queued is not queued twice.
 */
final class AckPlayer {
    private static final int RATE = 44_100;
    private final AudioTrack[] tracks = new AudioTrack[Tone.COUNT];
    private final int[] ms = new int[Tone.COUNT];
    private final AtomicBoolean[] queued = new AtomicBoolean[Tone.COUNT];
    private final HandlerThread thread = new HandlerThread("ack-player");
    private final Handler h;

    AckPlayer() {
        for (int k = 0; k < Tone.COUNT; k++) {
            ms[k] = k == Tone.DONE ? Tone.DONE_MS : Tone.ACK_MS;
            tracks[k] = track(Tone.synth(Tone.HZ[k], ms[k], RATE, 0.7));
            queued[k] = new AtomicBoolean();
        }
        thread.start();
        h = new Handler(thread.getLooper());
    }

    private static AudioTrack track(short[] pcm) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private void enqueue(int k) {
        if (!queued[k].compareAndSet(false, true)) return;
        h.post(() -> {
            queued[k].set(false);
            AudioTrack t = tracks[k];
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
                t.reloadStaticData();
                t.play();
            } catch (IllegalStateException ignored) {
                return;
            }
            SystemClock.sleep(ms[k] + Tone.GAP_MS);   // serialise: next tone starts after this one
        });
    }

    /** tone = Tone.A0..B1 (from Tone.toneOf(chunk index)) */
    void ack(int tone) { if (tone >= 0 && tone < Tone.DONE) enqueue(tone); }
    void done() { enqueue(Tone.DONE); }

    void release() {
        h.removeCallbacksAndMessages(null);
        thread.quitSafely();
        for (AudioTrack t : tracks) t.release();
    }
}
