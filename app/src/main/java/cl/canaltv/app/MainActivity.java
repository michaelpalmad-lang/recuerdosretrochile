package cl.canaltv.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    // Dominio base del reproductor: las rutas relativas del script se resuelven contra él
    private static final String BASE_URL = "https://panel.tvstream.cl:2020/";

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Mantiene el video reproduciéndose y recarga si la señal se congela. */
    private static final String WATCHDOG_JS =
        "(function(){" +
        " if(window.__wd) return; window.__wd=true; window.__userPaused=false;" +
        " var last=-1, stuck=0;" +
        " function vids(){var l=[].slice.call(document.querySelectorAll('video'));" +
        "  [].forEach.call(document.querySelectorAll('iframe'),function(f){" +
        "   try{l=l.concat([].slice.call(f.contentDocument.querySelectorAll('video')));}catch(e){}});return l;}" +
        " window.__toggle=function(){var v=vids()[0]; if(!v) return;" +
        "  if(v.paused){window.__userPaused=false; v.play();} else {window.__userPaused=true; v.pause();}};" +
        " setInterval(function(){var v=vids()[0]; if(!v) return;" +
        "  var c=document.getElementById('cargando'); if(c) c.style.display='none';" +
        "  if(window.__userPaused) {stuck=0; return;}" +
        "  if(v.paused){ var p=v.play(); if(p&&p.catch) p.catch(function(){}); }" +
        "  if(v.currentTime===last){ stuck++; if(stuck>=8) location.reload(); } else { stuck=0; last=v.currentTime; }" +
        " },3000);" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        configureWebView();
        hideSystemUi();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(WATCHDOG_JS, null);
                // segundo intento por si el reproductor se crea tarde
                handler.postDelayed(() -> web.evaluateJavascript(WATCHDOG_JS, null), 4000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) retryLater();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false; // todo se queda dentro de la app
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }
        });
    }

    private void loadPlayer() {
        String html = readAsset("player.html");
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
    }

    private void retryLater() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::loadPlayer, 5000);
    }

    private void exitCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        web.setVisibility(View.VISIBLE);
        hideSystemUi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    web.evaluateJavascript("window.__toggle&&window.__toggle()", null);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    loadPlayer(); // recargar señal
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (customView != null) { exitCustomView(); return true; }
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        web.onResume();
        loadPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        web.loadUrl("about:blank"); // corta el audio al salir
        web.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "<html><body style='background:#000'></body></html>";
        }
    }
}
