package com.zmlworks.qrtransfer;

/**
 * Acknowledgment tones (receiver → sender, the only back channel; no radio). Frequencies unchanged since 0.3.0.
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
 * above mains hum and the bulk of speech energy. Neighbours are ≥ 350 Hz apart, no tone is 2× or 3× another, and
 * every 2nd/3rd harmonic lands ≥ 300 Hz from every other tone. They stay ≥ 150 Hz from the 0.1.0/0.2.0 tones.
 * <p>0.3.1: acks are 120 ms (0.3.0: 80 ms; a phone's speaker amplifier can swallow the first tens of ms after
 * silence) with a 60 ms gap; DONE 400 ms. Detection still works on 0.3.0's 80 ms acks.
 * <p><b>Detector 0.3.1.</b> The 0.3.0 detector demanded an absolute level of −49 dBFS on the whole block, 30 % of the
 * full-band block energy at the tone and a broadband level 2× the noise floor. With the browser's/OS's automatic gain
 * control off (as it must be), a phone 30–60 cm from a laptop often arrives at −50…−65 dBFS, and a fan, hum or DC
 * offset below 900 Hz easily owns more than 70 % of the energy — so real tones were rejected although the level meter
 * moved. Simulated speaker → room → mic channel (tools/acoustic-sim.js): 0.3.0 detected 1–8 % of acks at −55…−62
 * dBFS, ~40 % with a loud fan, ~25–40 % with someone talking. Now, per 16 ms Hann block of the signal high-passed at
 * {@link #HP_HZ} (removes DC, hum, fan rumble, most speech power):
 * <ol>
 *   <li>the tone's level is ≥ SNR × <em>its own</em> tracked noise floor (per frequency) and ≥ {@link #MIN_DBFS},</li>
 *   <li>it carries ≥ FRAC of the high-passed block energy,</li>
 *   <li>it is ≥ 4× (6 dB) the probes 160/190/250/320 Hz either side (a voice's harmonic comb hits one of them) and
 *       ≥ 4× every other protocol tone,</li>
 *   <li>the 300–900 Hz band (first formant, low harmonics of a voice) has not risen above its own tracked floor
 *       (×{@link #LOW_MARGIN}) by more than the tone's energy — a beep does not do that, a steady fan is in the floor,</li>
 *   <li>it is centred: the ±40 Hz probes are balanced (within about ±14 Hz; both ends are crystal-locked, while a voice
 *       harmonic or a whistle is rarely exactly on frequency).</li>
 * </ol>
 * A burst counts once {@link #ACK_BLOCKS} (DONE: {@link #DONE_BLOCKS}) blocks of that tone are seen, tolerating a
 * single quiet block (fade, dip, click) but no block of another tone; the same tone cannot count again within
 * {@link #REFRACTORY_BLOCKS} of its last block (room echo). SNR / FRAC come from a sensitivity 1–5 (default 3).
 * Identical to the detector in web/sender.html (checked block by block in the tests).
 */
public final class Tone {
    private Tone() {}

    public static final int RATE = 16_000;
    /** tone ids (index into {@link #HZ}) */
    public static final int A0 = 0, A1 = 1, B0 = 2, B1 = 3, DONE = 4, COUNT = 5;
    public static final double[] HZ = {1350, 1700, 2050, 2400, 3050};
    public static final String[] NAMES = {"A0", "A1", "B0", "B1", "DONE"};
    public static final int ACK_MS = 120, DONE_MS = 400, GAP_MS = 60;
    public static final int BLOCK = 256;                       // 16 ms at 16 kHz (62.5 Hz/bin)
    public static final int ACK_BLOCKS = 3, DONE_BLOCKS = 10;  // blocks needed: 48 ms / 160 ms
    /** neighbour probes (Hz either side): a voice's harmonic comb (f0 85–300 Hz) puts energy on one of them */
    static final double[] NEIGHBOUR_HZ = {160, 190, 250, 320};
    public static final double NEIGHBOUR_FACTOR = 4, OTHER_FACTOR = 4, HP_HZ = 900, MIN_DBFS = -85,
            CENTRE_PROBE_HZ = 40, CENTRE_ASYM = 0.35, LOW_HZ = 300, LOW_MARGIN = 2, LOW_FACTOR = 1;
    public static final int WARMUP_BLOCKS = 4, REFRACTORY_BLOCKS = 12, DEFAULT_SENSITIVITY = 3;
    /** per sensitivity 1 (strict) .. 5 (very sensitive): tone level over its noise floor, share of band energy */
    static final double[] SENS_SNR = {0, 20, 12, 8, 5, 3}, SENS_FRAC = {0, 0.45, 0.38, 0.3, 0.2, 0.12};

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

    /** Hann-windowed generalized Goertzel: squared amplitude of the sinusoid at hz (a tone of amplitude A gives A²). */
    static double toneAmp2(double[] x, int n, double hz, int rate, double[] win, double sw) {
        double w = 2 * Math.PI * hz / rate, coeff = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) { double s0 = x[i] * win[i] + coeff * s1 - s2; s2 = s1; s1 = s0; }
        return Math.max(0, s1 * s1 + s2 * s2 - coeff * s1 * s2) * 4 / (sw * sw);
    }

    /**
     * Debounced 5-tone detector: feed blocks of {@link #blockSize()} samples, get at most one event per block —
     * the tone id (A0..DONE) the first time a tone has lasted its minimum duration, else -1.
     * Live diagnostics after each block: {@link #lastClass()}, {@link #level(int)}, {@link #floorDb(int)}.
     * Not thread-safe; the diagnostics getters may be read from another thread (values are plain doubles).
     */
    public static final class Listener {
        private final int rate, block;
        private final double[] buf, hp, win;
        private final double sw, sw2;
        private final double hb0, hb1, hb2, ha1, ha2, lb0, lb1, lb2, la1, la2, pb0, pb1, pb2, pa1, pa2;
        private double x1, x2, y1, y2, z1, z2, u1, u2, q1, q2, lowE, lowFloor;
        private final double[] p = new double[COUNT], floor = new double[COUNT];
        private final double[] level = new double[COUNT], floorDb = new double[COUNT];
        private final double minAmp2 = 2 * Math.pow(10, MIN_DBFS / 10);
        private int blocks, run, runTone = -1, holes, firedTone = -1;
        private long firedLast = -1_000_000_000L;
        private boolean fired;
        private int lastClass = -1, sensitivity;
        private double snr, frac, lastRms, lastBandRms;

        public Listener(int rate) { this(rate, DEFAULT_SENSITIVITY); }

        public Listener(int rate, int sensitivity) {
            this.rate = rate;
            this.block = Math.round(rate * 0.016f);
            buf = new double[block];
            hp = new double[block];
            win = new double[block];
            double a = 0, b = 0;
            for (int i = 0; i < block; i++) { win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (block - 1)); a += win[i]; b += win[i] * win[i]; }
            sw = a;
            sw2 = b;
            double w0 = 2 * Math.PI * HP_HZ / rate, cw = Math.cos(w0), al = Math.sin(w0) / (2 * Math.sqrt(0.5)), a0 = 1 + al;
            hb0 = (1 + cw) / 2 / a0;
            hb1 = -(1 + cw) / a0;
            hb2 = hb0;
            ha1 = -2 * cw / a0;
            ha2 = (1 - al) / a0;
            // voice band 300–900 Hz: high-pass 300 Hz, then two low-pass 900 Hz sections
            w0 = 2 * Math.PI * LOW_HZ / rate; cw = Math.cos(w0); al = Math.sin(w0) / (2 * Math.sqrt(0.5)); a0 = 1 + al;
            lb0 = (1 + cw) / 2 / a0; lb1 = -(1 + cw) / a0; lb2 = lb0; la1 = -2 * cw / a0; la2 = (1 - al) / a0;
            w0 = 2 * Math.PI * HP_HZ / rate; cw = Math.cos(w0); al = Math.sin(w0) / (2 * Math.sqrt(0.5)); a0 = 1 + al;
            pb0 = (1 - cw) / 2 / a0; pb1 = (1 - cw) / a0; pb2 = pb0; pa1 = -2 * cw / a0; pa2 = (1 - al) / a0;
            setSensitivity(sensitivity);
        }

        public void setSensitivity(int s) {
            s = Math.max(1, Math.min(5, s));
            sensitivity = s;
            snr = SENS_SNR[s];
            frac = SENS_FRAC[s];
        }
        public int sensitivity() { return sensitivity; }
        public int blockSize() { return block; }
        /** result of the last block: tone id or -1 */
        public int lastClass() { return lastClass; }
        /** rms of the last block (0..1, full band) */
        public double lastRms() { return lastRms; }
        /** rms of the last block above {@link #HP_HZ} */
        public double lastBandRms() { return lastBandRms; }
        /** level of tone k in the last block, dBFS (rms of the sinusoid) */
        public double level(int k) { return level[k]; }
        /** tracked noise floor at tone k, dBFS */
        public double floorDb(int k) { return floorDb[k]; }

        /** One block of 16-bit samples (length = blockSize()). */
        public int feed(short[] x, int off, int n) {
            for (int i = 0; i < n; i++) buf[i] = x[off + i] / 32768.0;
            return feedBlock(buf, n);
        }

        private int classify(double[] x) {
            double e = 0, eh = 0, el = 0;
            for (int i = 0; i < block; i++) {
                double v = x[i];
                e += v * v;
                double y = hb0 * v + hb1 * x1 + hb2 * x2 - ha1 * y1 - ha2 * y2;
                double z = lb0 * v + lb1 * x1 + lb2 * x2 - la1 * z1 - la2 * z2;
                double u = pb0 * z + pb1 * z1 + pb2 * z2 - pa1 * u1 - pa2 * u2;
                double q = pb0 * u + pb1 * u1 + pb2 * u2 - pa1 * q1 - pa2 * q2;
                x2 = x1; x1 = v; y2 = y1; y1 = y; z2 = z1; z1 = z; u2 = u1; u1 = u; q2 = q1; q1 = q; hp[i] = y;
                double hw = y * win[i], lw = q * win[i];
                eh += hw * hw;
                el += lw * lw;
            }
            lastRms = Math.sqrt(e / block);
            lastBandRms = Math.sqrt(eh / sw2);
            lowE = el;
            int best = -1;
            for (int k = 0; k < COUNT; k++) { p[k] = toneAmp2(hp, block, HZ[k], rate, win, sw); if (best < 0 || p[k] > p[best]) best = k; }
            blocks++;
            if (blocks <= WARMUP_BLOCKS) return -1;
            double pb = p[best];
            if (eh <= 0 || pb < minAmp2 || pb * sw2 / 2 < frac * eh || pb < snr * floor[best]) return -1;
            // a voice brings its first formant and low harmonics (300–900 Hz) up with it; a beep does not
            if (el - LOW_MARGIN * lowFloor > LOW_FACTOR * pb * sw2 / 2) return -1;
            for (int k = 0; k < COUNT; k++) if (k != best && pb < OTHER_FACTOR * p[k]) return -1;
            for (double d : NEIGHBOUR_HZ) {
                if (pb < NEIGHBOUR_FACTOR * toneAmp2(hp, block, HZ[best] - d, rate, win, sw)) return -1;
                if (pb < NEIGHBOUR_FACTOR * toneAmp2(hp, block, HZ[best] + d, rate, win, sw)) return -1;
            }
            double pp = toneAmp2(hp, block, HZ[best] + CENTRE_PROBE_HZ, rate, win, sw), pm = toneAmp2(hp, block, HZ[best] - CENTRE_PROBE_HZ, rate, win, sw);
            if (Math.abs(pp - pm) > CENTRE_ASYM * (pp + pm)) return -1;
            return best;
        }

        /** n must equal blockSize() */
        public int feedBlock(double[] x, int n) {
            if (n != block) throw new IllegalArgumentException("block " + n + " != " + block);
            int c = classify(x);
            if (blocks == 1) lowFloor = lowE;
            else if (c < 0) lowFloor = lowE < lowFloor ? 0.7 * lowFloor + 0.3 * lowE : 0.985 * lowFloor + 0.015 * lowE;
            for (int k = 0; k < COUNT; k++) {
                if (k == c || (k == runTone && run > 0)) continue;
                if (blocks == 1) floor[k] = p[k];
                else floor[k] = p[k] < floor[k] ? 0.7 * floor[k] + 0.3 * p[k] : 0.985 * floor[k] + 0.015 * p[k];
            }
            for (int k = 0; k < COUNT; k++) {
                level[k] = 10 * Math.log10(Math.max(p[k] / 2, 1e-13));
                floorDb[k] = 10 * Math.log10(Math.max(floor[k] / 2, 1e-13));
            }
            lastClass = c;
            if (c >= 0 && c == runTone) { run++; holes = 0; }
            else if (c < 0 && runTone >= 0 && holes < 1) holes++;
            else { run = c >= 0 ? 1 : 0; runTone = c; holes = 0; fired = c >= 0 && c == firedTone && blocks - firedLast <= REFRACTORY_BLOCKS; }
            if (c >= 0 && fired && c == firedTone) firedLast = blocks;
            if (c >= 0 && run >= (c == DONE ? DONE_BLOCKS : ACK_BLOCKS) && !fired) { fired = true; firedTone = c; firedLast = blocks; return c; }
            return -1;
        }
    }
}
