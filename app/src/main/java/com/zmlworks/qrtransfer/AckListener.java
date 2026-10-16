package com.zmlworks.qrtransfer;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Sender side: listens on the microphone for the receiver's ACK / DONE tones. */
final class AckListener {
    interface Callback { void onAck(); void onDone(); }

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
            Tone.Detector ack = new Tone.Detector(Tone.ACK_HZ, 2, Tone.RATE), done = new Tone.Detector(Tone.DONE_HZ, 8, Tone.RATE);
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
                    if (ack.feed(buf, 0, n)) cb.onAck();
                    if (done.feed(buf, 0, n)) cb.onDone();
                }
            } catch (IllegalStateException ignored) {
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
