import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.util.*;
/**
 * Pixel checks on browser screenshots (device pixels).
 *   java PixelCheck match <shot.png> <ref.png> <x> <y> [<ref.png> <x> <y> ...]
 *       the region of the screenshot at (x, y) must equal the canvas bitmap ref.png pixel for pixel
 *       (proves the code reached the screen 1:1 — no resampling, every module whole device pixels)
 *   java PixelCheck stats <shot.png> <x> <y> <w> <h>
 *       share of grey (anti-aliased) pixels and the histogram of black/white run lengths along all rows
 */
public class PixelCheck { public static void main(String[] a) throws Exception {
  BufferedImage shot = ImageIO.read(new File(a[1]));
  if (a[0].equals("match")) {
    int bad = 0, total = 0;
    for (int k = 2; k + 2 < a.length; k += 3) {
      BufferedImage ref = ImageIO.read(new File(a[k])); int x0 = Integer.parseInt(a[k + 1]), y0 = Integer.parseInt(a[k + 2]);
      int best = Integer.MAX_VALUE, bx = 0, by = 0;
      for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {   // the CSS position is known to ±1 device px
        int diff = 0;
        for (int y = 0; y < ref.getHeight(); y++) for (int x = 0; x < ref.getWidth(); x++) {
          int X = x0 + ox + x, Y = y0 + oy + y;
          int s = X >= 0 && Y >= 0 && X < shot.getWidth() && Y < shot.getHeight() ? shot.getRGB(X, Y) & 0xFF : -1;
          if (Math.abs(s - (ref.getRGB(x, y) & 0xFF)) > 2) diff++;
        }
        if (diff < best) { best = diff; bx = ox; by = oy; }
      }
      total++; if (best > 0) { bad++; System.out.println("MISMATCH " + a[k] + " at " + x0 + "," + y0 + ": " + best + " px (best offset " + bx + "," + by + ")"); }
    }
    System.out.println("match: " + (total - bad) + "/" + total + " codes pixel-identical on screen");
    if (bad > 0) System.exit(1);
  } else {
    int x0 = Integer.parseInt(a[2]), y0 = Integer.parseInt(a[3]), w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]), grey = 0;
    TreeMap<Integer, Integer> runs = new TreeMap<>();
    for (int y = y0; y < y0 + h; y++) {
      int prev = -1, run = 0;
      for (int x = x0; x < x0 + w; x++) {
        int v = shot.getRGB(x, y) & 0xFF; if (v > 8 && v < 247) grey++;
        int b = v < 128 ? 1 : 0;
        if (b == prev) run++; else { if (prev >= 0) runs.merge(run, 1, Integer::sum); prev = b; run = 1; }
      }
    }
    System.out.printf("grey=%.2f%% runs=%s%n", 100.0 * grey / (w * h), runs.headMap(13));
  }
}}
