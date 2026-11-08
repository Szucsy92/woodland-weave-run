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
import android.media.AudioManager;
import android.view.WindowManager;
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
        // Hardware volume keys must adjust this game's media, not ringtone volume.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
    }

    @Override
    protected void load() {
        // Install the interface BEFORE Bridge loads the first document.
        WebView web = findViewById(com.getcapacitor.android.R.id.webview);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public boolean isMuted() {
                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                return audio == null || AudioOutputPolicy.shouldMute(
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.isStreamMute(AudioManager.STREAM_MUSIC),
                    audio.getRingerMode());
            }
        }, "AndroidAudio");
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
            public void pulse(String pattern) {
                try {
                    String[] parts = pattern.split(",");
                    long[] timings = new long[parts.length + 1];
                    int[] amps = new int[parts.length + 1];
                    for (int i = 0; i < parts.length; i++) {
                        timings[i + 1] = Math.max(0, Math.min(400, Long.parseLong(parts[i].trim())));
                        amps[i + 1] = i % 2 == 0 ? 110 : 0;
                    }
                    android.os.Vibrator v;
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        v = ((android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();
                    } else {
                        v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                    }
                    if (v == null || !v.hasVibrator()) return;
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        boolean amp = v.hasAmplitudeControl();
                        v.vibrate(amp
                            ? android.os.VibrationEffect.createWaveform(timings, amps, -1)
                            : android.os.VibrationEffect.createWaveform(timings, -1));
                    } else {
                        v.vibrate(timings, -1);
                    }
                } catch (Exception ignored) { }
            }
        }, "AndroidHaptics");
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

    private File pendingApk;

    private void notifyUpdate(String state, int percent, String message) {
        String detail = "{state:" + org.json.JSONObject.quote(state) + ",percent:" + percent
            + ",message:" + org.json.JSONObject.quote(message == null ? "" : message) + "}";
        runOnUiThread(() -> {
            WebView web = findViewById(com.getcapacitor.android.R.id.webview);
            if (web != null) web.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('forest-update',{detail:" + detail + "}))", null);
        });
    }

    private boolean mayInstall() {
        return android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void launchInstall(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(install);
        notifyUpdate("installing", 100, "");
    }

    private void downloadAndInstall(String url) {
        try {
            File dir = new File(getCacheDir(), "updates");
            dir.mkdirs();
            File part = new File(dir, "update.apk.part");
            File apk = new File(dir, "update.apk");
            notifyUpdate("progress", 0, "");
            String current = url;
            HttpURLConnection conn = null;
            // GitHub answers with redirects to another host; follow them by hand.
            for (int hop = 0; hop < 8; hop++) {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "WoodlandWeaveRun");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (next == null) throw new Exception("redirect without target");
                    current = new URL(new URL(current), next).toString();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                break;
            }
            if (conn == null) throw new Exception("no connection");
            long total = conn.getContentLengthLong();
            long done = 0;
            int lastPct = -1;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) { lastPct = pct; notifyUpdate("progress", pct, ""); }
                    }
                }
            }
            if (total > 0 && done != total) throw new Exception("incomplete download");
            if (apk.exists()) apk.delete();
            if (!part.renameTo(apk)) throw new Exception("cannot save file");
            if (!mayInstall()) {
                pendingApk = apk;
                notifyUpdate("permission", 100, "");
                Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> startActivity(settings));
                return;
            }
            runOnUiThread(() -> {
                try { launchInstall(apk); }
                catch (Exception e) { notifyUpdate("error", 0, String.valueOf(e.getMessage())); }
            });
        } catch (Exception e) {
            notifyUpdate("error", 0, String.valueOf(e.getMessage()));
        }
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
        // Back from the "install unknown apps" screen: continue with the saved download.
        if (pendingApk != null && mayInstall()) {
            File apk = pendingApk;
            pendingApk = null;
            try { launchInstall(apk); }
            catch (Exception e) { notifyUpdate("error", 0, String.valueOf(e.getMessage())); }
        }
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
