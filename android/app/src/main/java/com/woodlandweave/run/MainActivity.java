package com.woodlandweave.run;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.res.Configuration;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Apply before the game or its saved settings can load.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        super.onCreate(savedInstanceState);
        hideSystemBars();
    }

    @Override
    protected void load() {
        // Install the interface BEFORE Bridge loads the first document.
        WebView web = findViewById(com.getcapacitor.android.R.id.webview);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void set(String mode) {
                runOnUiThread(() -> {
                    setRequestedOrientation("any".equals(mode)
                        ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    hideSystemBars();
                });
            }
        }, "AndroidOrientation");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public int versionCode() {
                try {
                    return (int) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                        getPackageManager().getPackageInfo(getPackageName(), 0));
                } catch (Exception e) { return 0; }
            }
            @JavascriptInterface
            public void download(String url) {
                if (url == null || !url.startsWith("https://github.com/")) return;
                new Thread(() -> downloadAndInstall(url)).start();
            }
        }, "AndroidUpdater");
        super.load();
    }

    private void downloadAndInstall(String url) {
        try {
            File dir = new File(getCacheDir(), "updates");
            dir.mkdirs();
            File apk = new File(dir, "update.apk");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> startActivity(install));
        } catch (Exception ignored) { }
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
            getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemBars();
    }
}
