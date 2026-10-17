import com.zmlworks.qrtransfer.FrameDecoder; import com.zmlworks.qrtransfer.Protocol;
import javax.imageio.ImageIO; import java.awt.*; import java.awt.image.BufferedImage; import java.io.File; import java.util.*;

/**
 * Simulated camera over grid-page screenshots, decoded by the app's own FrameDecoder (0.3.1, unchanged) and fed to the
 * app's Protocol.Assembler (any order, duplicates ignored). Usage:
 *   java GridSim <scale> <window-fraction> <page1.png> [page2.png ...]
 * For every page the "camera" takes 1920×1080 frames: windows of (fraction × screen) sliding over the screenshot in
 * steps of a quarter window, scaled to the camera size (bilinear) with mild blur + sensor noise. scale=0 → full-frame
 * only. Prints codes decoded per frame and finally whether the file was reassembled with a matching CRC-32.
 */
public class GridSim {
  static final Random RND = new Random(42);
  public static void main(String[] a) throws Exception {
    double frac = Double.parseDouble(a[1]); boolean full = Double.parseDouble(a[0]) == 0;
    Protocol.Assembler asm = new Protocol.Assembler(); FrameDecoder dec = new FrameDecoder();
    int frames = 0, decodedTotal = 0, maxPer = 0; Map<Integer,Integer> hist = new TreeMap<>();
    for (int k = 2; k < a.length; k++) {
      BufferedImage page = ImageIO.read(new File(a[k]));
      int W = page.getWidth(), H = page.getHeight(), pageCodes = 0; Set<String> seen = new HashSet<>();
      java.util.List<Rectangle> wins = new ArrayList<>();
      if (full) wins.add(new Rectangle(0, 0, W, H));
      else {
        int ww = (int) (W * frac), wh = (int) Math.min(H, ww * 9.0 / 16);
        for (int y = 0; y + wh <= H; y += Math.max(1, wh / 4)) for (int x = 0; x + ww <= W; x += Math.max(1, ww / 4)) wins.add(new Rectangle(x, y, ww, wh));
      }
      for (Rectangle r : wins) {
        BufferedImage cam = camera(page, r, 1920, 1080, !full);
        java.util.List<String> got = dec.decode(lum(cam), cam.getWidth(), cam.getHeight());
        frames++; decodedTotal += got.size(); maxPer = Math.max(maxPer, got.size()); hist.merge(got.size(), 1, Integer::sum);
        for (String t : got) { Protocol.Assembler.Result res = asm.accept(t); if (res == Protocol.Assembler.Result.RESTARTED) throw new IllegalStateException("RESTARTED"); seen.add(t); }
      }
      TreeSet<Integer> idx = new TreeSet<>(); for (String t : seen) idx.add(Protocol.parse(t).index + 1);
      System.out.println(a[k] + ": frames=" + wins.size() + " distinct codes read=" + seen.size() + "  progress=" + asm.received() + "/" + asm.total() + "  read=" + idx);
    }
    System.out.println("frames=" + frames + " codes/frame avg=" + String.format("%.2f", decodedTotal / (double) frames) + " max=" + maxPer + " histogram=" + hist);
    System.out.println("received=" + asm.received() + "/" + asm.total() + (asm.complete() ? "" : " missing=" + asm.missingRanges(20)));
    if (asm.complete()) {
      Protocol.Content c = asm.assemble();   // throws on CRC mismatch
      System.out.println("ASSEMBLED OK crc=" + asm.crc() + " bytes=" + c.data.length + " sha256=" + sha(c.data));
    } else System.exit(2);
  }
  static String sha(byte[] b) throws Exception { StringBuilder s = new StringBuilder(); for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x)); return s.toString(); }
  static BufferedImage camera(BufferedImage src, Rectangle r, int cw, int ch, boolean degrade) {
    double s = Math.min(cw / (double) r.width, ch / (double) r.height);
    int w = (int) (r.width * s), h = (int) (r.height * s);
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, degrade ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    g.drawImage(src, 0, 0, w, h, r.x, r.y, r.x + r.width, r.y + r.height, null); g.dispose();
    if (!degrade) return out;
    // 3×3 box blur (lens) + gaussian noise σ=6 + contrast loss (screen glare: black → 30, white → 221)
    int[] px = out.getRGB(0, 0, w, h, null, 0, w), o = new int[w * h];
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int sum = 0, n = 0;
      for (int dy = -1; dy <= 1; dy++) { int yy = y + dy; if (yy < 0 || yy >= h) continue;
        for (int dx = -1; dx <= 1; dx++) { int xx = x + dx; if (xx < 0 || xx >= w) continue; sum += px[yy * w + xx] & 255; n++; } }
      int v = (int) (30 + (sum / n) * 0.75 + RND.nextGaussian() * 6); v = Math.max(0, Math.min(255, v));
      o[y * w + x] = v * 0x010101;
    }
    BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    b.setRGB(0, 0, w, h, o, 0, w);
    return b;
  }
  static byte[] lum(BufferedImage im) {
    int w = im.getWidth(), h = im.getHeight(); byte[] y = new byte[w * h]; int[] px = im.getRGB(0, 0, w, h, null, 0, w);
    for (int i = 0; i < w * h; i++) { int p = px[i]; y[i] = (byte) (((p >> 16 & 255) * 77 + (p >> 8 & 255) * 150 + (p & 255) * 29) >> 8); }
    return y;
  }
}
