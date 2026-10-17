import com.zmlworks.qrtransfer.DecodePipeline; import com.zmlworks.qrtransfer.FrameDecoder; import com.zmlworks.qrtransfer.QrEngine;
import com.zmlworks.qrtransfer.Upscale2x;
import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.nio.file.*; import java.util.*;

/**
 * Research benchmark frames (a directory with list.tsv = file \t gt1 \x1f gt2 …) through the
 * app's decode path: DecodePipeline + FrameDecoder (Java ZXing, the 0.3.1 decoder) and DecodePipeline + zxing-cpp
 * (CppBridge = same 3.1.1 C++ core via Python, the app's options), with and without the 2× bicubic retry.
 * Usage: java EngineBench <list.tsv> [base dir] → one line per engine × scenario: codes read / ground truth, ms.
 * Simulated frames on a desktop CPU — no phone numbers.
 */
public class EngineBench {
  public static void main(String[] a) throws Exception {
    String base = a.length > 1 ? a[1] : new File(a[0]).getParent();
    List<String[]> items = new ArrayList<>();
    for (String l : Files.readAllLines(Paths.get(a[0]))) items.add(l.split("\t", -1));
    List<byte[]> ys = new ArrayList<>(); List<int[]> dims = new ArrayList<>();
    for (String[] it : items) {
      BufferedImage im = ImageIO.read(new File(base, it[0])); int w = im.getWidth(), h = im.getHeight(); byte[] y = new byte[w * h];
      if (im.getType() == BufferedImage.TYPE_BYTE_GRAY) im.getRaster().getDataElements(0, 0, w, h, y);
      else { int[] px = im.getRGB(0, 0, w, h, null, 0, w); for (int i = 0; i < w * h; i++) { int p = px[i]; y[i] = (byte) (((p >> 16 & 255) * 77 + (p >> 8 & 255) * 150 + (p & 255) * 29) >> 8); } }
      ys.add(y); dims.add(new int[]{w, h});
    }
    CppBridge cpp = new CppBridge();
    Map<String, DecodePipeline> E = new LinkedHashMap<>();
    E.put("java-FrameDecoder", new DecodePipeline(new FrameDecoder(), false));
    E.put("zxing-cpp", new DecodePipeline(cpp, false));
    E.put("zxing-cpp+upscale", new DecodePipeline(cpp, true));
    // 2x upscale cost alone (Java, desktop JIT)
    { int[] d = dims.get(0); byte[] out = null; int[] tmp = new int[2 * 1920 * 1080]; byte[] big = new byte[1920 * 1080];
      for (int k = 0; k < 10; k++) out = Upscale2x.run(big, 1920, 1080, out, tmp);
      long[] ts = new long[21]; for (int k = 0; k < ts.length; k++) { long t0 = System.nanoTime(); out = Upscale2x.run(big, 1920, 1080, out, tmp); ts[k] = System.nanoTime() - t0; }
      Arrays.sort(ts); System.out.printf(Locale.ROOT, "Upscale2x 1920x1080 -> 3840x2160: median %.1f ms (desktop JVM, JIT warm)%n", ts[ts.length / 2] / 1e6); }
    for (Map.Entry<String, DecodePipeline> e : E.entrySet()) {
      DecodePipeline p = e.getValue();
      for (int i = 0; i < Math.min(8, ys.size()); i++) p.decode(ys.get(i), dims.get(i)[0], dims.get(i)[1], 0);   // warm-up
      Map<String, int[]> sc = new TreeMap<>(); Map<String, List<Double>> ms = new TreeMap<>();
      long now = 1_000_000;
      for (int i = 0; i < items.size(); i++) {
        String[] it = items.get(i); String s = new File(it[0]).getName().replaceAll("-.*", "").toUpperCase(Locale.ROOT);
        Set<String> gt = new HashSet<>(Arrays.asList(it[1].isEmpty() ? new String[0] : it[1].split("\u001f")));
        double c0 = cpp.sumNativeMs; int u0 = p.upscaleTries(); long t0 = System.nanoTime();
        List<String> got = p.decode(ys.get(i), dims.get(i)[0], dims.get(i)[1], now += 1000);   // 1 s apart: the rate limit never blocks a retry here
        double wall = (System.nanoTime() - t0) / 1e6, nat = cpp.sumNativeMs - c0;
        // for zxing-cpp count only the in-library time (+ Java upscale), not the Python pipe
        double t = e.getKey().startsWith("zxing-cpp") ? nat + (p.upscaleTries() > u0 ? p.lastUpscaleOnlyMs() : 0) : wall;
        int ok = 0, bad = 0; for (String g : new HashSet<>(got)) if (gt.contains(g)) ok++; else bad++;
        int[] v = sc.computeIfAbsent(s, k -> new int[3]); v[0] += ok; v[1] += gt.size(); v[2] += bad;
        ms.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
      }
      StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%-20s", e.getKey())); int ok = 0, gt = 0, bad = 0;
      for (String s : sc.keySet()) { int[] v = sc.get(s); ok += v[0]; gt += v[1]; bad += v[2]; b.append(String.format(Locale.ROOT, " %s %d/%d (%.0f ms)", s, v[0], v[1], median(ms.get(s)))); }
      b.append(String.format(Locale.ROOT, "  | total %d/%d, wrong %d, upscale tries %d hits %d", ok, gt, bad, p.upscaleTries(), p.upscaleHits()));
      System.out.println(b);
    }
    System.exit(0);
  }
  static double median(List<Double> v) { List<Double> c = new ArrayList<>(v); Collections.sort(c); return c.get(c.size() / 2); }
}
