package com.zmlworks.qrtransfer;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import zxingcpp.BarcodeReader;

/**
 * zxing-cpp (native, Apache-2.0; AAR io.github.zxing-cpp:android) on the camera's Y plane. The Y bytes are copied into
 * an {@code ALPHA_8} bitmap, which the library reads as luminance ({@link BarcodeReader#read(Bitmap, Rect, int)}; the
 * AAR's CameraX entry point is not used, CameraX is excluded from the build). Options: QR only, up to 255 symbols per
 * frame, no rotate/invert/downscale retries (the research benchmark's "fast" settings: every code of a 10–21-code page
 * at ≥ 3 camera px/module, 5–18 ms per 1080p frame on a desktop CPU).
 * <p>{@link #create()} returns null if the native library cannot be loaded (e.g. an ABI that is not packaged); the
 * receiver then uses the Java ZXing {@link FrameDecoder}.
 */
final class CppEngine implements QrEngine {
    private final BarcodeReader reader;
    private Bitmap bmp, bmpUp;
    private final Rect rect = new Rect();
    private byte[] rowCopy;

    private CppEngine(BarcodeReader r) { reader = r; }

    static CppEngine create() {
        try {
            BarcodeReader.Options o = new BarcodeReader.Options();
            Set<BarcodeReader.Format> f = EnumSet.of(BarcodeReader.Format.QR_CODE);
            o.setFormats(f);
            o.setTryHarder(true);    // the C++ default (the Kotlin wrapper defaults to false); the benchmark used true
            o.setTryRotate(false);
            o.setTryInvert(false);
            o.setTryDownscale(false);
            o.setMaxNumberOfSymbols(255);
            o.setBinarizer(BarcodeReader.Binarizer.LOCAL_AVERAGE);
            return new CppEngine(new BarcodeReader(o));
        } catch (Throwable t) {   // UnsatisfiedLinkError, NoClassDefFoundError, ExceptionInInitializerError
            return null;
        }
    }

    @Override public String name() { return "zxing-cpp"; }

    @Override public List<String> decode(byte[] y, int w, int h) {
        // two cached bitmaps: the camera size and the 2× upscale used by DecodePipeline
        Bitmap b;
        if (bmp != null && bmp.getWidth() == w && bmp.getHeight() == h) b = bmp;
        else if (bmpUp != null && bmpUp.getWidth() == w && bmpUp.getHeight() == h) b = bmpUp;
        else {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            if (bmp == null) bmp = b;
            else { if (bmpUp != null) bmpUp.recycle(); bmpUp = b; }
        }
        int rb = b.getRowBytes();
        if (rb == w) b.copyPixelsFromBuffer(ByteBuffer.wrap(y, 0, w * h));
        else {   // padded rows (not seen for ALPHA_8 in practice)
            if (rowCopy == null || rowCopy.length < rb * h) rowCopy = new byte[rb * h];
            for (int r = 0; r < h; r++) System.arraycopy(y, r * w, rowCopy, r * rb, w);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(rowCopy, 0, rb * h));
        }
        rect.set(0, 0, w, h);
        List<BarcodeReader.Result> rs;
        try {
            rs = reader.read(b, rect, 0);
        } catch (RuntimeException e) {
            return Collections.emptyList();   // never let one bad frame kill the camera thread
        }
        if (rs == null || rs.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (BarcodeReader.Result r : rs) {
            String t = r.getText();
            if (t != null && !t.isEmpty() && r.getError() == null) out.add(t);
        }
        return new ArrayList<>(out);
    }
}
