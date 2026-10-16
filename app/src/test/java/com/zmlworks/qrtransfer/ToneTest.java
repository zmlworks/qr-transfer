package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 5-tone detector (A0/A1/B0/B1/DONE): separation, minimum duration, noise, harmonics, speech, old tones. */
public class ToneTest {
    static final int R = Tone.RATE;

    /** detected tone ids, in order, when the signal is fed block by block */
    static List<Integer> events(short[] sig) {
        Tone.Listener l = new Tone.Listener(R);
        List<Integer> out = new ArrayList<>();
        for (int off = 0; off + Tone.BLOCK <= sig.length; off += Tone.BLOCK) {
            int ev = l.feed(sig, off, Tone.BLOCK);
            if (ev >= 0) out.add(ev);
        }
        return out;
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
    static short[] ack(int tone, double amp) { return Tone.synth(Tone.HZ[tone], Tone.ACK_MS, R, amp); }
    static short[] done(double amp) { return Tone.synth(Tone.HZ[Tone.DONE], Tone.DONE_MS, R, amp); }
    static short[] shift(short[] s, int samples) { return concat(new short[samples], s); }

    @Test public void toneOfIsSlotAndAlternatingBit() {
        int[] want = {Tone.A0, Tone.B0, Tone.A1, Tone.B1, Tone.A0, Tone.B0, Tone.A1, Tone.B1};
        for (int i = 0; i < 8; i++) assertEquals(want[i], Tone.toneOf(i));
        for (int i = 0; i < 1000; i++) {
            assertEquals(i & 1, Tone.slotOfTone(Tone.toneOf(i)));        // slot = parity of the index
            assertTrue(Tone.toneOf(i) != Tone.toneOf(i + 2));           // consecutive chunks of a slot differ
        }
    }

    @Test public void frequenciesAreWellSeparatedAndNotHarmonics() {
        double[] f = Tone.HZ;
        double[] old = {1500, 1900, 2200, 2700, 2850};
        for (int i = 0; i < f.length; i++) {
            assertTrue(f[i] >= 1200 && f[i] <= 3200);
            for (double o : old) assertTrue(f[i] + " near old " + o, Math.abs(f[i] - o) >= 150);
            for (int j = 0; j < f.length; j++) {
                if (i == j) continue;
                assertTrue(Math.abs(f[i] - f[j]) >= 350);
                for (int h = 2; h <= 3; h++) assertTrue(h + "x" + f[i] + " vs " + f[j], Math.abs(h * f[i] - f[j]) >= 300);
            }
        }
    }

    @Test public void ratiosSeparateTheFiveTones() {
        for (int k = 0; k < Tone.COUNT; k++) {
            short[] s = Tone.synth(Tone.HZ[k], 100, R, 0.5);
            for (int j = 0; j < Tone.COUNT; j++) {
                double r = Tone.ratio(s, 400, Tone.BLOCK, Tone.HZ[j], R);
                if (k == j) assertEquals(1.0, r, 0.05);
                else assertTrue(k + " vs " + j + ": " + r, r < 0.005);
            }
        }
    }

    @Test public void eachToneDetectedOnceAtAnyAlignment() {
        for (int k = 0; k < Tone.B1 + 1; k++)
            for (int sh = 0; sh < Tone.BLOCK; sh += 37)
                assertEquals("tone " + k + " shift " + sh, List.of(k), events(shift(concat(gap(50), ack(k, 0.2), gap(80)), sh)));
        assertEquals(List.of(Tone.DONE), events(concat(gap(50), done(0.2), gap(50))));
    }

    @Test public void alternatingSequenceWithNoiseInOrder() {
        int[] seq = {Tone.A0, Tone.B0, Tone.A1, Tone.B1, Tone.B0, Tone.A0, Tone.A1, Tone.B1, Tone.A0};
        for (long seed = 1; seed <= 10; seed++) {
            List<short[]> parts = new ArrayList<>();
            parts.add(gap(100));
            for (int t : seq) { parts.add(ack(t, 0.05)); parts.add(gap(Tone.GAP_MS)); }
            parts.add(done(0.05));
            parts.add(gap(100));
            short[] sig = shift(concat(parts.toArray(new short[0][])), (int) (seed * 29));
            sig = mix(sig, noise(sig.length, 0.006, seed));   // tone rms 0.035 vs noise 0.006: SNR ≈ 15 dB
            List<Integer> want = new ArrayList<>();
            for (int t : seq) want.add(t);
            want.add(Tone.DONE);
            assertEquals("seed " + seed, want, events(sig));
        }
    }

    @Test public void detectionRateAtLowSnrAndNoFalseOnes() {
        // 200 random acks at ~6 dB SNR: most detected, never a wrong tone
        Random rnd = new Random(5);
        int detected = 0, wrong = 0;
        for (int k = 0; k < 200; k++) {
            int t = rnd.nextInt(4);
            short[] sig = concat(gap(60), ack(t, 0.03), gap(60));
            sig = mix(sig, noise(sig.length, 0.011, k));
            for (int e : events(sig)) { if (e == t) detected++; else wrong++; }
        }
        assertEquals(0, wrong);
        assertTrue("detected " + detected, detected >= 180);
    }

    @Test public void tooShortBlipsAreIgnored() {
        for (int k = 0; k < 4; k++) assertEquals(List.of(), events(concat(gap(50), Tone.synth(Tone.HZ[k], 24, R, 0.3), gap(50))));
        assertEquals(List.of(), events(concat(gap(50), Tone.synth(Tone.HZ[Tone.DONE], 120, R, 0.3), gap(50))));
    }

    @Test public void harmonicsDoNotHitOtherTones() {
        // distorted speaker: tone + 10 % 2nd + 5 % 3rd harmonic must still be only that tone
        for (int k = 0; k < Tone.COUNT; k++) {
            short[] s = new short[Tone.ACK_MS * R / 1000];
            for (int i = 0; i < s.length; i++) {
                double t = 2 * Math.PI * Tone.HZ[k] * i / R;
                s[i] = (short) (12000 * (Math.sin(t) + 0.1 * Math.sin(2 * t) + 0.05 * Math.sin(3 * t)));
            }
            List<Integer> ev = events(concat(gap(50), s, gap(50)));
            assertEquals("tone " + k, k == Tone.DONE ? List.of() : List.of(k), ev);   // DONE: 80 ms is too short
        }
    }

    @Test public void loudNoiseHumSpeechAndSilenceDoNotTrigger() {
        for (long seed = 1; seed <= 5; seed++) assertEquals(List.of(), events(noise(R * 3, 0.2, seed)));
        assertEquals(List.of(), events(new short[R]));
        // voiced sound at several pitches: harmonics pass through / near every tone
        for (int f0 : new int[]{100, 125, 150, 183, 220, 275, 340}) {
            short[] v = new short[R];
            for (int i = 0; i < v.length; i++) {
                double x = 0;
                for (int k = 1; k <= 30; k++) x += Math.sin(2 * Math.PI * f0 * k * i / R) / k;
                v[i] = (short) (x * 6000);
            }
            assertEquals("f0=" + f0, List.of(), events(v));
        }
        // mains hum 50/60 Hz with harmonics
        short[] hum = new short[R];
        for (int i = 0; i < hum.length; i++) {
            double x = 0;
            for (int k = 1; k <= 40; k += 2) x += Math.sin(2 * Math.PI * 50 * k * i / R) / k;
            hum[i] = (short) (x * 8000);
        }
        assertEquals(List.of(), events(hum));
    }

    @Test public void oldVersionTonesAndOtherBeepsAreIgnored() {
        // 0.1.0 (1900 / 2850) and 0.2.0 (1500 / 2200 / 2700) tones, plus 1000 Hz and halfway between our tones
        double[] f = {1500, 2200, 2700, 1900, 2850, 1000, 1525, 1875, 2225, 2725};
        for (double hz : f) {
            short[] sig = concat(gap(50), Tone.synth(hz, 450, R, 0.3), gap(50));
            assertEquals(hz + " Hz", List.of(), events(sig));
            assertEquals(hz + " Hz noisy", List.of(), events(mix(sig, noise(sig.length, 0.01, (long) hz))));
        }
    }

    @Test public void slowlyDriftingFloorStillDetectsTones() {
        // steady background noise for 2 s (the floor learns it), then a tone 10 dB above it
        short[] bg = noise(R * 2, 0.01, 9);
        short[] sig = concat(bg, mix(concat(ack(Tone.B1, 0.045), gap(50)), noise(R * 130 / 1000, 0.01, 10)));
        assertEquals(List.of(Tone.B1), events(sig));
    }

    @Test public void javaAgreesWithTheWebDetector() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("web_tones.txt");
        assertTrue("run node tools/test-web.js first", in != null);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String[] pcm = r.readLine().split(" "), cls = r.readLine().split(" "), ev = r.readLine().split(" ");
            short[] s = new short[pcm.length];
            for (int i = 0; i < s.length; i++) s[i] = Short.parseShort(pcm[i]);
            Tone.Listener l = new Tone.Listener(R);
            List<Integer> got = new ArrayList<>();
            int b = 0;
            for (int off = 0; off + Tone.BLOCK <= s.length; off += Tone.BLOCK, b++) {
                int e = l.feed(s, off, Tone.BLOCK);
                assertEquals("block " + b, Integer.parseInt(cls[b]), l.lastClass());
                if (e >= 0) got.add(e);
            }
            int[] want = new int[ev.length];
            for (int i = 0; i < ev.length; i++) want[i] = Integer.parseInt(ev[i]);
            assertArrayEquals(want, got.stream().mapToInt(Integer::intValue).toArray());
        }
    }
}
