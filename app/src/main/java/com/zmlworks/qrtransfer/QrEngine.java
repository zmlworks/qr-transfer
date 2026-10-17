package com.zmlworks.qrtransfer;

import java.util.List;

/** One QR decoding engine: every QR code text found in a grey-scale (luminance) frame. */
public interface QrEngine {
    /** @param y luminance, row-major w×h (one byte per pixel) */
    List<String> decode(byte[] y, int w, int h);
    /** short name for the UI / logs */
    String name();
}
