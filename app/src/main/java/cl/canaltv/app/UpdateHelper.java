package cl.canaltv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones dentro de la app: revisa si hay una versión nueva en GitHub,
 * pregunta con un mensaje, descarga el APK con barra de progreso y abre el
 * instalador de Android directamente (sin pasar por el navegador).
 */
final class UpdateHelper {

    private static final String API =
            "https://api.github.com/repos/michaelpalmad-lang/recuerdosretrochile/releases/latest";
    private static final String APK =
            "https://github.com/michaelpalmad-lang/recuerdosretrochile/releases/latest/download/recuerdosretro.apk";
    /** versionCode = 100 + número de compilación de GitHub (ver app/build.gradle). */
    static final int BASE = 100;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File apkDescargado;
    private boolean esperandoPermiso = false;
    private boolean revisado = false;

    UpdateHelper(Activity act) {
        this.act = act;
    }

    /** Revisa en segundo plano (una vez por apertura de la app). */
    void revisar() {
        if (revisado) return;
        revisado = true;
        new Thread(() -> {
            try {
                int instalada = numeroInstalado();
                JSONObject rel = new JSONObject(leer(API));
                int nueva = numeroDeTag(rel.optString("tag_name", ""));
                if (nueva > instalada && instalada > 0) {
                    String notas = rel.optString("body", "").trim();
                    ui.post(() -> ofrecer(nueva, notas));
                }
            } catch (Exception ignored) {
                // sin internet o GitHub no responde: se intenta en la próxima apertura
            }
        }).start();
    }

    /** Llamar desde onResume: si fue a activar el permiso, continúa la instalación. */
    void alVolver() {
        if (!esperandoPermiso || apkDescargado == null) return;
        esperandoPermiso = false;
        instalar(apkDescargado);
    }

    // ------------------------------------------------------------------

    private void ofrecer(int nueva, String notas) {
        if (act.isFinishing()) return;
        String msg = "Hay una nueva versión de " + act.getString(R.string.app_name)
                + " (versión 1." + nueva + ").\n\n¿Quieres actualizar ahora? Se descarga e instala aquí mismo, "
                + "sin perder nada.";
        if (!notas.isEmpty() && notas.length() < 400) msg += "\n\nNovedades:\n" + notas;
        new AlertDialog.Builder(act)
                .setTitle("📺 Actualización disponible")
                .setMessage(msg)
                .setPositiveButton("Actualizar", (d, w) -> descargar())
                .setNegativeButton("Más tarde", null)
                .show();
    }

    private void descargar() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(24 * act.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        TextView txt = new TextView(act);
        txt.setText("Descargando… 0%");
        txt.setGravity(Gravity.CENTER_HORIZONTAL);
        ProgressBar barra = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        box.addView(txt);
        box.addView(barra);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Actualizando")
                .setView(box)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            File destino = new File(act.getCacheDir(), "actualizacion/recuerdosretro.apk");
            try {
                destino.getParentFile().mkdirs();
                HttpURLConnection c = abrir(APK);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destino)) {
                    byte[] buf = new byte[16384];
                    long leido = 0;
                    int n, ultimo = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        leido += n;
                        if (total > 0) {
                            int pct = (int) (leido * 100 / total);
                            if (pct != ultimo) {
                                ultimo = pct;
                                ui.post(() -> { barra.setProgress(pct); txt.setText("Descargando… " + pct + "%"); });
                            }
                        }
                    }
                }
                ui.post(() -> {
                    dlg.dismiss();
                    apkDescargado = destino;
                    instalar(destino);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    dlg.dismiss();
                    new AlertDialog.Builder(act)
                            .setTitle("No se pudo descargar")
                            .setMessage("Revisa tu conexión a internet e inténtalo de nuevo.")
                            .setPositiveButton("Reintentar", (d, w) -> descargar())
                            .setNegativeButton("Cerrar", null)
                            .show();
                });
            }
        }).start();
    }

    private void instalar(File apk) {
        // Android 8+: la app necesita permiso para instalar actualizaciones
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("Un último paso")
                    .setMessage("Para instalar la actualización, activa \"Permitir de esta fuente\" para "
                            + act.getString(R.string.app_name) + " y luego vuelve con el botón Atrás.")
                    .setPositiveButton("Ir a activar", (d, w) -> {
                        esperandoPermiso = true;
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            esperandoPermiso = false;
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".archivos", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------

    private int numeroInstalado() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return (int) code - BASE;
        } catch (Exception e) {
            return -1;
        }
    }

    /** "v1.12" → 12 */
    private static int numeroDeTag(String tag) {
        try {
            return Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1).replaceAll("\\D", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static HttpURLConnection abrir(String url) throws Exception {
        // sigue redirecciones de GitHub manualmente
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "RecuerdosRetroApp");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("Demasiadas redirecciones");
    }

    private static String leer(String url) throws Exception {
        HttpURLConnection c = abrir(url);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        return sb.toString();
    }
}
