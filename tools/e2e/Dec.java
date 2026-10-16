import com.zmlworks.qrtransfer.FrameDecoder; import com.zmlworks.qrtransfer.Protocol;
import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.util.*;
/** Decodes every QR code in a screenshot with the app's FrameDecoder and feeds them to the app's Assembler. */
public class Dec { public static void main(String[] a) throws Exception {
  BufferedImage im = ImageIO.read(new File(a[0])); int w = im.getWidth(), h = im.getHeight();
  byte[] y = new byte[w * h];
  for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) { int p = im.getRGB(i, j); y[j * w + i] = (byte) (((p >> 16 & 255) * 77 + (p >> 8 & 255) * 150 + (p & 255) * 29) >> 8); }
  FrameDecoder d = new FrameDecoder(); Set<String> got = new LinkedHashSet<>(d.decode(y, w, h)); got.addAll(d.decode(y, w, h));
  Protocol.Assembler asm = new Protocol.Assembler();
  for (String t : got) System.out.println(t.substring(0, Math.min(40, t.length())) + "...  accept=" + asm.accept(t));
  System.out.println("codes=" + got.size() + " total=" + asm.total() + " crc=" + asm.crc());
  if (a.length > 1 && got.size() != Integer.parseInt(a[1])) { System.out.println("FAIL: expected " + a[1] + " codes"); System.exit(1); }
}}
