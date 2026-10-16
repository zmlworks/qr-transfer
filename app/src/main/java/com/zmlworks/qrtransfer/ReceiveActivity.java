package com.zmlworks.qrtransfer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;

/** Receiver: Camera2 preview + ZXing decode of each frame, progress grid, CRC check, save/share. */
public final class ReceiveActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAM = 1, REQ_SAVE = 2;

    private AutoFitTextureView preview;
    private TextView progress, missing, resultInfo, resultText;
    private ChunkGridView grid;
    private CheckBox beep;
    private View resultPanel, scanPanel;

    private HandlerThread camThread;
    private Handler cam;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size size;

    // decoder state: camera thread only
    private final Protocol.Assembler asm = new Protocol.Assembler();
    private final FrameDecoder decoder = new FrameDecoder();
    private final AckPolicy policy = new AckPolicy();
    private String completedId;
    private long completedAt;
    private byte[] yBuf;
    private volatile boolean completed;
    private volatile boolean beepOn = true;
    private boolean asking;

    private AckPlayer player;
    private Protocol.Content content;   // UI thread

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_receive);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        preview = findViewById(R.id.preview);
        progress = findViewById(R.id.progress);
        missing = findViewById(R.id.missing);
        grid = findViewById(R.id.grid);
        beep = findViewById(R.id.beep);
        resultPanel = findViewById(R.id.result_panel);
        scanPanel = findViewById(R.id.scan_panel);
        resultInfo = findViewById(R.id.result_info);
        resultText = findViewById(R.id.result_text);
        beep.setOnCheckedChangeListener((v, on) -> beepOn = on);
        findViewById(R.id.copy).setOnClickListener(v -> copy());
        findViewById(R.id.save).setOnClickListener(v -> saveAs());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.again).setOnClickListener(v -> again());
        preview.setSurfaceTextureListener(this);
        player = new AckPlayer();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderProgress(0, 0, "", new boolean[0], -1, "");
    }

    @Override protected void onResume() {
        super.onResume();
        camThread = new HandlerThread("camera");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!asking) { asking = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM); }
        } else if (preview.isAvailable()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        camThread.quitSafely();
        super.onPause();
    }

    @Override protected void onDestroy() { player.release(); super.onDestroy(); }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_CAM) return;
        asking = false;
        // when granted, onResume() (which follows) opens the camera on a live camera thread
        if (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ---------------- camera ----------------
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    /** Prefer ~1920x1080 (16:9): two codes share the frame, so each needs the pixels; still cheap to decode. */
    static Size choose(Size[] sizes) {
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double aspect = (double) s.getWidth() / s.getHeight();
            double score = Math.abs(aspect - 16.0 / 9) * 4 + Math.abs(Math.log((double) s.getWidth() * s.getHeight() / (1920.0 * 1080)));
            if (s.getWidth() * s.getHeight() <= 1920 * 1080 && score < bestScore) { best = s; bestScore = score; }
        }
        return best != null ? best : sizes[0];
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (device != null) return;
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
            if (id == null) { Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show(); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            size = choose(map.getOutputSizes(ImageFormat.YUV_420_888));
            preview.setAspect(size.getHeight(), size.getWidth());   // portrait
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, cam);
            final int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startSession(afModes); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); device = null; }
            }, cam);
        } catch (CameraAccessException | SecurityException e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")  // createCaptureSession(List...) keeps minSdk 26 simple
    private void startSession(int[] afModes) {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || device == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            final Surface view = new Surface(st), yuv = reader.getSurface();
            final CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(view);
            rb.addTarget(yuv);
            int af = CaptureRequest.CONTROL_AF_MODE_OFF;
            if (afModes != null) for (int m : afModes) {
                if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) { af = m; break; }
                if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) af = m;
            }
            rb.set(CaptureRequest.CONTROL_AF_MODE, af);
            device.createCaptureSession(Arrays.asList(view, yuv), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), null, cam); } catch (CameraAccessException | IllegalStateException ignored) { }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> Toast.makeText(ReceiveActivity.this, R.string.camera_failed, Toast.LENGTH_LONG).show());
                }
            }, cam);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show());
        }
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    // ---------------- decoding (camera thread) ----------------
    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (IllegalStateException e) { return; }
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            // after completion keep looking for ~30 s only to repeat DONE for a sender that did not hear it
            if (completed && now - completedAt > 30_000) return;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rs = p.getRowStride();
            if (yBuf == null || yBuf.length != w * h) yBuf = new byte[w * h];
            if (rs == w) buf.get(yBuf, 0, w * h);
            else for (int y = 0; y < h; y++) { buf.position(y * rs); buf.get(yBuf, y * w, w); }
            List<String> texts = decoder.decode(yBuf, w, h);   // up to two codes (slot A + slot B)
            for (String t : texts) onText(t, now);
        } finally { img.close(); }
    }

    private void onText(String text, long now) {
        if (completed) {
            Protocol.Frame f = Protocol.parse(text);
            if (f != null && f.id.equals(completedId) && beepOn && policy.shouldRepeatDone(now)) player.done();
            return;
        }
        Protocol.Assembler.Result res = asm.accept(text);
        if (res == Protocol.Assembler.Result.IGNORED) return;
        if (res == Protocol.Assembler.Result.RESTARTED) policy.reset();
        boolean done = asm.complete();
        if (beepOn && !done) {
            // alternating-bit ack (tone = slot + bit of this chunk), also for duplicates: the sender may have
            // missed the first ack and is still showing it
            int tone = policy.onFrame(asm.lastSeen(), now);
            if (tone >= 0) player.ack(tone);
        }
        if (res == Protocol.Assembler.Result.DUPLICATE) return;
        int total = asm.total(), got = asm.received(), last = asm.lastIndex();
        boolean[] have = new boolean[total];
        for (int i = 0; i < total; i++) have[i] = asm.has(i);
        String miss = asm.missingRanges(12), id = asm.id();
        final boolean restarted = res == Protocol.Assembler.Result.RESTARTED;
        runOnUiThread(() -> {
            if (restarted) Toast.makeText(this, R.string.new_transfer, Toast.LENGTH_SHORT).show();
            renderProgress(got, total, miss, have, last, id);
        });
        if (done) {
            try {
                final Protocol.Content c = asm.assemble();
                final String crc = asm.crc();
                completedId = asm.id();
                completedAt = now;
                completed = true;
                if (beepOn) { player.done(); policy.shouldRepeatDone(now); }
                runOnUiThread(() -> showResult(c, crc));
            } catch (DataFormatException e) {
                asm.reset();
                policy.reset();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.verify_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }
    }

    // ---------------- UI ----------------
    private void renderProgress(int got, int total, String miss, boolean[] have, int last, String id) {
        if (total == 0) {
            progress.setText(R.string.waiting_qr);
            missing.setText("");
        } else {
            progress.setText(getString(R.string.progress, got, total, 100 * got / total, id));
            missing.setText(miss.isEmpty() ? "" : getString(R.string.missing, miss));
        }
        grid.set(have, last);
    }

    private void showResult(Protocol.Content c, String crc) {
        content = c;
        scanPanel.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        String kind = c.isFile ? getString(R.string.kind_file, c.name) : getString(R.string.kind_text);
        resultInfo.setText(getString(R.string.result_ok, crc, kind, c.data.length));
        if (c.isFile) resultText.setText("");
        else {
            String t = c.text();
            resultText.setText(t.length() > 20_000 ? t.substring(0, 20_000) + "\n…" : t);
        }
        findViewById(R.id.copy).setVisibility(c.isFile ? View.GONE : View.VISIBLE);
    }

    private String outName() {
        if (content.isFile && !content.name.isEmpty()) return content.name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return "received_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + ".txt";
    }

    private void copy() {
        ClipboardManager cb = getSystemService(ClipboardManager.class);
        cb.setPrimaryClip(ClipData.newPlainText("QR transfer", content.text()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void saveAs() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(content.isFile ? "application/octet-stream" : "text/plain").putExtra(Intent.EXTRA_TITLE, outName()), REQ_SAVE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null || content == null) return;
        try (OutputStream o = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (o == null) throw new IOException("null stream");
            o.write(content.data);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException | SecurityException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
    }

    private void share() {
        try {
            File f = new File(ShareProvider.dir(this), outName());
            try (OutputStream o = new FileOutputStream(f)) { o.write(content.data); }
            Uri u = ShareProvider.uriFor(this, f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(getContentResolver().getType(u))
                    .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!content.isFile) i.putExtra(Intent.EXTRA_TEXT, content.text().length() < 50_000 ? content.text() : "");
            i.setClipData(ClipData.newRawUri(f.getName(), u));
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
    }

    private void again() {
        content = null;
        resultPanel.setVisibility(View.GONE);
        scanPanel.setVisibility(View.VISIBLE);
        cam.post(() -> { asm.reset(); policy.reset(); completed = false; completedId = null; });
        renderProgress(0, 0, "", new boolean[0], -1, "");
    }
}
