package cl.canaltv.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.InputStream;

/**
 * Solo en celulares: control de reproducción en segundo plano.
 * Notificación multimedia (logo + Pausa/Reanudar + Cerrar), controles en pantalla
 * de bloqueo y audífonos, y mantiene la app viva con la pantalla apagada.
 */
public class PlaybackService extends Service {

    public static final String EXTRA_PAUSADO = "pausado";
    static final String ACCION_TOGGLE = "cl.canaltv.app.TOGGLE";
    static final String ACCION_CERRAR = "cl.canaltv.app.CERRAR";

    private static final String CANAL = "reproduccion_v2";
    private static final int ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession session;
    private Bitmap logo;
    private boolean pausado = false;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecuerdosRetro:audio");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RecuerdosRetro:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        logo = cargarLogo();

        session = new MediaSession(this, "RecuerdosRetro");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()  { avisarApp(ACCION_TOGGLE); }
            @Override public void onPause() { avisarApp(ACCION_TOGGLE); }
            @Override public void onStop()  { cerrarTodo(); }
        });
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "En vivo · 70's 80's 90's");
        if (logo != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logo);
        session.setMetadata(md.build());
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;

        if (ACCION_CERRAR.equals(accion)) { cerrarTodo(); return START_NOT_STICKY; }
        if (ACCION_TOGGLE.equals(accion)) {
            avisarApp(ACCION_TOGGLE); // la app cambia el estado y nos vuelve a llamar
        } else if (intent != null && intent.hasExtra(EXTRA_PAUSADO)) {
            pausado = intent.getBooleanExtra(EXTRA_PAUSADO, false);
        }

        actualizarEstado();
        Notification n = construirNotificacion();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        return START_NOT_STICKY;
    }

    private void actualizarEstado() {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(pausado ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private PendingIntent alServicio(String accion, int codigo) {
        Intent i = new Intent(this, PlaybackService.class).setAction(accion);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, codigo, i, f);
    }

    private Notification construirNotificacion() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent abrir = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piAbrir = PendingIntent.getActivity(this, 0, abrir, f);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CANAL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentTitle(getString(R.string.app_name))
         .setContentText(pausado ? "En pausa" : "Sonando en vivo")
         .setContentIntent(piAbrir)
         .setDeleteIntent(alServicio(ACCION_CERRAR, 3)) // si la deslizan, se detiene todo
         .setOngoing(!pausado)
         .setShowWhen(false)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT)
         .addAction(pausado ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                    pausado ? "Reanudar" : "Pausar", alServicio(ACCION_TOGGLE, 1))
         .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", alServicio(ACCION_CERRAR, 2))
         .setStyle(new Notification.MediaStyle()
                 .setMediaSession(session.getSessionToken())
                 .setShowActionsInCompactView(0, 1));
        if (logo != null) b.setLargeIcon(logo);
        return b.build();
    }

    private void avisarApp(String accion) {
        sendBroadcast(new Intent(accion).setPackage(getPackageName()));
    }

    private void cerrarTodo() {
        avisarApp(ACCION_CERRAR);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private Bitmap cargarLogo() {
        try (InputStream in = getAssets().open("logo.png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si deslizan la app desde recientes, se detiene todo
        cerrarTodo();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
