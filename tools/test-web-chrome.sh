#!/bin/sh
# End-to-end: headless Chrome renders web/sender.html (auto-started with sample text), takes screenshots,
# then the app's FrameDecoder (JVM, ZXing) decodes the QR codes from the screenshot pixels and the app's Protocol
# class parses them. Variants: two codes side by side (landscape), two stacked (portrait), one code, and the
# microphone paths via tools/test-web-chrome.py (Playwright).
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
# microphone paths (fake mic must never advance without acks, mic refused → LOOP mode, full acoustic loop with a
# simulated receiver): Playwright, see tools/test-web-chrome.py
PY=${PLAYWRIGHT_PYTHON:-python3}
"$PY" tools/test-web-chrome.py "${SHOT:-$T/qr-sender-0.3.1-screenshot.png}"
echo "screenshots: $T"
