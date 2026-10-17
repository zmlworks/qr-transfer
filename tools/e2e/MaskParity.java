import com.google.zxing.*; import com.google.zxing.common.*; import com.google.zxing.qrcode.*; import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.*; import java.nio.file.*; import java.util.*;
/**
 * For each case written by tools/test-grid.js: (1) ZXing's MaskUtil penalty of each of the 8 JS variants equals the JS
 * penalty; (2) ZXing's own encoder produces exactly the JS best-penalty symbol (same mask, same modules); (3) ZXing's
 * FrameDecoder (the app's) decodes all 8 JS variants to the identical text (variants its detector cannot find are listed;
 * they must still decode with PURE_BARCODE).
 */
public class MaskParity { public static void main(String[] a) throws Exception {
  int cases = 0, decoded = 0; List<String> undetected = new ArrayList<>();
  for (String line : Files.readAllLines(Paths.get(a[0]))) {
    if (line.isEmpty()) continue;
    String[] f = line.split("\t"); String ecl = f[0], text = f[1]; int best = Integer.parseInt(f[2]);
    String[] pens = f[3].split(","), mats = f[4].split(",");
    int n = (int) Math.round(Math.sqrt(mats[0].length()));
    QRCode zx = Encoder.encode(text, ErrorCorrectionLevel.valueOf(ecl));
    for (int m = 0; m < 8; m++) {
      ByteMatrix bm = new ByteMatrix(n, n);
      for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) bm.set(c, r, mats[m].charAt(r * n + c) == '1' ? 1 : 0);
      int p = PenaltyProbe.penalty(bm);
      if (p != Integer.parseInt(pens[m])) throw new AssertionError("penalty m" + m + ": ZXing " + p + " JS " + pens[m]);
      if (m == zx.getMaskPattern()) for (int r = 0; r < n; r++) for (int c = 0; c < n; c++)
        if (zx.getMatrix().get(c, r) != bm.get(c, r)) throw new AssertionError("module " + r + "," + c + " differs from ZXing's symbol");
      // decode the variant with the app's FrameDecoder (4-module quiet zone, 3 px per module); if the detector can't
      // find it, the symbol must still be valid (PURE_BARCODE decode) — such variants are reported, not failed
      int s = 3, q = 4, size = (n + 2 * q) * s; byte[] y = new byte[size * size]; Arrays.fill(y, (byte) 255);
      for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) if (bm.get(c, r) == 1)
        for (int dy = 0; dy < s; dy++) for (int dx = 0; dx < s; dx++) y[((r + q) * s + dy) * size + (c + q) * s + dx] = 0;
      if (!new com.zmlworks.qrtransfer.FrameDecoder().decode(y, size, size).contains(text)) {
        Map<DecodeHintType, Object> pure = new EnumMap<>(DecodeHintType.class); pure.put(DecodeHintType.PURE_BARCODE, true);
        Result res = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y, size, size, 0, 0, size, size, false))), pure);
        if (!res.getText().equals(text)) throw new AssertionError("decoded text differs");
        undetected.add(ecl + " v" + ((n - 17) / 4) + " mask " + m + (m == best ? " (best)" : ""));
      }
      decoded++;
    }
    if (zx.getMaskPattern() != best) throw new AssertionError("best mask: ZXing " + zx.getMaskPattern() + " JS " + best + " (" + ecl + " " + text.length() + ")");
    cases++;
  }
  System.out.println("ok - ZXing parity: " + cases + " symbols × 8 masks: penalties equal, ZXing picks the same best mask and identical modules, " + decoded + " variants decode to the identical text" +
      (undetected.isEmpty() ? "" : "; valid but NOT found by the app's detector on a perfect image: " + undetected));
}}
