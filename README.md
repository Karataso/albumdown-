# AlbumDown

Download whole albums as MP3s, on your phone.

Paste a link (or just **Share → AlbumDown** from any app) → preview the
tracklist → pick the tracks you want → get tagged MP3s with cover art in
`Music/Artist/Album/`.

Android app written in Kotlin + Jetpack Compose. It bundles **yt-dlp, Python
and ffmpeg** on-device, so everything happens locally — no server, no account,
no ads.

> Not on Play Store. Build the APK yourself (see [Build](#build)).

## Supported links

| Source | What works |
|--------|-----------|
| YouTube / YouTube Music | playlists, albums, single videos (treated as a 1-track album) |
| SoundCloud | sets and single tracks |
| Spotify | albums, playlists, tracks — tracklist read from Spotify's public embed page, audio then searched on YouTube (Spotify audio itself is DRM'd) |
| Direct audio | `.mp3` `.m4a` `.ogg` `.opus` `.wav` `.flac` URLs |

## What it does

- **Tagged MP3s.** Title, artist, album and track number are embedded in a
  single ffmpeg pass (no extra ID3 libraries).
- **Album cover art** embedded for the whole album — the album's own artwork,
  not per-video thumbnails. WebP/AVIF get converted to JPEG first.
- **Same layout and naming rules as the original CLI**: artist names cleaned
  (`- Topic`, `- Album`, `- VEVO` suffixes removed), playlist titles cleaned
  (`Album - X` → `X`), file names sanitized, artist capitalization unified
  across tracks, track numbers from playlist order.
- **Already-downloaded tracks are skipped** — re-running is safe.
- **Runs in the background** as a foreground service with progress
  notification, so a long album survives you leaving the app. Cancel kills the
  in-flight yt-dlp process immediately.
- **WiFi-only mode** (⚙) — downloads wait for WiFi.
- **yt-dlp self-updates** once a week before each download (respecting the
  WiFi-only setting), plus a manual *Update* button with the current version
  in ⚙. Extractor updates matter: this is what keeps YouTube working.
- Works on Android 8.0+ (API 26), arm64 + arm32.

## Where your files go

```
Music/
└── Artist Name/
    └── Album Name/
        ├── 01. First Song.mp3
        ├── 02. Second Song.mp3
        └── ...
```

Written through MediaStore with scoped storage (no `WRITE_EXTERNAL_STORAGE`),
so it shows up in any music player right away.

## Build

1. Open `android/` in Android Studio.
2. Install SDK 35 when it asks. JDK 17+ (the JBR bundled with Studio is fine).
3. `Run` on a **physical device** — yt-dlp/ffmpeg are pointless on an x86
   emulator — or `Build > Generate Signed Bundle/APK` (ABI splits:
   `arm64-v8a` + `armeabi-v7a`, no x86).

Release signing: `android/keystore.properties` + `android/keystore/`
(not committed). Without them a release build still compiles, just unsigned.

### Tests

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Pure JVM unit tests — no emulator needed.

## Notes

- ffmpeg is provided by `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` —
  the original `com.arthenica` artifact was pulled from Maven Central in April
  2025. That fork is a drop-in (same `com.arthenica.ffmpegkit` package).
- Spotify resolution scrapes the public embed page: no credentials, but
  deliberately fragile — if Spotify changes the page, it breaks.
- The UI is currently **Spanish only** (v0.1). Translations welcome.
- Development notes in Spanish: [`android/README.md`](android/README.md).

## Roadmap

- v0.2: polish, translations, whatever breaks first on new YouTube responses
- v0.3+: lyrics and cookies, only if they actually hurt

## Credits

- **Original `albumdown` CLI**: [Karataso/albumdown](https://github.com/Karataso/albumdown)
  by [Karataso](https://github.com/Karataso). This app is a full rewrite of
  that tool in Kotlin — different language, different runtime, but the same
  behavior: folder layout, cleaning rules, tagging and cover handling are ports
  of the Python original (`cleanArtist`, `sanitize`, artist-case unification,
  cover selection… all say so in the source).
- **Android app (`android/`)**: by [Shadow1987-oss](https://github.com/Shadow1987-oss).
- Bundles [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
  (yt-dlp + Python runtime) and `ffmpeg-kit`.

## License

[GPL-3.0](LICENSE) — required by `youtubedl-android`, so the whole app is
GPL-3.0.
