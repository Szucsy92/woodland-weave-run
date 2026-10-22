package com.woodlandweave.run;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = getBridge().getWebView();
        // The menu drone must start on launch, before any tap.
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void set(String mode) {
                runOnUiThread(() -> setRequestedOrientation("any".equals(mode)
                    ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
            }
        }, "AndroidOrientation");
    }
}
