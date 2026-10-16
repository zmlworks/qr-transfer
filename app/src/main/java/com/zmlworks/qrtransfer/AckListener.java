package com.zmlworks.qrtransfer;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Sender side: listens on the microphone for the receiver's A0/A1/B0/B1 ack tones and the DONE tone. */
final class AckListener {
    interface Callback {
        /** an ack tone (Tone.A0..B1) was heard */
        void onAck(int tone);
        void onDone();
        /** the microphone stopped delivering audio (taken by another app, error) */
        void onMicLost();
    }

    private final Callback cb;
    private volatile boolean running;
    /** live indicator: tone id dominating the latest block (or -1) and its level (0..1) */
    volatile int liveTone = -1;
    volatile double liveRms;
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
            short[] buf = new short[tones.blockSize()];
            boolean lost = false;
            try {
                rec.startRecording();
                while (running) {
                    int n = 0;
                    while (n < buf.length && running) {
                        int r = rec.read(buf, n, buf.length - n);
                        if (r <= 0) { lost = true; running = false; break; }
                        n += r;
                    }
                    if (n < buf.length) break;
                    int ev = tones.feed(buf, 0, n);
                    liveTone = tones.lastClass();
                    liveRms = tones.lastRms();
                    if (ev == Tone.DONE) cb.onDone();
                    else if (ev >= 0) cb.onAck(ev);
                }
            } catch (IllegalStateException e) {
                lost = true;   // recorder died (e.g. another app took the mic)
            } finally {
                try { rec.stop(); } catch (IllegalStateException ignored) { }
                rec.release();
            }
            if (lost) cb.onMicLost();
        }, "ack-listener");
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
    }
}
