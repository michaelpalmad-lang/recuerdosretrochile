package cl.canaltv.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
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

    public static final String EXTRA_SALIR = "salir";

    // Dominio base del reproductor: las rutas relativas del script se resuelven contra él
    private static final String BASE_URL = "https://panel.tvstream.cl:2020/";

    private FrameLayout root;
    private WebView web;
    private WebView intro;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean esTV;
    private boolean cargado = false;

    /** Mantiene el video reproduciéndose y recarga si la señal se congela. */
    private static final String WATCHDOG_JS =
        "(function(){" +
        " if(window.__wd) return; window.__wd=true; window.__userPaused=false;" +
        " var last=-1, stuck=0, ticks=0;" +
        // busca videos en la página y en todos los iframes accesibles (recursivo)
        " function collect(doc,l,blocked){ try{" +
        "   [].push.apply(l,[].slice.call(doc.querySelectorAll('video')));" +
        "   [].forEach.call(doc.querySelectorAll('iframe'),function(f){" +
        "     try{ if(f.contentDocument) collect(f.contentDocument,l,blocked); else blocked.push(f);}" +
        "     catch(e){ blocked.push(f); } });" +
        "  }catch(e){} }" +
        " function vids(){var l=[],b=[];collect(document,l,b);return {v:l,b:b};}" +
        // quita el silencio: atributo, propiedad, volumen y botones del reproductor
        " function unmute(v){ try{" +
        "   if(v.muted||v.volume<1){ v.muted=false; v.removeAttribute('muted'); v.defaultMuted=false; v.volume=1; }" +
        "   var d=v.ownerDocument;" +
        "   [].forEach.call(d.querySelectorAll('.vjs-mute-control.vjs-vol-0, .vjs-mute-control[title=Unmute], .jw-icon-volume.jw-off, [aria-label*=nmute], .unmute, .mute-button.muted, .plyr__control[data-plyr=mute][aria-pressed=true]')," +
        "     function(b){ try{ b.click(); }catch(e){} });" +
        "   if(window.jwplayer){ try{ jwplayer().setMute(false); jwplayer().setVolume(100);}catch(e){} }" +
        "   if(window.videojs){ try{ for(var k in videojs.getPlayers()){var p=videojs.getPlayers()[k]; if(p){p.muted(false); p.volume(1);}} }catch(e){} }" +
        "  }catch(e){} }" +
        " window.__toggle=function(){var v=vids().v[0]; if(!v) return;" +
        "  if(v.paused){window.__userPaused=false; v.play(); unmute(v);} else {window.__userPaused=true; v.pause();}};" +
        " setInterval(function(){ ticks++; var r=vids(), v=r.v[0];" +
        // si el reproductor está en un iframe de otro dominio, se abre directo para poder controlarlo
        "  if(!v && r.b.length && document.getElementById('cargando') && ticks>=3){" +
        "    var s=r.b[0].src; if(s && /^https?:/.test(s)){ location.href=s; return; } }" +
        "  if(!v) return;" +
        "  var c=document.getElementById('cargando'); if(c) c.style.display='none';" +
        "  unmute(v);" +
        "  if(window.__userPaused) {stuck=0; return;}" +
        "  if(v.paused){ var p=v.play(); if(p&&p.catch) p.catch(function(){}); }" +
        "  if(ticks%3===0){ if(v.currentTime===last){ stuck++; if(stuck>=8) location.reload(); } else { stuck=0; last=v.currentTime; } }" +
        " },1000);" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_SALIR, false)) { finish(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        esTV = detectarTV();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        configureWebView();
        mostrarIntro();
        hideSystemUi();

        if (!esTV) {
            // Celular: permiso de notificación (Android 13+) y servicio para sonar en segundo plano
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            iniciarServicio();
        }
    }

    private boolean detectarTV() {
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature("android.software.leanback")
                || pm.hasSystemFeature("android.hardware.type.television");
    }

    private void iniciarServicio() {
        try {
            Intent s = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        } catch (Exception ignored) { }
    }

    private void detenerServicio() {
        try { stopService(new Intent(this, PlaybackService.class)); } catch (Exception ignored) { }
    }

    /** Intro vintage (encendido de TV antigua) sobre el reproductor, que carga por detrás. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void mostrarIntro() {
        intro = new WebView(this);
        intro.setBackgroundColor(Color.BLACK);
        intro.setFocusable(false);
        WebSettings s = intro.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        intro.addJavascriptInterface(new IntroBridge(this), "Intro");
        root.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        intro.loadUrl("file:///android_asset/intro.html");
        handler.postDelayed(this::ocultarIntro, 7000); // por si acaso
    }

    /** Puente para que intro.html avise cuando termina. */
    public static final class IntroBridge {
        private final MainActivity a;
        IntroBridge(MainActivity a) { this.a = a; }
        @JavascriptInterface
        public void done() { a.handler.post(a::ocultarIntro); }
    }

    private void ocultarIntro() {
        final WebView v = intro;
        if (v == null) return;
        intro = null;
        v.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            root.removeView(v);
            v.destroy();
            web.requestFocus();
        }).start();
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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(WATCHDOG_JS, null);
                handler.postDelayed(() -> { if (web != null) web.evaluateJavascript(WATCHDOG_JS, null); }, 4000);
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
        cargado = true;
        String html = readAsset("player.html");
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
    }

    private final Runnable reintento = this::loadPlayer;

    private void retryLater() {
        handler.removeCallbacks(reintento);
        handler.postDelayed(reintento, 5000);
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
                    if (intro != null) { ocultarIntro(); return true; }
                    web.evaluateJavascript("window.__toggle&&window.__toggle()", null);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    loadPlayer(); // recargar señal
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (intro != null) { ocultarIntro(); return true; }
                    if (customView != null) { exitCustomView(); return true; }
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SALIR, false)) finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web == null) return;
        web.onResume();
        if (!cargado) loadPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (web == null) return;
        if (esTV || isFinishing()) {
            // TV: al salir se corta la señal
            handler.removeCallbacks(reintento);
            web.loadUrl("about:blank");
            web.onPause();
            cargado = false;
        }
        // Celular: sigue sonando en segundo plano (el servicio mantiene la app viva)
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!esTV) detenerServicio();
        if (intro != null) { intro.destroy(); intro = null; }
        if (web != null) { web.destroy(); web = null; }
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
