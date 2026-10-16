package com.zmlworks.qrtransfer;

/**
 * Acknowledgment tones. The receiver beeps ACK on every new chunk and DONE when complete; the
 * sender's microphone detects them with the Goertzel algorithm (one DFT bin, cheap per block).
 */
public final class Tone {
    private Tone() {}

    public static final int RATE = 16_000;
    public static final double ACK_HZ = 1900, DONE_HZ = 2850;   // 2850 = 1.5 x 1900: not a harmonic
    public static final int ACK_MS = 120, DONE_MS = 450;
    public static final int BLOCK = 256;                      // 16 ms at 16 kHz

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
            this.hz = hz; this.need = need; this.rate = rate; this.threshold = 0.45; this.minRms = 120;
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
}
