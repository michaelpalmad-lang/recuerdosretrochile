package cl.canaltv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.CastStateListener;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.InputStream;

/**
 * Chromecast (solo celular): botón de transmitir sobre el video, envía la señal
 * en vivo al TV y muestra una pantalla "Transmitiendo en…" mientras tanto.
 */
final class CastHelper {

    interface Listener {
        void onCastStart();
        void onCastEnd();
    }

    private final AppCompatActivity act;
    private final FrameLayout root;
    private final Listener listener;

    private CastContext ctx;
    private MediaRouteButton boton;
    private LinearLayout pantalla;
    private TextView textoPantalla;

    /** Enlace oficial HLS del canal (panel tvstream.cl). */
    static final String ENLACE_OFICIAL = "https://panel.tvstream.cl:1936/8034/8034/playlist.m3u8";

    // Se usa el oficial; la detección automática solo actúa si este quedara vacío
    private String enlace = ENLACE_OFICIAL;
    private CastSession sesion;
    private boolean cargadoEnTV = false;
    private boolean botonPermitido = true;
    private int estado = CastState.NO_DEVICES_AVAILABLE;

    CastHelper(AppCompatActivity act, FrameLayout root, Listener listener) {
        this.act = act;
        this.root = root;
        this.listener = listener;
    }

    /** Devuelve false si el teléfono no tiene servicios de Google / Cast. */
    boolean iniciar() {
        try {
            ctx = CastContext.getSharedInstance(act);
        } catch (Exception e) {
            ctx = null;
            return false;
        }

        // Botón de transmitir (arriba a la derecha)
        boton = new MediaRouteButton(act);
        CastButtonFactory.setUpMediaRouteButton(act.getApplicationContext(), boton);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setShape(GradientDrawable.OVAL);
        fondo.setColor(0x88000000);
        boton.setBackground(fondo);
        int t = dp(48), m = dp(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(t, t, Gravity.TOP | Gravity.END);
        lp.setMargins(m, m, m, m);
        root.addView(boton, lp);

        crearPantalla();

        ctx.addCastStateListener(estadoListener);
        ctx.getSessionManager().addSessionManagerListener(sesionListener, CastSession.class);
        estado = ctx.getCastState();
        actualizarBoton();

        // Si ya había una transmisión activa (app reabierta)
        CastSession actual = ctx.getSessionManager().getCurrentCastSession();
        if (actual != null && actual.isConnected()) empezar(actual);
        return true;
    }

    void liberar() {
        if (ctx == null) return;
        ctx.removeCastStateListener(estadoListener);
        ctx.getSessionManager().removeSessionManagerListener(sesionListener, CastSession.class);
    }

    boolean transmitiendo() {
        return sesion != null;
    }

    /** Oculta el botón en la ventana flotante o durante la intro. */
    void setBotonPermitido(boolean permitido) {
        botonPermitido = permitido;
        actualizarBoton();
        if (pantalla != null) pantalla.setVisibility(sesion != null && permitido ? View.VISIBLE : View.GONE);
    }

    /** Lo llama la app cada vez que el reproductor pide un enlace .m3u8. */
    void enlaceDetectado(String url) {
        if (url == null) return;
        boolean mejor = enlace == null
                || (enlace.contains("chunklist") && !url.contains("chunklist"));
        if (!mejor) return;
        enlace = url;
        if (sesion != null && !cargadoEnTV) cargarEnTV();
    }

    // ------------------------------------------------------------------

    private final CastStateListener estadoListener = new CastStateListener() {
        @Override
        public void onCastStateChanged(int nuevo) {
            estado = nuevo;
            actualizarBoton();
        }
    };

    private final SessionManagerListener<CastSession> sesionListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession s, String id) { empezar(s); }
        @Override public void onSessionResumed(CastSession s, boolean b) { empezar(s); }
        @Override public void onSessionEnded(CastSession s, int error) { terminar(); }
        @Override public void onSessionStartFailed(CastSession s, int error) { terminar(); }
        @Override public void onSessionResumeFailed(CastSession s, int error) { terminar(); }
        @Override public void onSessionSuspended(CastSession s, int reason) { }
        @Override public void onSessionStarting(CastSession s) { }
        @Override public void onSessionEnding(CastSession s) { }
        @Override public void onSessionResuming(CastSession s, String id) { }
    };

    private void empezar(CastSession s) {
        boolean nueva = sesion == null;
        sesion = s;
        cargadoEnTV = false;
        String tv = "tu TV";
        try { if (s.getCastDevice() != null) tv = s.getCastDevice().getFriendlyName(); } catch (Exception ignored) { }
        textoPantalla.setText("Transmitiendo en " + tv);
        pantalla.setVisibility(botonPermitido ? View.VISIBLE : View.GONE);
        if (nueva) listener.onCastStart();
        actualizarBoton();

        // Si el TV ya está mostrando algo de esta sesión, no se recarga
        RemoteMediaClient rmc = s.getRemoteMediaClient();
        if (rmc != null && rmc.getMediaInfo() != null) { cargadoEnTV = true; return; }
        cargarEnTV();
    }

    private void terminar() {
        if (sesion == null) return;
        sesion = null;
        cargadoEnTV = false;
        pantalla.setVisibility(View.GONE);
        listener.onCastEnd();
    }

    private void cargarEnTV() {
        if (sesion == null) return;
        if (enlace == null) {
            textoPantalla.setText("Buscando la señal para enviarla al TV…");
            return;
        }
        RemoteMediaClient rmc = sesion.getRemoteMediaClient();
        if (rmc == null) return;

        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, act.getString(R.string.app_name));
        md.putString(MediaMetadata.KEY_SUBTITLE, "En vivo · 70's 80's 90's");

        MediaInfo info = new MediaInfo.Builder(enlace)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .setMetadata(md)
                .build();
        rmc.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build());
        cargadoEnTV = true;
        try {
            String tv = sesion.getCastDevice() != null ? sesion.getCastDevice().getFriendlyName() : "tu TV";
            textoPantalla.setText("Transmitiendo en " + tv);
        } catch (Exception ignored) { }
    }

    private void actualizarBoton() {
        if (boton == null) return;
        boolean hayTV = estado != CastState.NO_DEVICES_AVAILABLE;
        boton.setVisibility(hayTV && botonPermitido ? View.VISIBLE : View.GONE);
        boton.bringToFront();
    }

    private void crearPantalla() {
        pantalla = new LinearLayout(act);
        pantalla.setOrientation(LinearLayout.VERTICAL);
        pantalla.setGravity(Gravity.CENTER);
        pantalla.setBackgroundColor(0xF0121110);
        pantalla.setClickable(true); // bloquea toques al video de atrás
        pantalla.setVisibility(View.GONE);

        ImageView logo = new ImageView(act);
        Bitmap bmp = cargarLogo();
        if (bmp != null) logo.setImageBitmap(bmp);
        logo.setAdjustViewBounds(true);
        pantalla.addView(logo, new LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT));

        textoPantalla = new TextView(act);
        textoPantalla.setTextColor(Color.WHITE);
        textoPantalla.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textoPantalla.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(16);
        pantalla.addView(textoPantalla, tp);

        Button detener = new Button(act);
        detener.setText("Detener transmisión");
        detener.setAllCaps(false);
        detener.setTextColor(0xFF1C1A18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4D628);
        bg.setCornerRadius(dp(24));
        detener.setBackground(bg);
        detener.setPadding(dp(24), 0, dp(24), 0);
        detener.setOnClickListener(v -> {
            try { ctx.getSessionManager().endCurrentSession(true); } catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        bp.topMargin = dp(20);
        pantalla.addView(detener, bp);

        root.addView(pantalla, new FrameLayout.LayoutParams(-1, -1));
    }

    private Bitmap cargarLogo() {
        try (InputStream in = act.getAssets().open("logo.png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unused")
    private void aviso(String t) {
        Toast.makeText(act, t, Toast.LENGTH_SHORT).show();
    }
}
