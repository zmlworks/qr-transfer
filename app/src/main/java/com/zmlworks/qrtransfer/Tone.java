package com.zmlworks.qrtransfer;

/**
 * Acknowledgment tones (receiver → sender, the only back channel; no radio).
 * <ul>
 *   <li>Slot A (top / left QR, even chunk numbers): 1500 Hz, 100 ms</li>
 *   <li>Slot B (bottom / right QR, odd chunk numbers): 2200 Hz, 100 ms</li>
 *   <li>DONE (whole transfer verified): 2700 Hz, 450 ms</li>
 * </ul>
 * Chosen inside 1.2–3 kHz, where small phone speakers are loud and flat and phone/PC microphones (16 kHz sampling)
 * are clean, above mains hum and most speech energy. They are ≥ 500 Hz apart (≥ 8 Goertzel bins at 62.5 Hz/bin,
 * far outside the Hann main lobe of ±125 Hz) and no tone sits near another's 2nd/3rd harmonic (3000, 4400, 4500 Hz…),
 * so speaker distortion of one tone cannot fake another. The sender detects them with Goertzel (one DFT bin per tone).
 */
public final class Tone {
    private Tone() {}

    public static final int RATE = 16_000;
    public static final double A_HZ = 1500, B_HZ = 2200, DONE_HZ = 2700;
    public static final int ACK_MS = 100, DONE_MS = 450, GAP_MS = 40;
    public static final int BLOCK = 256;                      // 16 ms at 16 kHz
    public static final int ACK_BLOCKS = 2, DONE_BLOCKS = 8;  // consecutive blocks needed: 32 ms / 128 ms

    public static double slotHz(int slot) { return slot == 0 ? A_HZ : B_HZ; }

    /** 16-bit PCM sine with 10 ms raised-cosine fade in/out (no clicks). */
    public static short[] synth(double hz, int ms, int rate, double amp) {
        int n = rate * ms / 1000, fade = rate / 100;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double env = 1;
            if (i < fade) env = 0.5 - 0.5 * Math.cos(Math.PI * i / fade);
            else if (i > n - fade) env = 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / fade);
            s[i] = (short) Math.round(Math.sin(2 * Math.PI * hz * i / rate) * env * amp * 32767);
        }
        return s;
    }

    /**
     * Fraction of the block's energy at frequency hz: 1.0 for a pure tone at hz, ~0 for silence,
     * speech or other tones. Generalized Goertzel (non-integer bin) over a Hann-windowed block.
     */
    public static double ratio(short[] x, int off, int n, double hz, int rate) {
        double w = 2 * Math.PI * hz / rate, coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0, energy = 0, sumW = 0, sumW2 = 0;
        for (int i = 0; i < n; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            double v = x[off + i] * win;
            energy += v * v;
            sumW += win;
            sumW2 += win * win;
            double s0 = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        if (energy <= 0) return 0;
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;   // |X(hz)|^2
        // pure tone of amplitude A: power = A^2 (sumW)^2 / 4, energy = A^2 sumW2 / 2
        return power / energy * 2 * sumW2 / (sumW * sumW);
    }

    /** Debounced detector: fires once per tone after `need` consecutive matching blocks. */
    public static final class Detector {
        private final double hz, threshold, minRms;
        private final int need, rate;
        private int run;
        private boolean fired;

        public Detector(double hz, int need, int rate) {
            this.hz = hz; this.need = need; this.rate = rate; this.threshold = 0.40; this.minRms = 120;
        }

        /** Feed one block; returns true exactly once at the start of each detected tone. */
        public boolean feed(short[] x, int off, int n) {
            double e = 0;
            for (int i = 0; i < n; i++) e += (double) x[off + i] * x[off + i];
            boolean hit = Math.sqrt(e / n) >= minRms && ratio(x, off, n, hz, rate) >= threshold;
            if (hit) {
                run++;
                if (run >= need && !fired) { fired = true; return true; }
            } else { run = 0; fired = false; }
            return false;
        }
    }

    /** Events reported by {@link Listener}. */
    public static final int EV_A = 0, EV_B = 1, EV_DONE = 2;

    /** The three detectors together; feed blocks, get events (EV_*) or -1. */
    public static final class Listener {
        private final Detector a, b, done;

        public Listener(int rate) {
            a = new Detector(A_HZ, ACK_BLOCKS, rate);
            b = new Detector(B_HZ, ACK_BLOCKS, rate);
            done = new Detector(DONE_HZ, DONE_BLOCKS, rate);
        }

        /** Returns a bit set of events found in this block (bit EV_A, EV_B, EV_DONE). */
        public int feed(short[] x, int off, int n) {
            int ev = 0;
            if (a.feed(x, off, n)) ev |= 1 << EV_A;
            if (b.feed(x, off, n)) ev |= 1 << EV_B;
            if (done.feed(x, off, n)) ev |= 1 << EV_DONE;
            return ev;
        }
    }
}
