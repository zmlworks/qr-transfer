package com.zmlworks.qrtransfer;

import java.util.List;

/**
 * Receiver decode step (pure Java, testable on the JVM): engine on the camera frame; if it found nothing, optionally
 * once more on a 2× bicubic upscale ({@link Upscale2x}) — for codes below ~3 camera pixels per module — at most every
 * {@link #UPSCALE_MIN_INTERVAL_MS}, so a frame without codes does not cost an upscale every time. Keeps simple timing
 * statistics for the on-screen diagnostics.
 */
public final class DecodePipeline {
    public static final long UPSCALE_MIN_INTERVAL_MS = 400;
    /** frames larger than this (pixels) are not upscaled (memory) */
    public static final int UPSCALE_MAX_PIXELS = 1920 * 1088;

    private volatile QrEngine engine;
    private volatile boolean upscale;
    private long lastUpscaleAt = Long.MIN_VALUE / 2;
    private byte[] up;
    private int[] tmp;
    // statistics (camera thread writes, UI reads; approximate is fine)
    private volatile int frames, codes, upTries, upHits;
    private volatile double avgMs = -1, avgUpMs = -1;
    private volatile int lastCount;
    private volatile double upscaleOnlyMs = -1;

    public DecodePipeline(QrEngine engine, boolean upscale) { this.engine = engine; this.upscale = upscale; }

    public void setEngine(QrEngine e) { engine = e; resetStats(); }
    public QrEngine engine() { return engine; }
    public void setUpscale(boolean on) { upscale = on; }
    public boolean upscale() { return upscale; }

    public List<String> decode(byte[] y, int w, int h, long nowMs) {
        QrEngine e = engine;
        long t0 = System.nanoTime();
        List<String> r = e.decode(y, w, h);
        double ms = (System.nanoTime() - t0) / 1e6;
        avgMs = avgMs < 0 ? ms : avgMs * 0.9 + ms * 0.1;
        if (r.isEmpty() && upscale && (long) w * h <= UPSCALE_MAX_PIXELS && nowMs - lastUpscaleAt >= UPSCALE_MIN_INTERVAL_MS) {
            lastUpscaleAt = nowMs;
            long t1 = System.nanoTime();
            if (tmp == null || tmp.length < 2 * w * h) tmp = new int[2 * w * h];
            up = Upscale2x.run(y, w, h, up, tmp);
            upscaleOnlyMs = (System.nanoTime() - t1) / 1e6;
            r = e.decode(up, 2 * w, 2 * h);
            double ums = (System.nanoTime() - t1) / 1e6;
            avgUpMs = avgUpMs < 0 ? ums : avgUpMs * 0.8 + ums * 0.2;
            upTries++;
            if (!r.isEmpty()) upHits++;
        }
        frames++;
        codes += r.size();
        lastCount = r.size();
        return r;
    }

    public void resetStats() { frames = codes = upTries = upHits = lastCount = 0; avgMs = avgUpMs = -1; }
    public int frames() { return frames; }
    public int codes() { return codes; }
    public int lastCount() { return lastCount; }
    public int upscaleTries() { return upTries; }
    public int upscaleHits() { return upHits; }
    /** smoothed decode time per frame (ms), -1 before the first frame */
    public double avgMs() { return avgMs; }
    /** time of the last 2× upscale alone (ms), -1 before the first try */
    public double lastUpscaleOnlyMs() { return upscaleOnlyMs; }
    /** smoothed upscale + decode time (ms), -1 before the first try */
    public double avgUpscaleMs() { return avgUpMs; }
}
