import com.zmlworks.qrtransfer.FrameDecoder;
import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.util.*;
/** Prints every QR text the app's FrameDecoder finds in each image (one "file<TAB>text" line per code). */
public class DecText { public static void main(String[] a) throws Exception {
  for (String f : a) {
    BufferedImage im = ImageIO.read(new File(f)); int w = im.getWidth(), h = im.getHeight();
    byte[] y = new byte[w * h]; int[] px = im.getRGB(0, 0, w, h, null, 0, w);
    for (int i = 0; i < w * h; i++) { int p = px[i]; y[i] = (byte) (((p >> 16 & 255) * 77 + (p >> 8 & 255) * 150 + (p & 255) * 29) >> 8); }
    Set<String> got = new LinkedHashSet<>(new FrameDecoder().decode(y, w, h));
    for (String t : got) System.out.println(f + "\t" + t);
  }
}}
