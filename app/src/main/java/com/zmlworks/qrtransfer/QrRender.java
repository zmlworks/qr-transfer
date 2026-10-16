package com.zmlworks.qrtransfer;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Frame text -> 1 pixel per module bitmap (scaled up later without filtering). */
final class QrRender {
    private QrRender() {}

    static Bitmap render(String text, ErrorCorrectionLevel ecl) throws WriterException {
        Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
        h.put(EncodeHintType.ERROR_CORRECTION, ecl);
        h.put(EncodeHintType.MARGIN, 4);   // quiet zone in modules
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, h);
        int w = m.getWidth(), hh = m.getHeight();
        int[] px = new int[w * hh];
        for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888);
    }

    /** QR version from a rendered bitmap with a 4-module margin. */
    static int version(Bitmap b) { return (b.getWidth() - 8 - 17) / 4; }
}
