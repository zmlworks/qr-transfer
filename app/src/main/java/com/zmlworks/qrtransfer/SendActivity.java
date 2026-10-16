package com.zmlworks.qrtransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Sender: text or file -> QT1 frames -> animated QR codes.
 * Default: loop all frames forever. With "advance on tone", hold each frame until the receiver's
 * ACK beep (or a timeout), skip frames already acknowledged, and stop on the DONE tone.
 */
public final class SendActivity extends Activity implements AckListener.Callback {
    private static final int REQ_PICK = 1, REQ_MIC = 2;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int[] CHUNKS = {200, 400, 700, 1000};
    private static final int[] FPS = {2, 4, 6, 10};
    private static final ErrorCorrectionLevel[] ECC = {ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q};

    private View setup, show;
    private EditText text;
    private TextView fileInfo, info;
    private Spinner chunk, fps, ecc;
    private CheckBox ackBox;
    private ImageView qr;
    private Button pause;
    private byte[] fileData;
    private String fileName;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread ht;
    private Handler worker;
    // --- state below is touched only on the worker thread ---
    private List<String> frames;
    private boolean[] acked;
    private int cur, ackCount;
    private boolean ackMode, sending;
    private volatile boolean paused;
    private long shownAt, periodMs;
    private ErrorCorrectionLevel ecl;
    private AckListener listener;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_send);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        setup = findViewById(R.id.setup);
        show = findViewById(R.id.show);
        text = findViewById(R.id.text);
        fileInfo = findViewById(R.id.file_info);
        info = findViewById(R.id.info);
        chunk = findViewById(R.id.chunk);
        fps = findViewById(R.id.fps);
        ecc = findViewById(R.id.ecc);
        ackBox = findViewById(R.id.ack);
        qr = findViewById(R.id.qr);
        pause = findViewById(R.id.pause);
        spinner(chunk, R.array.chunks, 1);
        spinner(fps, R.array.fps, 1);
        spinner(ecc, R.array.ecc, 1);
        findViewById(R.id.pick).setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK));
        findViewById(R.id.clear_file).setOnClickListener(v -> setFile(null, null));
        findViewById(R.id.start).setOnClickListener(v -> begin());
        findViewById(R.id.stop).setOnClickListener(v -> stopSending());
        pause.setOnClickListener(v -> { paused = !paused; pause.setText(paused ? "▶" : "⏸"); worker.post(this::schedule); });
        findViewById(R.id.prev).setOnClickListener(v -> step(-1));
        findViewById(R.id.next).setOnClickListener(v -> step(1));
        ht = new HandlerThread("qr-sender");
        ht.start();
        worker = new Handler(ht.getLooper());
        handleShare(getIntent());
    }

    private void spinner(Spinner s, int arr, int sel) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getResources().getStringArray(arr));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sel);
    }

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) loadFile(u);
        else {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) text.setText(t);
        }
    }

    // ---------------- input ----------------
    private void setFile(String name, byte[] data) {
        fileName = name;
        fileData = data;
        fileInfo.setText(data == null ? getString(R.string.no_file) : getString(R.string.file_info, name, data.length));
        text.setEnabled(data == null);
    }

    private void loadFile(Uri u) {
        String name = "file";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("null stream");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) { toast(getString(R.string.too_large, MAX_BYTES / 1024)); return; }
            }
            setFile(name, bo.toByteArray());
        } catch (IOException | SecurityException e) { toast(e.toString()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) loadFile(data.getData());
    }

    // ---------------- start / stop ----------------
    private void begin() {
        if (ackBox.isChecked() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startSending(ackBox.isChecked());
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_MIC) return;
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) { toast(getString(R.string.mic_denied)); ackBox.setChecked(false); }
        startSending(ok);
    }

    private void startSending(boolean withAck) {
        final Protocol.Content c = fileData != null ? new Protocol.Content(true, fileName, fileData)
                : new Protocol.Content(false, "", text.getText().toString().getBytes(StandardCharsets.UTF_8));
        if (c.data.length == 0) { toast(getString(R.string.nothing)); return; }
        final int chunkBytes = CHUNKS[chunk.getSelectedItemPosition()];
        periodMs = 1000 / FPS[fps.getSelectedItemPosition()];
        ecl = ECC[ecc.getSelectedItemPosition()];
        setup.setVisibility(View.GONE);
        show.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;   // bright screen = easier scanning
        getWindow().setAttributes(lp);
        info.setText(R.string.preparing);
        paused = false;
        pause.setText("⏸");
        worker.post(() -> {
            frames = Protocol.frames(Protocol.newId(), c, chunkBytes, true);
            acked = new boolean[frames.size()];
            ackCount = 0;
            sending = true;
            ackMode = false;
            if (withAck) {
                listener = new AckListener(this);
                ackMode = listener.start();
                if (!ackMode) { listener = null; ui.post(() -> toast(getString(R.string.mic_failed))); }
            }
            showFrame(0);
            schedule();
        });
    }

    private void stopSending() {
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            sending = false;
            if (listener != null) { listener.stop(); listener = null; }
        });
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        show.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (show.getVisibility() == View.VISIBLE) stopSending(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopSending();
        ht.quitSafely();
        super.onDestroy();
    }

    // ---------------- frame loop (worker thread) ----------------
    private void showFrame(int i) {
        if (!sending) return;
        cur = i;
        final Bitmap bmp;
        try { bmp = QrRender.render(frames.get(i), ecl); } catch (WriterException e) {
            ui.post(() -> { toast(e.toString()); stopSending(); });
            return;
        }
        shownAt = SystemClock.uptimeMillis();
        final String line = String.format(Locale.ROOT, "%d / %d  ·  v%d  ·  %s%s", i + 1, frames.size(), QrRender.version(bmp),
                frames.get(i).substring(4, 12), ackMode ? "  ·  ACK " + ackCount : "");
        ui.post(() -> {
            BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
            d.setFilterBitmap(false);   // crisp modules when scaled up
            d.setAntiAlias(false);
            qr.setImageDrawable(d);
            info.setText(line);
        });
    }

    private int nextIndex(int from) {
        int n = frames.size();
        for (int k = 1; k <= n; k++) { int j = (from + k) % n; if (!acked[j]) return j; }
        java.util.Arrays.fill(acked, false);   // all acknowledged but no DONE: send everything again
        return (from + 1) % n;
    }

    private final Runnable tick = () -> { if (!paused && sending) { showFrame(nextIndex(cur)); schedule(); } };

    private void schedule() {
        worker.removeCallbacks(tick);
        if (paused || !sending) return;
        worker.postDelayed(tick, ackMode ? Math.max(600, 3 * periodMs) : periodMs);
    }

    private void step(int d) {
        paused = true;
        pause.setText("▶");
        worker.post(() -> { worker.removeCallbacks(tick); if (frames != null) showFrame((cur + d + frames.size()) % frames.size()); });
    }

    // AckListener callbacks (listener thread) -> worker thread
    @Override public void onAck() {
        worker.post(() -> {
            if (!sending) return;
            // a beep right after switching frames most likely belongs to the previous frame
            int idx = SystemClock.uptimeMillis() - shownAt < 120 ? (cur - 1 + frames.size()) % frames.size() : cur;
            if (!acked[idx]) { acked[idx] = true; ackCount++; }
            if (!paused && idx == cur) { showFrame(nextIndex(cur)); schedule(); }
        });
    }

    @Override public void onDone() {
        ui.post(() -> {
            if (show.getVisibility() != View.VISIBLE) return;
            stopSending();
            toast(getString(R.string.receiver_done));
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
