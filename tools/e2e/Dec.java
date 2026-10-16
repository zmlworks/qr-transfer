import com.google.zxing.*; import com.google.zxing.common.HybridBinarizer; import com.google.zxing.qrcode.QRCodeReader;
import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.util.*;
public class Dec { public static void main(String[] a) throws Exception {
  BufferedImage im = ImageIO.read(new File(a[0])); int w = im.getWidth(), h = im.getHeight(); int[] px = im.getRGB(0,0,w,h,null,0,w);
  Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class); hints.put(DecodeHintType.TRY_HARDER, true);
  String t = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,px))), hints).getText();
  System.out.println(t.substring(0, Math.min(80, t.length())) + "...");
  com.zmlworks.qrtransfer.Protocol.Assembler asm = new com.zmlworks.qrtransfer.Protocol.Assembler();
  System.out.println("accept=" + asm.accept(t) + " total=" + asm.total() + " crc=" + asm.crc());
}}
