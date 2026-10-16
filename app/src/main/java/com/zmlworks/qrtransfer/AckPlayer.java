package com.zmlworks.qrtransfer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Plays the short ACK / DONE beeps (static AudioTracks, prepared once). */
final class AckPlayer {
    private static final int RATE = 44_100;
    private final AudioTrack ack, done;

    AckPlayer() {
        ack = track(Tone.synth(Tone.ACK_HZ, Tone.ACK_MS, RATE, 0.7));
        done = track(Tone.synth(Tone.DONE_HZ, Tone.DONE_MS, RATE, 0.7));
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

    private static void play(AudioTrack t) {
        try {
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (IllegalStateException ignored) { }
    }

    void ack() { play(ack); }
    void done() { play(done); }

    void release() { ack.release(); done.release(); }
}
