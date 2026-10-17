package com.google.zxing.qrcode.encoder;
/** Test-only bridge to ZXing's package-private MaskUtil (ISO 18004 penalty rules). */
public final class PenaltyProbe {
  public static int penalty(ByteMatrix m) {
    return MaskUtil.applyMaskPenaltyRule1(m) + MaskUtil.applyMaskPenaltyRule2(m) + MaskUtil.applyMaskPenaltyRule3(m) + MaskUtil.applyMaskPenaltyRule4(m);
  }
}
