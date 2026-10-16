package com.zmlworks.qrtransfer;

/**
 * Acknowledgment tones, v0.3.0 (receiver → sender, the only back channel; no radio).
 * <p>Alternating-bit acks: every chunk is acknowledged by the tone of (slot, parity) — slot = index &amp; 1
 * (A = top/left = even chunks, B = bottom/right = odd chunks), parity = (index &gt;&gt; 1) &amp; 1 (position within the
 * slot, alternating 0/1). So the tone tells the sender exactly which of two consecutive chunks of a slot was read
 * (index mod 4):
 * <pre>
 *   A0 1350 Hz  chunk ≡ 0 (mod 4)      B0 2050 Hz  chunk ≡ 1 (mod 4)
 *   A1 1700 Hz  chunk ≡ 2 (mod 4)      B1 2400 Hz  chunk ≡ 3 (mod 4)
 *   DONE 3050 Hz, long (400 ms)        whole transfer received and CRC-verified
 * </pre>
 * Why these: all inside 1.3–3.1 kHz, where small phone speakers are loud and laptop/phone microphones are flat and far
 * above mains hum and the bulk of speech energy. Neighbours are ≥ 350 Hz apart (≥ 5.6 Goertzel bins at 62.5 Hz/bin;
 * the Hann main lobe is ±2 bins). No tone is 2× or 3× another, and every 2nd/3rd harmonic lands ≥ 300 Hz from every
 * other tone (2×1350 = 2700, 2×1700 = 3400, …), so speaker distortion cannot fake another tone. They also stay ≥ 150 Hz
 * (≥ 2.4 bins, rejected by the neighbour-bin test) from the 0.1.0/0.2.0 tones (1500/1900/2200/2700/2850 Hz), so an old
 * receiver's beeps are never mistaken for 0.3.0 acks.
 * <p>Durations: an ack is 80 ms (10 ms raised-cosine fades); the detector needs 3 consecutive 16 ms blocks (48 ms; a blip shorter than ~33 ms can never span them), which
 * leaves margin for unaligned blocks, fades and room echo. Tones are serialised with a 40 ms gap so the detector re-arms.
 * DONE is 400 ms and needs 10 consecutive blocks (160 ms), so no short ack (or a short burst of anything) can end a
 * transfer.
 */
public final class Tone {
    private Tone() {}

    public static final int RATE = 16_000;
    /** tone ids (index into {@link #HZ}) */
    public static final int A0 = 0, A1 = 1, B0 = 2, B1 = 3, DONE = 4, COUNT = 5;
    public static final double[] HZ = {1350, 1700, 2050, 2400, 3050};
    public static final String[] NAMES = {"A0", "A1", "B0", "B1", "DONE"};
    public static final int ACK_MS = 80, DONE_MS = 400, GAP_MS = 40;
    public static final int BLOCK = 256;                       // 16 ms at 16 kHz (62.5 Hz/bin)
    public static final int ACK_BLOCKS = 3, DONE_BLOCKS = 10;  // consecutive blocks needed: 48 ms / 160 ms
    /** detection thresholds (shared with web/sender.html) */
    public static final double MIN_RATIO = 0.30, NEIGHBOUR_HZ = 190, NEIGHBOUR_FACTOR = 6, OTHER_FACTOR = 6,
            MIN_RMS = 120 / 32767.0, FLOOR_FACTOR = 2.0;

    /** Ack tone id for a chunk index: slot (index &amp; 1) and alternating bit ((index &gt;&gt; 1) &amp; 1). */
    public static int toneOf(int index) { return (index & 1) * 2 + ((index >> 1) & 1); }
    public static int slotOfTone(int tone) { return tone >> 1; }

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
     * Fraction of the block's energy at frequency hz: 1.0 for a pure tone at hz, ~0 for silence or other tones.
     * Generalized Goertzel (non-integer bin) over a Hann-windowed block. Samples are normalized (−1..1).
     */
    public static double ratio(double[] x, int n, double hz, int rate) {
        double w = 2 * Math.PI * hz / rate, coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0, energy = 0, sumW = 0, sumW2 = 0;
        for (int i = 0; i < n; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            double v = x[i] * win;
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

    public static double ratio(short[] x, int off, int n, double hz, int rate) {
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = x[off + i] / 32768.0;
        return ratio(d, n, hz, rate);
    }

    /**
     * Which tone (if any) dominates one block, or -1. A tone wins only if
     * (1) the block is loud enough: rms ≥ MIN_RMS and ≥ FLOOR_FACTOR × the tracked noise floor,
     * (2) ≥ MIN_RATIO of the block energy is at its frequency,
     * (3) it is ≥ NEIGHBOUR_FACTOR × stronger than the bins ±190 Hz around it (local noise / off-frequency tones), and
     * (4) it is ≥ OTHER_FACTOR × stronger than every other protocol tone.
     */
    public static int classify(double[] x, int n, int rate, double noiseFloor, double[] ratiosOut) {
        double e = 0;
        for (int i = 0; i < n; i++) e += x[i] * x[i];
        double rms = Math.sqrt(e / n);
        double[] r = ratiosOut != null ? ratiosOut : new double[COUNT];
        int best = -1;
        for (int k = 0; k < COUNT; k++) {
            r[k] = ratio(x, n, HZ[k], rate);
            if (best < 0 || r[k] > r[best]) best = k;
        }
        if (rms < MIN_RMS || rms < FLOOR_FACTOR * noiseFloor || r[best] < MIN_RATIO) return -1;
        for (int k = 0; k < COUNT; k++) if (k != best && r[best] < OTHER_FACTOR * r[k]) return -1;
        double lo = ratio(x, n, HZ[best] - NEIGHBOUR_HZ, rate), hi = ratio(x, n, HZ[best] + NEIGHBOUR_HZ, rate);
        if (r[best] < NEIGHBOUR_FACTOR * Math.max(lo, hi)) return -1;
        return best;
    }

    /**
     * Debounced 5-tone detector: feed blocks of {@link #BLOCK}-ish samples, get at most one event per block —
     * the tone id (A0..DONE) the first time a tone has lasted its minimum duration, else -1.
     * One event per tone burst; the detector re-arms after a block without that tone.
     */
    public static final class Listener {
        private final int rate;
        private final double[] buf;
        private final double[] ratios = new double[COUNT];
        private int run, runTone = -1;
        private boolean fired;
        private double floor = 0;
        private int lastClass = -1;
        private double lastRms;

        public Listener(int rate) { this.rate = rate; this.buf = new double[Math.round(rate * 0.016f)]; }
        public int blockSize() { return buf.length; }
        /** result of the last block (for a live indicator): tone id or -1, its rms */
        public int lastClass() { return lastClass; }
        public double lastRms() { return lastRms; }
        public double noiseFloor() { return floor; }

        /** One block of 16-bit samples (length = blockSize()). */
        public int feed(short[] x, int off, int n) {
            for (int i = 0; i < n; i++) buf[i] = x[off + i] / 32768.0;
            return feedBlock(buf, n);
        }

        public int feedBlock(double[] x, int n) {
            double e = 0;
            for (int i = 0; i < n; i++) e += x[i] * x[i];
            double rms = Math.sqrt(e / n);
            int c = classify(x, n, rate, floor, ratios);
            lastClass = c;
            lastRms = rms;
            // noise floor: follows quiet blocks quickly, loud non-tone blocks slowly (never learns from tones)
            if (c < 0) floor = rms < floor ? 0.7 * floor + 0.3 * rms : 0.98 * floor + 0.02 * rms;
            if (c >= 0 && c == runTone) run++;
            else { run = c >= 0 ? 1 : 0; runTone = c; fired = false; }
            int need = c == DONE ? DONE_BLOCKS : ACK_BLOCKS;
            if (c >= 0 && run >= need && !fired) { fired = true; return c; }
            return -1;
        }
    }
}
