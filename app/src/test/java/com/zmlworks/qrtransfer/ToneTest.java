package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class ToneTest {
    static final int R = Tone.RATE;

    /** events per type [A, B, DONE] when the signal is fed block by block */
    static int[] events(short[] sig) {
        Tone.Listener l = new Tone.Listener(R);
        int[] n = new int[3];
        for (int off = 0; off + Tone.BLOCK <= sig.length; off += Tone.BLOCK) {
            int ev = l.feed(sig, off, Tone.BLOCK);
            for (int k = 0; k < 3; k++) if ((ev & (1 << k)) != 0) n[k]++;
        }
        return n;
    }

    static short[] concat(short[]... parts) {
        int len = 0;
        for (short[] p : parts) len += p.length;
        short[] out = new short[len];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static short[] noise(int n, double amp, long seed) {
        Random r = new Random(seed);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) Math.max(-32768, Math.min(32767, r.nextGaussian() * amp * 32767));
        return s;
    }

    static short[] mix(short[] a, short[] b) {
        short[] o = new short[Math.max(a.length, b.length)];
        for (int i = 0; i < o.length; i++) {
            int v = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
            o[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return o;
    }

    static short[] gap(int ms) { return new short[R * ms / 1000]; }
    static short[] a(double amp) { return Tone.synth(Tone.A_HZ, Tone.ACK_MS, R, amp); }
    static short[] b(double amp) { return Tone.synth(Tone.B_HZ, Tone.ACK_MS, R, amp); }
    static short[] done(double amp) { return Tone.synth(Tone.DONE_HZ, Tone.DONE_MS, R, amp); }

    /** start offset not aligned to blocks */
    static short[] shift(short[] s, int samples) { return concat(new short[samples], s); }

    @Test public void ratiosSeparateTheThreeTones() {
        double[] hz = {Tone.A_HZ, Tone.B_HZ, Tone.DONE_HZ};
        for (double f : hz) {
            short[] s = Tone.synth(f, 100, R, 0.5);
            for (double g : hz) {
                double r = Tone.ratio(s, 400, Tone.BLOCK, g, R);
                if (f == g) assertEquals(1.0, r, 0.05);
                else assertTrue(f + " vs " + g + ": " + r, r < 0.01);
            }
        }
    }

    @Test public void harmonicsDoNotHitOtherTones() {
        // a distorted speaker: A + 10% 2nd + 5% 3rd harmonic must still be only A
        short[] s = new short[R / 2];
        for (int i = 0; i < s.length; i++) {
            double t = 2 * Math.PI * Tone.A_HZ * i / R;
            s[i] = (short) (12000 * (Math.sin(t) + 0.1 * Math.sin(2 * t) + 0.05 * Math.sin(3 * t)));
        }
        assertEquals(0, Tone.ratio(s, 0, Tone.BLOCK, Tone.DONE_HZ, R), 0.01);
        assertEquals(0, Tone.ratio(s, 0, Tone.BLOCK, Tone.B_HZ, R), 0.01);
        int[] ev = events(s);
        assertEquals(1, ev[0]);
        assertEquals(0, ev[1]);
        assertEquals(0, ev[2]);
    }

    @Test public void sequenceOfTwoTonesWithNoiseCountsEachOnce() {
        short[] sig = concat(gap(100), a(0.05), gap(Tone.GAP_MS), b(0.05), gap(150), b(0.05), gap(80), a(0.05), gap(Tone.GAP_MS),
                b(0.05), gap(200), a(0.05), gap(100));
        sig = shift(sig, 77);
        sig = mix(sig, noise(sig.length, 0.01, 1));   // SNR ~ 11 dB for a quiet, far tone
        int[] ev = events(sig);
        assertEquals(3, ev[0]);
        assertEquals(3, ev[1]);
        assertEquals(0, ev[2]);
    }

    @Test public void simultaneousTonesAreBothDetected() {
        short[] both = mix(a(0.2), b(0.2));
        int[] ev = events(concat(gap(50), both, gap(50)));
        assertEquals(1, ev[0]);
        assertEquals(1, ev[1]);
        assertEquals(0, ev[2]);
    }

    @Test public void doneNeedsDurationAndIsDistinct() {
        int[] ev = events(concat(gap(100), done(0.3), gap(100)));
        assertEquals(0, ev[0]);
        assertEquals(0, ev[1]);
        assertEquals(1, ev[2]);
        short[] blip = Tone.synth(Tone.DONE_HZ, 60, R, 0.3);
        assertEquals(0, events(concat(gap(100), blip, gap(100)))[2]);
        // done right after a slot tone, noisy
        short[] sig = concat(gap(50), b(0.1), gap(Tone.GAP_MS), done(0.1), gap(50));
        ev = events(mix(sig, noise(sig.length, 0.02, 3)));
        assertEquals(1, ev[1]);
        assertEquals(1, ev[2]);
        assertEquals(0, ev[0]);
    }

    @Test public void tonesSurviveLoudNoiseButNoiseAloneDoesNot() {
        short[] sig = concat(gap(100), a(0.3), gap(100), b(0.3), gap(100));
        int[] ev = events(mix(sig, noise(sig.length, 0.05, 11)));   // SNR ~ 12 dB
        assertEquals(1, ev[0]);
        assertEquals(1, ev[1]);
        for (long seed = 1; seed <= 5; seed++) {
            int[] n = events(noise(R * 3, 0.2, seed));
            assertEquals(0, n[0] + n[1] + n[2]);
        }
    }

    @Test public void speechLikeHarmonicsAndSilenceDoNotTrigger() {
        // voiced sound at several pitches: harmonics pass through / near 1500 and 2200 Hz
        for (int f0 : new int[]{125, 150, 183, 220, 275}) {
            short[] v = new short[R];
            for (int i = 0; i < v.length; i++) {
                double x = 0;
                for (int k = 1; k <= 20; k++) x += Math.sin(2 * Math.PI * f0 * k * i / R) / k;
                v[i] = (short) (x * 6000);
            }
            int[] ev = events(v);
            assertEquals("f0=" + f0, 0, ev[0] + ev[1] + ev[2]);
        }
        int[] s = events(new short[R]);
        assertEquals(0, s[0] + s[1] + s[2]);
    }

    @Test public void otherBeepsDoNotTrigger() {
        // 1000 Hz and 1900 Hz (the 0.1.0 ack) and 2850 Hz (0.1.0 done) are not ours
        short[] sig = concat(gap(50), Tone.synth(1000, 200, R, 0.3), gap(50), Tone.synth(1900, 200, R, 0.3), gap(50),
                Tone.synth(2850, 500, R, 0.3), gap(50));
        int[] ev = events(sig);
        assertEquals(0, ev[0] + ev[1] + ev[2]);
    }
}
