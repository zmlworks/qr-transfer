# Third-party notices / サードパーティ ライセンス

## ZXing core 3.5.3 — Apache License 2.0
- https://github.com/zxing/zxing — Copyright (C) ZXing authors
- Used for QR encoding (sender) and, since app 0.4.0, as the optional fallback decoder ("ZXing (old)"). Bundled in the APK (shrunk by R8, unmodified source).
- Maven: `com.google.zxing:core:3.5.3` (pure Java; this is *not* Google Play Services).
- Full license text: [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)

## zxing-cpp 3.1.1 — Apache License 2.0 (receiver decoder, app 0.4.0+)
- https://github.com/zxing-cpp/zxing-cpp — Copyright (C) zxing-cpp authors (C++ port of ZXing)
- Used for QR decoding in the receiver (default engine since app 0.4.0). Bundled in the APK as the unmodified Maven AAR
  `io.github.zxing-cpp:android:3.1.1` (Kotlin wrapper `zxingcpp.BarcodeReader` + native library
  `libzxingcpp_android.so`; the release APK ships arm64-v8a and armeabi-v7a). No zxing-cpp source is included in this
  repository.
- Full license text: [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)
- The native library contains **libzueci** 1.0.1 (copied into zxing-cpp's `core/src/libzueci`), BSD-3-Clause:

```
libzueci - an open source UTF-8 ECI library adapted from libzint
Copyright (C) 2022 gitlost

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
following conditions are met:
1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
   disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
   products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

  libzueci includes the UTF-8 decoder by Bjoern Hoehrmann:

```
Copyright (c) 2008-2009 Bjoern Hoehrmann <bjoern@hoehrmann.de>

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Kotlin Standard Library 2.3.20 — Apache License 2.0 (app 0.4.0+)
- https://github.com/JetBrains/kotlin — Copyright JetBrains s.r.o. and Kotlin Programming Language contributors
- Maven `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` (+ `org.jetbrains:annotations:13.0`, Apache-2.0), required by the
  zxing-cpp AAR's Kotlin wrapper; bundled in the APK (shrunk by R8). Full license text: [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)

## QR Code Generator for JavaScript (qrcode-generator) 1.4.4 — MIT License
- https://github.com/kazuhikoarase/qrcode-generator — Copyright (c) 2009 Kazuhiko Arase
- Inlined unmodified in `web/sender.html` (also bundled as an APK asset so the phone can share it). Since sender v0.4.0
  the page re-masks the finished symbol in its own code (`QRSym`); the library itself is still unmodified.

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

## Gradle Wrapper — Apache License 2.0
- `gradle/wrapper/gradle-wrapper.jar` (Gradle, https://gradle.org) — build tool, not part of the APK.

## Test-only (not distributed in the APK)
- JUnit 4.13.2 — Eclipse Public License 1.0
- Android Gradle Plugin / Android SDK — Apache License 2.0 (build tools)
