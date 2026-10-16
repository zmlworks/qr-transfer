#!/bin/sh
# End-to-end: headless Chrome renders web/sender.html (auto-started with sample text), takes a screenshot,
# then ZXing (JVM) decodes the QR from the screenshot pixels and the app's Protocol class parses the frame.
# Needs: google-chrome/chromium, JDK, and a prior ./gradlew compileDebugJavaWithJavac.
set -e
cd "$(dirname "$0")/.."
CHROME=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
T=$(mktemp -d)
python3 - "$T" <<'PY'
import sys
s = open('web/sender.html', encoding='utf-8').read()
inj = '''<script>
document.getElementById("text").value = "電波を使わないデータ受け渡し — Chrome headless test ✓\\n".repeat(30);
document.getElementById("start").click();
</script></body></html>'''
open(sys.argv[1] + '/t.html', 'w', encoding='utf-8').write(s.replace('</body></html>', inj))
PY
"$CHROME" --headless=new --disable-gpu --no-sandbox --hide-scrollbars --window-size=1280,900 \
  --virtual-time-budget=1500 --screenshot="$T/shot.png" "file://$T/t.html" 2>/dev/null
JAR=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}" -name 'core-3.5.3.jar' | head -1)
CLS=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -d "$T" -cp "$JAR:$CLS" tools/e2e/Dec.java
java -cp "$JAR:$CLS:$T" Dec "$T/shot.png"
echo "screenshot: $T/shot.png"
