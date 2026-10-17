package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Detection through a simulated acoustic path (0.3.1): phone speaker (no bass, resonance, dip, clipping) → room
 * (comb/all-pass reverb, RT60 0.3–0.6 s) → microphone with AGC off (tones at −45…−62 dBFS) with fan rumble, DC offset
 * and speech-like babble, at the app's 16 kHz. Compares the current detector with the 0.3.0 one (kept below as
 * {@link Legacy}) and checks false events on noise / voice. tools/acoustic-sim.js does the same for the web detector
 * at 44.1/48 kHz with convolution reverb; its before/after table is in BUILD_REPORT.md.
 */
public class AcousticChannelTest {
    static final int R = Tone.RATE;

    // ---------------- channel ----------------
    static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;
        Biquad(String type, double f0, double q, double gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f0 / R, c = Math.cos(w), s = Math.sin(w), al = s / (2 * q);
            double B0, B1, B2, A0, A1, A2;
            switch (type) {
                case "hp": B0 = (1 + c) / 2; B1 = -(1 + c); B2 = B0; A0 = 1 + al; A1 = -2 * c; A2 = 1 - al; break;
                case "lp": B0 = (1 - c) / 2; B1 = 1 - c; B2 = B0; A0 = 1 + al; A1 = -2 * c; A2 = 1 - al; break;
                default: B0 = 1 + al * A; B1 = -2 * c; B2 = 1 - al * A; A0 = 1 + al / A; A1 = -2 * c; A2 = 1 - al / A;
            }
            b0 = B0 / A0; b1 = B1 / A0; b2 = B2 / A0; a1 = A1 / A0; a2 = A2 / A0;
        }
        double f(double x) { double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y; }
    }

    /** Schroeder reverb: 4 combs + 2 all-pass; wet level relative to the direct sound */
    static double[] reverb(double[] x, double rt60, double wet) {
        int[] d = {557, 617, 691, 751};
        double[] out = new double[x.length];
        for (int k = 0; k < 4; k++) {
            double g = Math.pow(10, -3.0 * d[k] / (rt60 * R));
            double[] buf = new double[d[k]];
            int p = 0;
            for (int i = 0; i < x.length; i++) { double y = buf[p]; buf[p] = x[i] + g * y; p = (p + 1) % d[k]; out[i] += y / 4; }
        }
        for (int dl : new int[]{113, 37}) {
            double[] buf = new double[dl];
            int p = 0;
            for (int i = 0; i < x.length; i++) { double b = buf[p], y = -0.7 * out[i] + b; buf[p] = out[i] + 0.7 * y; p = (p + 1) % dl; out[i] = y; }
        }
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = x[i] + wet * out[i];
        return r;
    }

    static final class Scenario {
        final String name; final double levelDb, fanDb, rt60, wet, drive, voiceDb; final int ackMs;
        Scenario(String name, double levelDb, double fanDb, double rt60, double wet, double drive, double voiceDb, int ackMs) {
            this.name = name; this.levelDb = levelDb; this.fanDb = fanDb; this.rt60 = rt60; this.wet = wet; this.drive = drive; this.voiceDb = voiceDb; this.ackMs = ackMs;
        }
    }

    /** returns {signal as PCM16, onset sample of each tone, tone id of each} */
    static Object[] trial(Scenario sc, long seed) {
        Random r = new Random(seed);
        int n = 40, ms = sc.ackMs;
        double[] x = new double[R * (2 + n * (ms + 700) / 1000)];
        int[] at = new int[n], tone = new int[n];
        int t = R / 2;
        int[] bit = {0, 0};
        boolean shortGap = false;
        int prevSlot = 0;
        for (int k = 0; k < n; k++) {
            int slot = shortGap ? 1 - prevSlot : r.nextInt(2);
            if (r.nextDouble() >= 0.15) bit[slot] ^= 1; else t += R * 6 / 10;   // re-ack: ≥ 700 ms later
            tone[k] = k == n - 1 ? Tone.DONE : slot * 2 + bit[slot];
            int len = tone[k] == Tone.DONE ? Tone.DONE_MS : ms;
            short[] s = Tone.synth(Tone.HZ[tone[k]], len, R, 0.7);
            at[k] = t;
            for (int i = 0; i < s.length && t + i < x.length; i++) x[t + i] += s[i] / 32768.0;
            shortGap = !shortGap && r.nextDouble() < 0.35;
            prevSlot = slot;
            t += R * (len + (shortGap ? (ms >= 120 ? 60 : 40) : 150 + r.nextInt(300))) / 1000;
        }
        // speaker
        Biquad hp = new Biquad("hp", 450 + 450 * r.nextDouble(), 0.9 + 0.7 * r.nextDouble(), 0);
        Biquad res = new Biquad("pk", 1800 + 1800 * r.nextDouble(), 3, 3 + 7 * r.nextDouble());
        Biquad dip = new Biquad("pk", 1100 + 2200 * r.nextDouble(), 3, -3 - 6 * r.nextDouble());
        Biquad lp = new Biquad("lp", 4500 + 2000 * r.nextDouble(), 0.8, 0);
        for (int i = 0; i < x.length; i++) {
            double v = lp.f(dip.f(res.f(hp.f(x[i]))));
            x[i] = sc.drive > 0 ? Math.tanh(sc.drive * v) / Math.tanh(sc.drive) : v;
        }
        x = reverb(x, sc.rt60, sc.wet);
        // level of the first tone's steady part
        double e = 0;
        int a = at[0] + R / 50, b = at[0] + R * ms / 1000 - R / 100;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        double g = Math.pow(10, sc.levelDb / 20) / Math.sqrt(e / (b - a));
        // fan: pink-ish noise + blade-pass hum; voice: band-limited buzz (steady pitch for 125 ms — harsher than speech) through two moving formants
        double[] fan = new double[x.length], voice = new double[x.length];
        double p0 = 0, p1 = 0, p2 = 0, bp = 90 + 70 * r.nextDouble();
        for (int i = 0; i < x.length; i++) {
            double w = r.nextGaussian();
            p0 = 0.99765 * p0 + w * 0.0990460; p1 = 0.96300 * p1 + w * 0.2965164; p2 = 0.57000 * p2 + w * 1.0526913;
            fan[i] = p0 + p1 + p2 + w * 0.1848 + 0.6 * Math.sin(2 * Math.PI * bp * i / R) + 0.3 * Math.sin(4 * Math.PI * bp * i / R);
        }
        if (!Double.isNaN(sc.voiceDb)) {
            double f0 = 120, ph = 0;
            Biquad form = new Biquad("pk", 1500, 4, 15), f1 = new Biquad("pk", 500, 4, 15);
            for (int i = 0; i < x.length; i++) {
                if (i % (R / 8) == 0) {
                    f0 = 85 + 175 * r.nextDouble();
                    f1 = new Biquad("pk", 300 + 500 * r.nextDouble(), 4, 15);           // first formant
                    form = new Biquad("pk", 900 + 1800 * r.nextDouble(), 4, 15);        // second formant
                }
                ph += f0 / R;
                if (ph >= 1) ph -= 1;
                double env = Math.pow(Math.sin(Math.PI * ((i % (R / 4)) / (R / 4.0))), 2), saw = 0;
                for (int h = 1; h * f0 < 4000; h++) saw += Math.sin(2 * Math.PI * h * ph) / h;   // band-limited (no aliasing)
                voice[i] = form.f(f1.f(saw)) * env;
            }
        }
        scale(fan, sc.fanDb);
        if (!Double.isNaN(sc.voiceDb)) scale(voice, sc.voiceDb);
        short[] pcm = new short[x.length];
        for (int i = 0; i < x.length; i++)
            pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(32768 * (x[i] * g + fan[i] + voice[i] + 0.002))));
        return new Object[]{pcm, at, tone};
    }

    static void scale(double[] x, double db) {
        double e = 0;
        for (double v : x) e += v * v;
        double g = Math.pow(10, db / 20) / Math.sqrt(e / x.length + 1e-30);
        for (int i = 0; i < x.length; i++) x[i] *= g;
    }

    interface Det { int feed(short[] s, int off, int n); }

    /** fraction of tones detected (right tone, within the burst + 250 ms) and the number of wrong-tone events */
    static double[] rate(Scenario sc, boolean legacy, int trials) {
        int hit = 0, total = 0, wrong = 0;
        for (int tr = 0; tr < trials; tr++) {
            Object[] t = trial(sc, 100 + tr * 31L);
            short[] pcm = (short[]) t[0];
            int[] at = (int[]) t[1], tone = (int[]) t[2];
            Det d = legacy ? new Legacy()::feed : new Tone.Listener(R)::feed;
            boolean[] used = new boolean[at.length];
            for (int off = 0; off + Tone.BLOCK <= pcm.length; off += Tone.BLOCK) {
                int e = d.feed(pcm, off, Tone.BLOCK);
                if (e < 0) continue;
                int end = off + Tone.BLOCK, m = -1;
                boolean during = false, dup = false;
                for (int k = 0; k < at.length; k++) {
                    int len = R * (tone[k] == Tone.DONE ? Tone.DONE_MS : sc.ackMs) / 1000;
                    boolean in = end >= at[k] && end <= at[k] + len + R / 4;
                    if (in) during = true;
                    if (in && tone[k] == e && !used[k] && m < 0) m = k;
                    if (tone[k] == e && end > at[k] + len && end <= at[k] + len + R * 4 / 10) dup = true;
                }
                if (m >= 0) { used[m] = true; hit++; } else if (during && !dup) wrong++;
            }
            total += at.length;
        }
        return new double[]{hit / (double) total, wrong};
    }

    static final Scenario[] SC = {
            new Scenario("clean −35 dBFS", -35, -75, 0.3, 0.3, 0, Double.NaN, 80),
            new Scenario("laptop −45 dBFS, fan −58, mild clip", -45, -58, 0.4, 0.5, 1.5, Double.NaN, 80),
            new Scenario("AGC off, quiet −55 dBFS", -55, -72, 0.4, 0.5, 0, Double.NaN, 80),
            new Scenario("very quiet −62 dBFS", -62, -78, 0.4, 0.5, 0, Double.NaN, 80),
            new Scenario("loud fan −47 vs tone −45", -45, -47, 0.4, 0.5, 0, Double.NaN, 80),
            new Scenario("reverberant RT60 0.6, wet", -45, -62, 0.6, 1.0, 0, Double.NaN, 80),
            new Scenario("talking −40, tone −42", -42, -65, 0.4, 0.5, 0, -40, 80),
            new Scenario("0.3.1 receiver (120 ms), quiet −55", -55, -72, 0.4, 0.5, 0, Double.NaN, 120),
    };

    @Test public void newDetectorBeatsTheOldOneThroughARealisticChannel() {
        StringBuilder rep = new StringBuilder("scenario                                   0.3.0   0.3.1 (wrong-tone events)\n");
        double minNew = 1;
        for (Scenario sc : SC) {
            double[] o = rate(sc, true, 3), n = rate(sc, false, 3);
            rep.append(String.format(Locale.ROOT, "%-42s %4.0f %%  %4.0f %%  (%d / %d)%n", sc.name, 100 * o[0], 100 * n[0], (int) o[1], (int) n[1]));
            // with someone talking a voice harmonic can (rarely) pass as a tone; never through a clean channel
            assertTrue(sc.name + ": wrong-tone events " + n[1], n[1] <= (Double.isNaN(sc.voiceDb) ? 0 : 2));
            if (Double.isNaN(sc.voiceDb)) minNew = Math.min(minNew, n[0]);
            if (sc.levelDb <= -55) assertTrue(sc.name + ": 0.3.0 should fail here " + o[0], o[0] < 0.3);
        }
        System.out.print(rep);
        assertTrue("worst-case detection " + minNew + "\n" + rep, minNew >= 0.85);
    }

    @Test public void fewFalseEventsOnNoiseAndVoice() {
        // 60 s of each: fan, loud fan, voice babble, voice + fan; no tones
        int falseEvents = 0;
        double[][] cases = {{-45, Double.NaN}, {-35, Double.NaN}, {-70, -35}, {-55, -25}};
        for (double[] c : cases) {
            Scenario sc = new Scenario("noise", -200, c[0], 0.4, 0.5, 0, c[1], 80);
            for (int tr = 0; tr < 2; tr++) {
                short[] pcm = (short[]) trial(sc, 900 + tr)[0];
                Tone.Listener l = new Tone.Listener(R);
                Legacy old = new Legacy();
                int fn = 0, fo = 0;
                for (int off = 0; off + Tone.BLOCK <= pcm.length; off += Tone.BLOCK) {
                    if (l.feed(pcm, off, Tone.BLOCK) >= 0) fn++;
                    if (old.feed(pcm, off, Tone.BLOCK) >= 0) fo++;
                }
                System.out.println(String.format(Locale.ROOT, "false events fan %.0f voice %.0f: 0.3.1 %d, 0.3.0 %d", c[0], c[1], fn, fo));
                falseEvents += fn;
            }
        }
        // ≈ 8 × 30 s = 4 minutes of non-tone sound
        assertTrue("false events " + falseEvents, falseEvents <= 2);
    }

    /** The 0.3.0 detector, verbatim, for the before/after comparison. */
    static final class Legacy {
        static final double MIN_RATIO = 0.30, NEIGHBOUR_HZ = 190, NEIGHBOUR_FACTOR = 6, OTHER_FACTOR = 6, MIN_RMS = 120 / 32767.0, FLOOR_FACTOR = 2.0;
        final double[] buf = new double[Tone.BLOCK], ratios = new double[Tone.COUNT];
        int run, runTone = -1;
        boolean fired;
        double floor;

        static int classify(double[] x, int n, double noiseFloor, double[] r) {
            double e = 0;
            for (int i = 0; i < n; i++) e += x[i] * x[i];
            double rms = Math.sqrt(e / n);
            int best = -1;
            for (int k = 0; k < Tone.COUNT; k++) { r[k] = Tone.ratio(x, n, Tone.HZ[k], R); if (best < 0 || r[k] > r[best]) best = k; }
            if (rms < MIN_RMS || rms < FLOOR_FACTOR * noiseFloor || r[best] < MIN_RATIO) return -1;
            for (int k = 0; k < Tone.COUNT; k++) if (k != best && r[best] < OTHER_FACTOR * r[k]) return -1;
            double lo = Tone.ratio(x, n, Tone.HZ[best] - NEIGHBOUR_HZ, R), hi = Tone.ratio(x, n, Tone.HZ[best] + NEIGHBOUR_HZ, R);
            if (r[best] < NEIGHBOUR_FACTOR * Math.max(lo, hi)) return -1;
            return best;
        }

        int feed(short[] s, int off, int n) {
            double e = 0;
            for (int i = 0; i < n; i++) { buf[i] = s[off + i] / 32768.0; e += buf[i] * buf[i]; }
            double rms = Math.sqrt(e / n);
            int c = classify(buf, n, floor, ratios);
            if (c < 0) floor = rms < floor ? 0.7 * floor + 0.3 * rms : 0.98 * floor + 0.02 * rms;
            if (c >= 0 && c == runTone) run++;
            else { run = c >= 0 ? 1 : 0; runTone = c; fired = false; }
            int need = c == Tone.DONE ? 10 : 3;
            if (c >= 0 && run >= need && !fired) { fired = true; return c; }
            return -1;
        }
    }

}
