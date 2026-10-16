#!/bin/sh
# End-to-end: headless Chrome renders web/sender.html (auto-started with sample text), takes screenshots,
# then the app's FrameDecoder (JVM, ZXing) decodes the QR codes from the screenshot pixels and the app's Protocol
# class parses them. Variants: two codes side by side (landscape), two stacked (portrait), one code, and the
# microphone path with Chrome's fake audio device (must start listening without errors).
# Needs: google-chrome/chromium, JDK, and a prior ./gradlew compileDebugJavaWithJavac.
set -e
cd "$(dirname "$0")/.."
CHROME=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
T=$(mktemp -d)
JAR=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}" -name 'core-3.5.3.jar' | head -1)
CLS=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -d "$T" -cp "$JAR:$CLS" tools/e2e/Dec.java
page() {  # $1 name, $2 two(true/false), $3 ack(true/false)
  python3 - "$T/$1.html" "$2" "$3" <<'PY'
import sys
s = open('web/sender.html', encoding='utf-8').read()
inj = '''<script>
var t = "電波を使わないデータ受け渡し — Chrome headless test ✓\\n";
for (var i = 0; i < 300; i++) t += Math.random().toString(36).slice(2);   // incompressible → several frames
document.getElementById("text").value = t;
document.getElementById("two").checked = %s;
document.getElementById("ack").checked = %s;
document.getElementById("start").click();
setInterval(function () { console.log("STATUS " + document.getElementById("status").textContent + " | " + document.getElementById("info").textContent); }, 1000);
</script></body></html>''' % (sys.argv[2], sys.argv[3])
open(sys.argv[1], 'w', encoding='utf-8').write(s.replace('</body></html>', inj))
PY
}
shot() {  # $1 name, $2 window size, $3 expected codes, extra chrome flags...
  n=$1; size=$2; want=$3; shift 3
  "$CHROME" --headless=new --disable-gpu --no-sandbox --hide-scrollbars --window-size="$size" "$@" \
    --virtual-time-budget=1500 --screenshot="$T/$n.png" "file://$T/$n.html" 2>/dev/null
  echo "== $n ($size)"; java -cp "$JAR:$CLS:$T" Dec "$T/$n.png" "$want"
}
page two-land true false;  shot two-land 1400,900 2
page two-port true false;  shot two-port 800,1400 2
page one true false;       sed -i 's/i < 300/i < 0/' "$T/one.html"; shot one 1400,900 1
page single false false;   shot single 1400,900 1
page mic true true
# real time (getUserMedia does not run under virtual time); Chrome's fake mic beeps must not be taken as acks,
# and without acks the codes must still advance (timeout ≈ 1.7 s)
timeout 8 "$CHROME" --headless=new --disable-gpu --no-sandbox --use-fake-ui-for-media-stream --use-fake-device-for-media-stream \
  --enable-logging=stderr --remote-debugging-port=0 "file://$T/mic.html" 2>&1 | grep -a -o 'STATUS [^"]*' > "$T/mic.log" || true
echo "== mic"; cat "$T/mic.log"
grep -q '🎤' "$T/mic.log" || { echo "FAIL: mic not listening"; exit 1; }
grep -q '✓0 ' "$T/mic.log" && ! grep -q '✓[1-9]' "$T/mic.log" || { echo "FAIL: false acks"; exit 1; }
grep -q 'A 3 ' "$T/mic.log" || { echo "FAIL: did not advance on timeout"; exit 1; }
# full acoustic loop in the browser: getUserMedia replaced by a synthetic stream that "beeps" A and B every 400 ms
# (like a receiver reading both codes), then DONE after 4 s → the sender must advance on acks and stop on DONE
page micsim true true
python3 - "$T/micsim.html" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
fake = """<script>
navigator.mediaDevices.getUserMedia = async function () {
  var ctx = new AudioContext(), dst = ctx.createMediaStreamDestination(), t0 = ctx.currentTime + 0.3;
  function beep(hz, at, dur) { var o = ctx.createOscillator(), g = ctx.createGain(); o.frequency.value = hz; g.gain.value = 0.3;
    o.connect(g); g.connect(dst); o.start(at); o.stop(at + dur); }
  for (var k = 0; k < 9; k++) { beep(QT1.A_HZ, t0 + k * 0.4, 0.1); beep(QT1.B_HZ, t0 + k * 0.4 + 0.14, 0.1); }
  beep(QT1.DONE_HZ, t0 + 4.0, 0.45);
  return dst.stream;
};
</script>"""
s = s.replace('<script>\nvar t = ', fake + '<script>\nvar t = ', 1)
open(p, 'w', encoding='utf-8').write(s)
PY
timeout 9 "$CHROME" --headless=new --disable-gpu --no-sandbox --autoplay-policy=no-user-gesture-required \
  --enable-logging=stderr --remote-debugging-port=0 "file://$T/micsim.html" 2>&1 | grep -a -o 'STATUS [^"]*' > "$T/micsim.log" || true
echo "== micsim"; cat "$T/micsim.log"
grep -q '✓[1-9]' "$T/micsim.log" || { echo "FAIL: no acks heard"; exit 1; }
grep -q 'receiver finished' "$T/micsim.log" || { echo "FAIL: DONE not heard"; exit 1; }
echo "screenshots: $T"
