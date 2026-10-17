#!/usr/bin/env python3
"""Headless Chrome (Playwright, system Chrome) checks of web/sender.html (0.3.1 tests, updated for the 0.5.0 shared queue).

0. defaults: MIC-ACK ("advance on tones") selected, auto-advance (loop) not selected, loop speed disabled.
1. fake mic (Chrome's fake audio device beeps): MIC-ACK mode, and the codes must NEVER advance without a matching
   ack; the applied getUserMedia settings (echoCancellation/noiseSuppression/autoGainControl = false) are shown.
2. mic refused (getUserMedia rejects NotAllowedError): still MIC-ACK mode with a warning, codes do NOT advance
   (no silent fallback to auto-advance); only the "switch to loop" button starts the loop.
3. mic test: before sending, the simulated receiver's "test tones" (A0 A1 B0 B1 DONE) all light up in the
   diagnostics checklist and the event log; per-tone level bars are drawn.
4. acoustic loop: getUserMedia replaced by a synthetic stream fed by an in-page simulated receiver (camera reads the
   shown chunks with random loss, alternating-bit acks played as real oscillator tones — 120 ms, quiet (≈ −50 dBFS)
   under louder 120 Hz rumble + DC, the case 0.3.0 could not hear — with random ack loss, re-acks, DONE). Must
   finish, never move past a chunk the receiver lacks, never show two chunks with the same ack tone, and the received
   frames must reassemble (base45 → envelope → inflate → CRC-32) to the original text. The simulated receiver uses the
   0.3.1 ack policy (de-duplication per slot = index parity): the 0.5.0 sender with the old receiver.
5. (0.5.0) same with the 0.4.0 ack policy (per tone), auto re-mask every 1 s, and a camera that cannot read chunk 3
   in its first rendering and chunk 5 in any of its first 9 renderings: chunk 3 must be read after a re-mask, chunk 5
   must be put back into the queue after 8 re-masks (requeue) and be read when it comes back. Screenshot here.
Usage: python3 tools/test-web-chrome.py [screenshot.png]      (pip install playwright; uses /usr/bin/google-chrome)
"""
import base64, json, os, pathlib, sys, time, zlib
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
URL = (ROOT / "web" / "sender.html").as_uri()
SHOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qr-sender-0.5.0-screenshot.png"
CHROME = os.environ.get("CHROME", "/usr/bin/google-chrome")
B45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

def b45dec(s):
    out = bytearray()
    for i in range(0, len(s), 3):
        c = [B45.index(ch) for ch in s[i:i + 3]]
        if len(c) == 3:
            n = c[0] + c[1] * 45 + c[2] * 2025; out += bytes([n >> 8, n & 255])
        else:
            out.append(c[0] + c[1] * 45)
    return bytes(out)

def assemble(frames):
    parts = {}
    for f in frames:
        _, fid, idx, total, crc, data = f.split(":", 5)
        parts[int(idx)] = b45dec(data)
    total = int(frames[0].split(":")[3]); crc = frames[0].split(":")[4]
    assert sorted(parts) == list(range(total)), "missing chunks"
    env = b"".join(parts[i] for i in range(total))
    flags, nl = env[1], (env[2] << 8) | env[3]
    payload = env[4 + nl:]
    if flags & 2: payload = zlib.decompress(payload, -15)
    assert "%08X" % (zlib.crc32(payload) & 0xffffffff) == crc, "CRC mismatch"
    return payload

TEXT_JS = """() => { document.getElementById("vtwo").checked = true;   // v0.4.0: grid is the default; these tests cover 「2枚・確認音」
  var t = "電波を使わないデータ受け渡し — v0.3.1 headless test ✓\\n";
  for (var i = 0; i < 600; i++) t += Math.random().toString(36).slice(2);
  document.getElementById("text").value = t; document.getElementById("fps").value = "6"; document.getElementById("chunk").value = "200"; return t; }"""

# simulated receiver: camera (30 fps, 70 % read, ~100 ms decode), AckPolicy (dedupe, re-ack 700 ms), FIFO tone player
# (120 ms tones, 60 ms gap, 25 % of acks lost), DONE when complete (repeated every 2 s while codes are seen).
# Acoustic path: tones at amplitude 0.004 (≈ −51 dBFS rms) plus a louder 120 Hz rumble (0.01) and DC (0.002):
# 0.3.0's detector needed 30 % of the full-band energy at the tone and could not hear this.
FAKE_MIC = """
(() => {
  window.__rx = { have: {}, haveAt: {}, frames: {}, acks: 0, lost: 0, done: false, violations: [], tests: 0, blocked: 0,
                  policy: window.__rxPolicy || "slot", hard: window.__rxHard || {} };
  navigator.mediaDevices.getUserMedia = async function () {
    var ctx = new AudioContext(), dst = ctx.createMediaStreamDestination(), freeAt = 0, rx = window.__rx;
    var last = [-1, -1, -1, -1], at = [-1e9, -1e9, -1e9, -1e9], doneAt = -1e9, queue = [], AMP = 0.004;
    var hum = ctx.createOscillator(), hg = ctx.createGain(); hum.frequency.value = 120; hg.gain.value = 0.01;
    hum.connect(hg); hg.connect(dst); hum.start();
    var dc = ctx.createConstantSource(); dc.offset.value = 0.002; dc.connect(dst); dc.start();
    function beep(tone, t0) {
      var dur = tone === 4 ? 0.4 : 0.12, o = ctx.createOscillator(), g = ctx.createGain();
      o.frequency.value = QT1.HZ[tone];
      g.gain.setValueAtTime(0, t0); g.gain.linearRampToValueAtTime(AMP, t0 + 0.01);
      g.gain.setValueAtTime(AMP, t0 + dur - 0.01); g.gain.linearRampToValueAtTime(0, t0 + dur);
      o.connect(g); g.connect(dst); o.start(t0); o.stop(t0 + dur + 0.01);
      return t0 + dur;
    }
    function play(tone) {
      if (queue.indexOf(tone) >= 0) return;
      queue.push(tone);
    }
    // the receiver app's "test tones" button: A0, A1, B0, B1, DONE with 350 ms pauses
    window.__rxTest = function () {
      var t = Math.max(ctx.currentTime + 0.05, freeAt);
      for (var k = 0; k < 5; k++) t = beep(k, t) + 0.35;
      freeAt = t; rx.tests++;
    };
    setInterval(function () {               // player thread
      if (!queue.length || ctx.currentTime < freeAt - 0.02) return;
      var tone = queue.shift(), dur = tone === 4 ? 0.4 : 0.12, t0 = Math.max(ctx.currentTime + 0.05, freeAt);
      freeAt = t0 + dur + 0.06;
      if (tone !== 4 && Math.random() < 0.25) { rx.lost++; return; }
      beep(tone, t0);
    }, 5);
    setInterval(function () {               // camera
      var st = window.__qtSender.state();
      if (!st.running) return;
      if (st.slots === 2 && st.shown[0] >= 0 && st.shown[1] >= 0 && st.shown[0] !== st.shown[1] &&
          QT1.toneOf(st.shown[0]) === QT1.toneOf(st.shown[1])) rx.violations.push("same tone on screen: " + st.shown);
      for (var s = 0; s < st.slots; s++) {
        var i = st.shown[s];
        if (i < 0 || Math.random() > 0.7) continue;
        // hard renderings: chunk i is unreadable while its variant counter k < rx.hard[i]
        if (rx.hard[i] !== undefined && st.masks[s] && st.masks[s].k < rx.hard[i]) { rx.blocked++; continue; }
        (function (i, frame) { setTimeout(function () {
          var now = performance.now();
          if (rx.done) { if (now - doneAt >= 2000) { doneAt = now; play(4); } return; }
          if (!rx.have[i]) rx.haveAt[i] = now;
          rx.have[i] = true; rx.frames[i] = frame;
          if (Object.keys(rx.have).length === st.total) { rx.done = true; doneAt = now; play(4); return; }
          var sl = rx.policy === "tone" ? QT1.toneOf(i) : i & 1;   // 0.4.0: per tone; 0.3.1: per slot (parity)
          if (i !== last[sl] || now - at[sl] >= 700) { last[sl] = i; at[sl] = now; rx.acks++; play(QT1.toneOf(i)); }
        }, 70 + Math.random() * 60); })(i, st.frames[i]);
      }
    }, 33);
    var stream = dst.stream, tr = stream.getAudioTracks()[0];
    tr.getSettings = function () { return { echoCancellation: false, noiseSuppression: false, autoGainControl: false, sampleRate: ctx.sampleRate }; };
    return stream;
  };
})();
"""

def page_with(browser, init=None, perms=None):
    ctx = browser.new_context(viewport={"width": 1400, "height": 900}, permissions=perms or [])
    if init: ctx.add_init_script(init)
    p = ctx.new_page()
    p.on("pageerror", lambda e: (_ for _ in ()).throw(AssertionError("page error: %s" % e)))
    p.goto(URL)
    return ctx, p

def state(p): return p.evaluate("() => window.__qtSender.state()")

def changes(lg):
    """display events that changed a slot's chunk (0.5.0 also logs re-mask redraws of the same chunk)"""
    out, last = [], {}
    for e in lg:
        if "show" in e and last.get(e["slot"]) != e["show"]:
            out.append(e); last[e["slot"]] = e["show"]
    return out

def acoustic(p, text, label):
    """run a transfer to the end against the in-page receiver; check no false advance, distinct tones, content"""
    p.wait_for_function("() => window.__qtSender.state().running")
    t0 = time.time()
    p.wait_for_function("() => /receiver finished/.test(document.getElementById('status').textContent)", timeout=240000)
    dt = time.time() - t0
    rx = p.evaluate("() => window.__rx")
    lg = log(p)
    total = len(rx["frames"])
    assert not rx["violations"], rx["violations"][:5]
    # a chunk leaves a slot only when the receiver already had it, or when it was put back into the queue (requeue)
    requeued = {(e["requeue"], e["t"]) for e in lg if "requeue" in e}
    for s in (0, 1):
        prev = None
        for e in [e for e in lg if e.get("slot") == s and "show" in e]:
            if prev is not None and prev["show"] >= 0 and e["show"] != prev["show"]:
                c = prev["show"]
                ok = str(c) in rx["haveAt"] and rx["haveAt"][str(c)] <= e["t"] + 1
                ok = ok or any(rc == c and abs(rt - e["t"]) <= 50 for rc, rt in requeued)
                assert ok, ("slot", s, "left chunk", c, "at", e["t"], "receiver lacks it")
            prev = e
    shown = sorted({e["show"] for e in lg if "show" in e and e["show"] >= 0})
    assert shown == list(range(total)), shown
    frames = [rx["frames"][str(i)] for i in range(total)]
    got = assemble(frames).decode("utf-8")
    assert got == text, "content mismatch"
    heard = [e for e in lg if "heard" in e]
    print("ok - acoustic loop, %s (tones ≈ −51 dBFS under 120 Hz rumble): %d chunks, %.1f s (%.0f B/s), acks played %d (lost %d), "
          "heard %d matching + %d stale; content + CRC verified, never left an unreceived chunk, tones always distinct"
          % (label, total, dt, len(text.encode()) / dt, rx["acks"], rx["lost"],
             sum(1 for e in heard if e["ok"]), sum(1 for e in heard if not e["ok"])))
    return lg
def log(p): return p.evaluate("() => window.__qtSender.log")

with sync_playwright() as pw:
    common = ["--autoplay-policy=no-user-gesture-required", "--no-sandbox"]
    # ---- 0 + 1. defaults; Chrome's fake microphone: ack mode, no advance at all, applied settings shown ----
    b = pw.chromium.launch(executable_path=CHROME, headless=True,
                           args=common + ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
    ctx, p = page_with(b, perms=["microphone"])
    d = p.evaluate("() => ({ grid: document.getElementById('vgrid').checked, ack: document.getElementById('ack').checked, loop: document.getElementById('loop').checked, fps: document.getElementById('fps').disabled })")
    assert d == {"grid": True, "ack": True, "loop": False, "fps": True}, d
    print("ok - defaults: 一覧表示 (grid) selected; in 2枚・確認音: MIC-ACK selected, auto-advance (loop) off, loop speed disabled")
    p.evaluate(TEXT_JS); p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    time.sleep(6)
    st = state(p)
    assert st["mode"] == "ack", st["mode"]
    assert st["shown"] == [0, 1], ("advanced without ack!", st["shown"], log(p))
    assert st["good"] == 0, st
    assert "MIC-ACK" in p.inner_text("#mode")
    assert "A0" in p.inner_text("#slots") and "B0" in p.inner_text("#slots")
    shows = changes(log(p))
    assert len(shows) == 2, shows
    remasks = [e for e in log(p) if "remask" in e]
    info = p.inner_text("#micinfo")
    assert "echoCancellation=false" in info and "noiseSuppression=false" in info and "autoGainControl=false" in info, info
    assert remasks and all(e["remask"] in (0, 1) for e in remasks), remasks   # default 3 s: re-masked, never skipped
    print("ok - fake mic: MIC-ACK mode, held chunks 1/2 for 6 s without acks (%d stale/foreign tones ignored, %d auto re-masks); settings: %s"
          % (st["stale"], len(remasks), info.split("|")[1].strip()))
    ctx.close(); b.close()

    b = pw.chromium.launch(executable_path=CHROME, headless=True, args=common)
    # ---- 2. microphone refused: stays MIC-ACK (warning), no auto-advance; loop only via the button ----
    ctx, p = page_with(b, init="navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('denied', 'NotAllowedError'); };")
    p.evaluate(TEXT_JS); p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    time.sleep(2.5)
    st = state(p)
    assert st["mode"] == "ack" and st["shown"] == [0, 1], st
    m = p.inner_text("#mode")
    assert "NotAllowedError" in m and "進みません" in m, m
    assert len(changes(log(p))) == 2, "advanced without a microphone!"
    p.click("#toloop"); time.sleep(2)
    st = state(p)
    assert st["mode"] == "loop", st
    assert len([e for e in log(p) if "show" in e]) > 8, "loop did not cycle"
    print("ok - mic refused: no auto-advance (warning shown), loop only after the button")
    ctx.close()

    # ---- 3. mic test before sending: the receiver's test tones light up all five checks ----
    ctx, p = page_with(b, init=FAKE_MIC)
    p.click("#mictest")
    p.wait_for_function("() => window.__qtSender.state().mic !== null")
    time.sleep(0.8)
    p.evaluate("() => window.__rxTest()")
    p.wait_for_function("() => document.querySelectorAll('.tchk.ok').length === 5", timeout=10000)
    ev = [e["tone"] for e in state(p)["events"]]
    assert ev == [0, 1, 2, 3, 4], ev
    assert not state(p)["running"]
    assert "DONE" in p.inner_text("#evlog") and "テスト" in p.inner_text("#evlog")
    print("ok - mic test (before sending): test tones A0 A1 B0 B1 DONE all heard, checklist 5/5, event log")
    p.click("#mictest")   # stop the test; the transfer opens the mic itself

    # ---- 4. acoustic loop with a simulated 0.3.1 receiver (quiet tones under rumble) ----
    text = p.evaluate(TEXT_JS); p.click("#start")
    acoustic(p, text, "0.3.1 receiver policy")
    ctx.close()

    # ---- 5. 0.4.0 receiver policy, auto re-mask, unreadable renderings → re-mask, requeue, retry at the end ----
    ctx, p = page_with(b, init="window.__rxPolicy = 'tone'; window.__rxHard = {3: 1, 5: 9};" + FAKE_MIC)
    text = p.evaluate(TEXT_JS.replace("i < 600", "i < 1500"))   # more chunks: others are still queued when #6 is put back
    p.evaluate("() => { document.getElementById('remasksec').value = '1'; }")
    p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    p.wait_for_function("() => window.__qtSender.state().remasks >= 1 && window.__qtSender.state().good >= 3", timeout=30000)
    p.screenshot(path=SHOT, full_page=True)
    lg = acoustic(p, text, "0.4.0 receiver policy, re-mask 1 s, hard chunks #4 #6")
    rq = [e for e in lg if "requeue" in e]
    rm = [e for e in lg if "remask" in e]
    assert any(e["remask"] == 3 for e in rm), "chunk 3 was never re-masked"
    assert any(e["requeue"] == 5 for e in rq), ("chunk 5 was never put back into the queue", rq)
    st = state(p)
    assert st["requeues"] >= 1, st["requeues"]
    assert "m" in p.inner_text("#lblA") or p.inner_text("#lblA") == "", p.inner_text("#lblA")
    print("ok - auto re-mask: %d re-masks, requeued %s, chunk #4 read after a re-mask, chunk #6 read after requeue + retry"
          % (len(rm), sorted(set(e["requeue"] for e in rq))))
    ctx.close(); b.close()
print("screenshot:", SHOT)
print("web sender chrome tests passed")
