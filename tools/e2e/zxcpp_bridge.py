#!/usr/bin/env python3
"""zxing-cpp (Python binding of the same C++ core as the app's io.github.zxing-cpp:android 3.1.1 AAR) as a decode
server for the JVM simulations (tools/e2e/CppBridge.java). The Android .so cannot run on a desktop JVM, so this is the
closest stand-in: same library version, the app's CppEngine options (QR only, no rotate/invert/downscale retries,
tryHarder on, up to 255 symbols, LocalAverage binarizer).
Needs: pip install zxing-cpp==3.1.1 numpy (run with env ZXCPP_PY=<that python> if it is not the default python3).
stdin: repeated "<w> <h>\\n" + w*h luminance bytes; stdout per request: "<decode ms>\\t<JSON list of texts>"."""
import json, sys, time
import numpy as np, zxingcpp

inp, out = sys.stdin.buffer, sys.stdout
while True:
    line = inp.readline()
    if not line: break
    w, h = map(int, line.split())
    buf = inp.read(w * h)
    img = np.frombuffer(buf, dtype=np.uint8).reshape(h, w)
    t0 = time.perf_counter()
    rs = zxingcpp.read_barcodes(img, formats=zxingcpp.BarcodeFormat.QRCode, try_rotate=False, try_downscale=False,
                                try_invert=False, binarizer=zxingcpp.Binarizer.LocalAverage)
    texts = []
    for r in rs:
        if r.valid and r.text and r.text not in texts: texts.append(r.text)
    out.write("%.2f\t%s\n" % ((time.perf_counter() - t0) * 1000, json.dumps(texts))); out.flush()
