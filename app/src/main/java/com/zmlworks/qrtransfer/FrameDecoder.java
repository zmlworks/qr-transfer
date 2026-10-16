package com.zmlworks.qrtransfer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.detector.FinderPattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes up to two QR codes from one grey-scale camera frame (pure ZXing, testable on the JVM).
 * <ol>
 *   <li>QRCodeMultiReader on the whole frame (finds every code whatever the layout/rotation);</li>
 *   <li>if nothing was found, the two halves are decoded separately — split across the long side and the short
 *       side on alternate frames (stacked phone sender / side-by-side PC sender, either way the receiver is held);</li>
 *   <li>whenever exactly one code was found, it is painted white in a copy of the frame (its finder centres
 *       ± 4.5 modules) and the whole frame is decoded again, so the other code is found wherever it is and the
 *       first one's finder patterns cannot confuse the detector.</li>
 * </ol>
 */
public final class FrameDecoder {
    private final QRCodeMultiReader multi = new QRCodeMultiReader();
    private final QRCodeReader single = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private int frameNo;
    private byte[] erased;

    public FrameDecoder() {
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    }

    /** A decoded code with its result points in full-frame coordinates. */
    private static final class Hit {
        final String text;
        final ResultPoint[] pts;
        final float module;   // estimated module size in px (0 = unknown)
        Hit(String text, ResultPoint[] pts, float offX, float offY) {
            this.text = text;
            float m = 0;
            int n = 0;
            ResultPoint[] moved = pts == null ? null : new ResultPoint[pts.length];
            if (pts != null) for (int i = 0; i < pts.length; i++) {
                ResultPoint p = pts[i];
                if (p == null) continue;
                if (p instanceof FinderPattern) { m += ((FinderPattern) p).getEstimatedModuleSize(); n++; }
                moved[i] = new ResultPoint(p.getX() + offX, p.getY() + offY);
            }
            this.pts = moved;
            this.module = n > 0 ? m / n : 0;
        }
    }

    /** @param y luminance, row-major w×h */
    public List<String> decode(byte[] y, int w, int h) {
        frameNo++;
        Set<String> out = new LinkedHashSet<>();
        Hit last = null;
        try {
            Result[] rs = multi.decodeMultiple(bitmap(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)), hints);
            for (Result r : rs) if (r.getText() != null && out.add(r.getText())) last = new Hit(r.getText(), r.getResultPoints(), 0, 0);
        } catch (NotFoundException ignored) {
            // none found as a group; the halves below may still work
        } catch (RuntimeException ignored) {
            // ZXing can throw on degenerate geometry; never let a bad frame kill the camera thread
        }
        if (out.isEmpty()) {
            boolean splitLong = (frameNo & 1) == 1;     // first try: across the long side (the usual layout)
            boolean vertical = (w >= h) != splitLong;   // vertical = top/bottom halves (in image coordinates)
            Hit a, b;
            if (vertical) {
                a = tryOne(y, w, h, 0, 0, w, h / 2);
                b = tryOne(y, w, h, 0, h / 2, w, h - h / 2);
            } else {
                a = tryOne(y, w, h, 0, 0, w / 2, h);
                b = tryOne(y, w, h, w / 2, 0, w - w / 2, h);
            }
            if (a != null && out.add(a.text)) last = a;
            if (b != null && out.add(b.text)) last = b;
        }
        if (out.size() == 1 && last != null) {
            Hit other = eraseAndRetry(y, w, h, last);
            if (other != null) out.add(other.text);
        }
        return new ArrayList<>(out);
    }

    private Hit eraseAndRetry(byte[] y, int w, int h, Hit found) {
        if (found.pts == null || found.pts.length < 3) return null;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -1, y1 = -1;
        for (ResultPoint p : found.pts) {
            if (p == null) continue;
            x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX());
            y0 = Math.min(y0, p.getY()); y1 = Math.max(y1, p.getY());
        }
        if (x1 < 0) return null;
        // finder centres are 3.5 modules inside the symbol edge; erase 4.5 modules beyond them
        float pad = found.module > 0 ? 4.5f * found.module : 0.1f * Math.max(x1 - x0, y1 - y0);
        int l = Math.max(0, (int) (x0 - pad)), r = Math.min(w, (int) Math.ceil(x1 + pad)),
                t = Math.max(0, (int) (y0 - pad)), b = Math.min(h, (int) Math.ceil(y1 + pad));
        if (l >= r || t >= b) return null;
        if (erased == null || erased.length != y.length) erased = new byte[y.length];
        System.arraycopy(y, 0, erased, 0, y.length);
        for (int row = t; row < b; row++) Arrays.fill(erased, row * w + l, row * w + r, (byte) 0xFF);
        Hit h2 = tryOne(erased, w, h, 0, 0, w, h);
        return h2 != null && !h2.text.equals(found.text) ? h2 : null;
    }

    /** Single-code decode of a crop; result points translated to full-frame coordinates. */
    private Hit tryOne(byte[] y, int w, int h, int left, int top, int cw, int ch) {
        if (cw < 40 || ch < 40) return null;
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(y, w, h, left, top, cw, ch, false);
            Result r = single.decode(bitmap(src), hints);
            return new Hit(r.getText(), r.getResultPoints(), left, top);
        } catch (ReaderException | RuntimeException ignored) {
            return null;   // not readable here
        } finally {
            single.reset();
        }
    }

    private static BinaryBitmap bitmap(LuminanceSource s) { return new BinaryBitmap(new HybridBinarizer(s)); }
}
