package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class ToneTest {
    private static int count(short[] sig, Tone.Detector d) {
        int n = 0;
        for (int off = 0; off + Tone.BLOCK <= sig.length; off += Tone.BLOCK) if (d.feed(sig, off, Tone.BLOCK)) n++;
        return n;
    }

    private static short[] concat(short[]... parts) {
        int len = 0;
        for (short[] p : parts) len += p.length;
        short[] out = new short[len];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static short[] noise(int n, double amp, long seed) {
        Random r = new Random(seed);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (r.nextGaussian() * amp * 32767);
        return s;
    }

    private static short[] mix(short[] a, short[] b) {
        short[] o = new short[Math.min(a.length, b.length)];
        for (int i = 0; i < o.length; i++) o[i] = (short) Math.max(-32768, Math.min(32767, a[i] + b[i]));
        return o;
    }

    @Test public void ratioOfPureTone() {
        short[] s = Tone.synth(Tone.ACK_HZ, 100, Tone.RATE, 0.5);
        assertEquals(1.0, Tone.ratio(s, 400, Tone.BLOCK, Tone.ACK_HZ, Tone.RATE), 0.05);
        assertTrue(Tone.ratio(s, 400, Tone.BLOCK, Tone.DONE_HZ, Tone.RATE) < 0.02);
    }

    @Test public void detectsEachAckOnceEvenWithNoise() {
        short[] gap = new short[Tone.RATE / 10];
        short[] ack = Tone.synth(Tone.ACK_HZ, Tone.ACK_MS, Tone.RATE, 0.05);   // quiet tone (far phone)
        short[] sig = concat(gap, ack, gap, ack, gap, ack, gap);
        sig = mix(sig, noise(sig.length, 0.01, 1));
        assertEquals(3, count(sig, new Tone.Detector(Tone.ACK_HZ, 2, Tone.RATE)));
        assertEquals(0, count(sig, new Tone.Detector(Tone.DONE_HZ, 8, Tone.RATE)));
    }

    @Test public void doneToneNeedsDuration() {
        short[] gap = new short[Tone.RATE / 10];
        short[] done = Tone.synth(Tone.DONE_HZ, Tone.DONE_MS, Tone.RATE, 0.3);
        short[] blip = Tone.synth(Tone.DONE_HZ, 60, Tone.RATE, 0.3);       // too short to count as DONE
        assertEquals(1, count(concat(gap, done, gap), new Tone.Detector(Tone.DONE_HZ, 8, Tone.RATE)));
        assertEquals(0, count(concat(gap, blip, gap), new Tone.Detector(Tone.DONE_HZ, 8, Tone.RATE)));
    }

    @Test public void noiseAndOtherTonesDoNotTrigger() {
        short[] n = noise(Tone.RATE * 3, 0.2, 7);
        assertEquals(0, count(n, new Tone.Detector(Tone.ACK_HZ, 2, Tone.RATE)));
        // "speech-ish": several harmonics of 210 Hz incl. 1890 Hz (9th) — close to ACK but mixed
        short[] v = new short[Tone.RATE];
        for (int i = 0; i < v.length; i++) {
            double x = 0;
            for (int k = 1; k <= 12; k++) x += Math.sin(2 * Math.PI * 210 * k * i / Tone.RATE) / k;
            v[i] = (short) (x * 6000);
        }
        assertEquals(0, count(v, new Tone.Detector(Tone.ACK_HZ, 2, Tone.RATE)));
        assertEquals(0, count(new short[Tone.RATE], new Tone.Detector(Tone.ACK_HZ, 2, Tone.RATE)));
    }
}
