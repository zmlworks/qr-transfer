package com.zmlworks.qrtransfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        findViewById(R.id.send).setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        findViewById(R.id.receive).setOnClickListener(v -> startActivity(new Intent(this, ReceiveActivity.class)));
        findViewById(R.id.share_web).setOnClickListener(v -> shareWebSender());
    }

    /** The offline browser sender is bundled as an asset; hand it to a PC via any share target. */
    private void shareWebSender() {
        try {
            File f = new File(ShareProvider.dir(this), "qr-sender.html");
            try (InputStream in = getAssets().open("sender.html"); OutputStream o = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
            Uri u = ShareProvider.uriFor(this, f);
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri(f.getName(), u));
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
    }
}
