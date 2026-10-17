import com.zmlworks.qrtransfer.QrEngine;
import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;

/** QrEngine backed by zxing-cpp through tools/e2e/zxcpp_bridge.py (env ZXCPP_PY = python with zxingcpp 3.1.1). */
public class CppBridge implements QrEngine {
  private final Process p; private final OutputStream o; private final BufferedReader r;
  /** time of the last decode inside the C++ library (ms, measured in the Python process: no IPC) */
  public double lastNativeMs, sumNativeMs; public int calls;
  public CppBridge() throws IOException {
    String py = System.getenv().getOrDefault("ZXCPP_PY", "python3");
    String path = System.getenv().getOrDefault("ZXCPP_BRIDGE", "tools/e2e/zxcpp_bridge.py");
    p = new ProcessBuilder(py, path).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    o = new BufferedOutputStream(p.getOutputStream(), 1 << 20);
    r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
  }
  @Override public String name() { return "zxing-cpp (py bridge)"; }
  @Override public List<String> decode(byte[] y, int w, int h) {
    try {
      o.write((w + " " + h + "\n").getBytes(StandardCharsets.US_ASCII)); o.write(y, 0, w * h); o.flush();
      String line = r.readLine(); List<String> out = new ArrayList<>();
      int tab = line.indexOf('\t'); lastNativeMs = Double.parseDouble(line.substring(0, tab)); sumNativeMs += lastNativeMs; calls++; line = line.substring(tab + 1);
      // minimal JSON list-of-strings parser (frame texts are QR alphanumeric: no escapes needed except \" and \\)
      int i = line.indexOf('[') + 1;
      while (i < line.length()) {
        int q = line.indexOf('"', i); if (q < 0) break;
        StringBuilder b = new StringBuilder(); int k = q + 1;
        for (; k < line.length(); k++) { char c = line.charAt(k); if (c == '\\') { b.append(line.charAt(++k)); continue; } if (c == '"') break; b.append(c); }
        out.add(b.toString()); i = k + 1;
      }
      return out;
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }
}
