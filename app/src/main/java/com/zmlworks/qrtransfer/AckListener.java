package com.zmlworks.qrtransfer;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Sender side: listens on the microphone for the receiver's slot A / slot B / DONE tones. */
final class AckListener {
    interface Callback { void onAck(int slot); void onDone(); }

    private final Callback cb;
    private volatile boolean running;
    private Thread thread;

    AckListener(Callback cb) { this.cb = cb; }

    /** Caller must hold RECORD_AUDIO. Returns false if the mic could not be opened. */
    @SuppressLint("MissingPermission")
    boolean start() {
        int min = AudioRecord.getMinBufferSize(Tone.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord rec;
        try {
            // VOICE_RECOGNITION: usually no AGC / noise suppression, which could eat a steady tone
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Tone.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Tone.BLOCK * 16));
        } catch (IllegalArgumentException | SecurityException e) { return false; }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return false; }
        running = true;
        thread = new Thread(() -> {
            Tone.Listener tones = new Tone.Listener(Tone.RATE);
            short[] buf = new short[Tone.BLOCK];
            try {
                rec.startRecording();
                while (running) {
                    int n = 0;
                    while (n < buf.length && running) {
                        int r = rec.read(buf, n, buf.length - n);
                        if (r <= 0) { running = false; break; }
                        n += r;
                    }
                    if (n < buf.length) break;
                    int ev = tones.feed(buf, 0, n);
                    if ((ev & (1 << Tone.EV_A)) != 0) cb.onAck(0);
                    if ((ev & (1 << Tone.EV_B)) != 0) cb.onAck(1);
                    if ((ev & (1 << Tone.EV_DONE)) != 0) cb.onDone();
                }
            } catch (IllegalStateException ignored) {
                // recorder died (e.g. another app took the mic): the sender falls back to its timeouts
            } finally {
                try { rec.stop(); } catch (IllegalStateException ignored) { }
                rec.release();
            }
        }, "ack-listener");
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
    }
}
