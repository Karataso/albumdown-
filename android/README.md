# AlbumDown Android (v0.1)

App open source (GPL-3.0, porque `youtubedl-android` lo exige) fuera de Play Store.
Pegar link → preview → MP3s en `Music/Artista/Álbum/`.

## Compilar

1. Abrir `android/` en Android Studio.
2. Instalar SDK 35 cuando lo pida. JDK 17+ (el JBR que trae Studio sirve).
3. `Run` en un cel físico (yt-dlp no tiene sentido en emulador x86) o
   `Build > Generate Signed Bundle/APK` (splits arm64 + arm32).

## Tests puros

`./gradlew :app:testDebugUnitTest` — sin emulador.

## Notas v0.1

- ffmpeg: `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` — el `com.arthenica`
  original se retiró de Maven Central (abril 2025). Este fork es drop-in
  (mismo paquete `com.arthenica.ffmpegkit`).
- yt-dlp se auto-actualiza 1 vez por semana antes de cada descarga (respeta
  solo-WiFi); botón manual + versión en ⚙.
- Spotify: scraping del embed público (sin credenciales) para la lista de
  temas; el audio se busca en YouTube al descargar.
- Firma de release: `keystore.properties` + `keystore/` (no commiteados).
- Lyrics / cookies = v0.3+ solo si duelen.
