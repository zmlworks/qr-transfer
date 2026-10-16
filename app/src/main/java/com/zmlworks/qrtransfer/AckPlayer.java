package com.zmlworks.qrtransfer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays slot A / slot B / DONE beeps (static AudioTracks, prepared once). Tones are played one after another on a
 * private thread (with a short gap) so two acknowledgments from the same camera frame never overlap; a tone for a slot
 * that is already queued is not queued twice.
 */
final class AckPlayer {
    private static final int RATE = 44_100;
    private final AudioTrack[] tracks = new AudioTrack[3];
    private final int[] ms = {Tone.ACK_MS, Tone.ACK_MS, Tone.DONE_MS};
    private final AtomicBoolean[] queued = {new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean()};
    private final HandlerThread thread = new HandlerThread("ack-player");
    private final Handler h;

    AckPlayer() {
        tracks[0] = track(Tone.synth(Tone.A_HZ, Tone.ACK_MS, RATE, 0.7));
        tracks[1] = track(Tone.synth(Tone.B_HZ, Tone.ACK_MS, RATE, 0.7));
        tracks[2] = track(Tone.synth(Tone.DONE_HZ, Tone.DONE_MS, RATE, 0.7));
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

    /** slot 0 = A, 1 = B */
    void ack(int slot) { enqueue(slot == 0 ? 0 : 1); }
    void done() { enqueue(2); }

    void release() {
        h.removeCallbacksAndMessages(null);
        thread.quitSafely();
        for (AudioTrack t : tracks) t.release();
    }
}
