package com.zmlworks.qrtransfer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/**
 * Sender side: listens on the microphone for the receiver's A0/A1/B0/B1 ack tones and the DONE tone.
 * Uses the UNPROCESSED source where the device supports it (no AGC / noise suppression / echo cancelling, which treat
 * a steady beep as noise), else VOICE_RECOGNITION, and switches off any platform effects on the session.
 */
final class AckListener {
    interface Callback {
        /** an ack tone (Tone.A0..B1) was heard */
        void onAck(int tone);
        void onDone();
        /** the microphone stopped delivering audio (taken by another app, error) */
        void onMicLost();
    }

    private final Callback cb;
    private final Context ctx;
    private volatile boolean running;
    /** live diagnostics: tone id dominating the latest block (or -1), its full-band rms, per-tone level / floor (dBFS) */
    volatile int liveTone = -1;
    volatile double liveRms;
    final double[] level = new double[Tone.COUNT], floor = new double[Tone.COUNT];
    volatile String source = "";
    private volatile int sensitivity = Tone.DEFAULT_SENSITIVITY;
    private Thread thread;

    AckListener(Context ctx, Callback cb) { this.ctx = ctx.getApplicationContext(); this.cb = cb; }

    void setSensitivity(int s) { sensitivity = s; }

    /** Caller must hold RECORD_AUDIO. Returns false if the mic could not be opened. */
    @SuppressLint("MissingPermission")
    boolean start() {
        int min = AudioRecord.getMinBufferSize(Tone.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioManager am = ctx.getSystemService(AudioManager.class);
        boolean unprocessed = am != null && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        AudioRecord rec = null;
        int[] sources = unprocessed ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION};
        for (int src : sources) {
            try {
                rec = new AudioRecord(src, Tone.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, Tone.BLOCK * 16));
            } catch (IllegalArgumentException | SecurityException e) { rec = null; }
            if (rec != null && rec.getState() == AudioRecord.STATE_INITIALIZED) {
                source = src == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED" : "VOICE_RECOGNITION";
                break;
            }
            if (rec != null) { rec.release(); rec = null; }
        }
        if (rec == null) return false;
        final AudioEffect[] fx = disableEffects(rec.getAudioSessionId());
        running = true;
        final AudioRecord r = rec;
        thread = new Thread(() -> {
            Tone.Listener tones = new Tone.Listener(Tone.RATE, sensitivity);
            short[] buf = new short[tones.blockSize()];
            boolean lost = false;
            try {
                r.startRecording();
                while (running) {
                    int n = 0;
                    while (n < buf.length && running) {
                        int k = r.read(buf, n, buf.length - n);
                        if (k <= 0) { lost = true; running = false; break; }
                        n += k;
                    }
                    if (n < buf.length) break;
                    if (tones.sensitivity() != sensitivity) tones.setSensitivity(sensitivity);
                    int ev = tones.feed(buf, 0, n);
                    liveTone = tones.lastClass();
                    liveRms = tones.lastRms();
                    for (int k = 0; k < Tone.COUNT; k++) { level[k] = tones.level(k); floor[k] = tones.floorDb(k); }
                    if (ev == Tone.DONE) cb.onDone();
                    else if (ev >= 0) cb.onAck(ev);
                }
            } catch (IllegalStateException e) {
                lost = true;   // recorder died (e.g. another app took the mic)
            } finally {
                try { r.stop(); } catch (IllegalStateException ignored) { }
                r.release();
                for (AudioEffect e : fx) if (e != null) e.release();
            }
            if (lost) cb.onMicLost();
        }, "ack-listener");
        thread.start();
        return true;
    }

    /** AGC / noise suppression / echo cancelling would attenuate a steady tone: off when the platform attached them */
    private static AudioEffect[] disableEffects(int session) {
        AudioEffect[] fx = new AudioEffect[3];
        try {
            if (AutomaticGainControl.isAvailable()) fx[0] = AutomaticGainControl.create(session);
            if (NoiseSuppressor.isAvailable()) fx[1] = NoiseSuppressor.create(session);
            if (AcousticEchoCanceler.isAvailable()) fx[2] = AcousticEchoCanceler.create(session);
            for (AudioEffect e : fx) if (e != null) e.setEnabled(false);
        } catch (RuntimeException ignored) { /* effect framework quirks: best effort */ }
        return fx;
    }

    void stop() {
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
    }
}
