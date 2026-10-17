package com.zmlworks.qrtransfer;

/**
 * Exact 2× bicubic upscale of an 8-bit grey image (pure Java, used before a retry when a frame yields no code).
 * <p>Keys cubic with a = −0.75 (the kernel of OpenCV's INTER_CUBIC, which the QR-engine research measured: at 2 camera
 * pixels per module zxing-cpp read 0/30 codes as is and 30/30 after 2× bicubic; bilinear did not help). For a 2×
 * scale with pixel-centre alignment the output samples sit at ±¼ pixel, so the kernel reduces to two fixed 4-tap
 * filters, (−36, 268, 900, −108)/1024 and its mirror image, applied separably in integer arithmetic (exact, no floats).
 */
public final class Upscale2x {
    private Upscale2x() {}

    private static final int W0 = -36, W1 = 268, W2 = 900, W3 = -108;   // sum 1024

    /**
     * @param out destination of at least 4·w·h bytes (reused between frames), or null to allocate
     * @return out (2w × 2h, row-major)
     */
    public static byte[] run(byte[] src, int w, int h, byte[] out, int[] rowBuf) {
        int W = 2 * w, H = 2 * h;
        if (out == null || out.length < W * H) out = new byte[W * H];
        // horizontal pass into 4 rolling rows of ints is enough for the vertical 4-tap filter; for simplicity and
        // speed (one pass over memory) keep a full intermediate of 2w × h ints.
        int[] tmp = rowBuf != null && rowBuf.length >= W * h ? rowBuf : new int[W * h];
        for (int y = 0; y < h; y++) {
            int o = y * w, t = y * W;
            for (int x = 0; x < w; x++) {
                int pm2 = src[o + Math.max(0, x - 2)] & 255, pm1 = src[o + Math.max(0, x - 1)] & 255, p0 = src[o + x] & 255,
                        p1 = src[o + Math.min(w - 1, x + 1)] & 255, p2 = src[o + Math.min(w - 1, x + 2)] & 255;
                tmp[t + 2 * x] = W0 * pm2 + W1 * pm1 + W2 * p0 + W3 * p1;        // x − ¼
                tmp[t + 2 * x + 1] = W3 * pm1 + W2 * p0 + W1 * p1 + W0 * p2;     // x + ¼
            }
        }
        final int round = 1 << 19;   // (1024 · 1024) / 2
        for (int y = 0; y < h; y++) {
            int rm2 = Math.max(0, y - 2) * W, rm1 = Math.max(0, y - 1) * W, r0 = y * W,
                    r1 = Math.min(h - 1, y + 1) * W, r2 = Math.min(h - 1, y + 2) * W;
            int oa = (2 * y) * W, ob = oa + W;
            for (int x = 0; x < W; x++) {
                int a = tmp[rm2 + x], b = tmp[rm1 + x], c = tmp[r0 + x], d = tmp[r1 + x], e = tmp[r2 + x];
                int va = (W0 * a + W1 * b + W2 * c + W3 * d + round) >> 20;
                int vb = (W3 * b + W2 * c + W1 * d + W0 * e + round) >> 20;
                out[oa + x] = (byte) (va < 0 ? 0 : va > 255 ? 255 : va);
                out[ob + x] = (byte) (vb < 0 ? 0 : vb > 255 ? 255 : vb);
            }
        }
        return out;
    }
}
