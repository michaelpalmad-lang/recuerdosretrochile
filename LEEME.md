# Recuerdos Retro Chile — app Android / Android TV

App que abre tu canal (reproductor oficial de tvstream.cl) a pantalla completa.
Funciona en teléfonos, tablets, Android TV, Google TV y TV Box.

## Cómo obtener el APK (gratis, sin instalar nada)
1. Crea una cuenta en github.com (si no tienes).
2. Botón "+" → "New repository" → nombre: `recuerdosretrochile` → **Public** → Create.
3. "uploading an existing file" → arrastra TODO el contenido de esta carpeta
   (incluida la carpeta `.github`) → "Commit changes".
   * Si la carpeta `.github` no se subió: "Add file → Create new file", escribe
     `.github/workflows/build.yml` como nombre y pega el contenido de ese archivo.
4. Pestaña **Actions**: espera ~3 min a que quede en verde.
5. El APK queda siempre en:
   `https://github.com/TU_USUARIO/recuerdosretrochile/releases/latest/download/recuerdosretro.apk`

## Instalar en el Smart TV
1. En el TV instala **Downloader** (by AFTVnews) desde Google Play.
2. Ajustes del TV → Seguridad / Apps → permitir "fuentes desconocidas" para Downloader.
3. En Downloader escribe la URL del paso 5 → Instalar.
4. La app aparece en el inicio del TV con su banner.

## Control remoto
- OK / Play-Pausa: pausar o reanudar
- Menú o Canal +/-: recargar la señal
- Atrás: salir

Si la señal se corta o se congela, la app se reconecta sola.

## Personalizar
- Nombre: `app/src/main/res/values/strings.xml`
- Ícono: `app/src/main/res/mipmap-*/ic_launcher.png`
- Banner TV (320×180): `app/src/main/res/drawable-xhdpi/banner.png`
- Reproductor: `app/src/main/assets/player.html`
