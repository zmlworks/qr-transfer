# Third-party notices / サードパーティ ライセンス

## ZXing core 3.5.3 — Apache License 2.0
- https://github.com/zxing/zxing — Copyright (C) ZXing authors
- Used for QR encoding (sender) and decoding (receiver). Bundled in the APK (shrunk by R8, unmodified source).
- Maven: `com.google.zxing:core:3.5.3` (pure Java; this is *not* Google Play Services).
- Full license text: [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)

## QR Code Generator for JavaScript (qrcode-generator) 1.4.4 — MIT License
- https://github.com/kazuhikoarase/qrcode-generator — Copyright (c) 2009 Kazuhiko Arase
- Inlined unmodified in `web/sender.html` (also bundled as an APK asset so the phone can share it).

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

"QR Code" is a registered trademark of DENSO WAVE INCORPORATED.

## Test-only (not distributed in the APK)
- JUnit 4.13.2 — Eclipse Public License 1.0
- Android Gradle Plugin / Android SDK — Apache License 2.0 (build tools)
