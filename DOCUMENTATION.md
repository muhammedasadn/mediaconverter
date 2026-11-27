# Media Converter — Complete Application Documentation

## 1. Application Details

| Property | Value |
|---|---|
| **App Name** | Media Converter |
| **Package ID (`applicationId`)** | `com.muhammedasadn.mediaconverter` |
| **Version** | `1.2.21` (`versionCode` `10221990` base) |
| **Platform** | Native Android (Kotlin + Jetpack Compose + Material 3) |
| **Minimum Android** | Android 7.0 (API 24) |
| **Target / Compile SDK** | Target SDK 35 / Compile SDK 37 |
| **License** | GNU General Public License v3.0 or later (`GPL-3.0-or-later`) |
| **Repository** | `https://github.com/muhammedasadn/mediaconverter` |
| **Supported ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, `universal` |

### Core Bundled Engines
1. **`youtubedl-android` (`0.18.1`)**: Embeds Python 3.12 (`libpython.so` + `libpython.zip.so`), QuickJS (`libqjs.so`), aria2c (`libaria2c.so`), and the `yt-dlp` binary for extracting metadata and downloading video/audio streams from 1,500+ sites.
2. **FFmpeg (`youtubedl-android-ffmpeg` `0.18.1`)**: Runs on-device to merge adaptive video/audio streams, embed subtitles/chapters/metadata/thumbnails, trim/cut video segments, and power the offline video-to-audio converter.
3. **`gallery-dl` (bundled in `assets/gallery-dl.zip`)**: Runs through the embedded Python runtime to inspect and download posts, profiles, carousels, and image searches across image-focused platforms.
4. **NewPipeExtractor (`v0.27.1`)**: Fast, pure-JVM extractor (zero Python startup overhead) used for instant in-app search, stream playback resolution, optional low-latency listing, and `OkHttp` + `MediaMuxer` fallback downloads on supported platforms (YouTube, YouTube Music, SoundCloud, Bandcamp).
5. **AndroidX Media3 ExoPlayer (`1.11.1`)**: Powers the in-app video/audio preview player and the interactive cut/trim timeline.

---

## 2. How the App Works

### Startup & Engine Initialization
1. **UI First (`MainActivity.kt`):** Renders the splash screen and loads persisted theme/accent/glass preferences from Jetpack DataStore (`SettingsRepository`) without blocking the UI thread.
2. **Engine Unpacking (`HazelApp.startLibraryInit()`):** Runs on `Dispatchers.IO` after the first frame draws (or after a 5-second fallback if launched via background notification/shortcut):
   - Unpacks `yt-dlp`, Python 3.12 (`libpython.zip.so`), and FFmpeg into `noBackupFilesDir/youtubedl-android/`.
   - Verifies the live `yt-dlp` binary integrity (`YtDlpUpdater.ensureValidBinary`) and applies any staged updates.
   - Any link read or download invoked before unpacking completes automatically awaits `awaitLibraryReadyBlocking()` in `YtDlpEngine` / `PythonRuntime`, preventing startup race errors.

### Link Reading & Format Extraction (`LinkResolver` → `MediaProbe`)
1. When you paste or share a link (`MainActivity` / `ShareOverlayActivity` / `QuickActionsWidget`):
   - `DownloadViewModel.fetchAll()` validates and normalizes the URL.
   - `InfoCache` checks whether metadata or a playlist listing for that URL was recently cached (skipped if **Incognito Mode** is enabled).
   - `LinkResolver` checks the configured `ListingSource` (`YT_DLP` by default for full format/subtitle/audio-track accuracy, or `NEWPIPE` for fast JVM extraction with automatic fallback to `yt-dlp`).
   - `MediaProbe` runs `yt-dlp --dump-single-json` (or `--flat-playlist --lazy-playlist` for playlists/channels), parses streams into video and audio format ladders, estimates sizes if missing, and renders cards on Home.

### Downloading, Trimming & Storage (`DownloadService` + `StoragePaths` + `MediaStoreHelper`)
1. **Background Execution:** Starting a download launches `DownloadService` (`foregroundServiceType="dataSync"`), keeping the process alive when the app goes to the background and posting Pause / Resume / Cancel notification controls (`DownloadActionReceiver`).
2. **Two-Tier Safe Storage:**
   - Active downloads and FFmpeg processing write to app-private external temp directories (`Android/data/com.muhammedasadn.mediaconverter/files/MediaConverter/`) — no storage permission needed while downloading.
   - Once finished, `MediaStoreHelper` publishes completed files to public shared storage (`Download/MediaConverter/Video`, `Download/MediaConverter/Audio`, `Download/MediaConverter/Gallery`, or `Music/MediaConverter` for converted audio) or to any user-selected SAF folder / SD card (`SdCards`).
3. **Cut / Trim Modes:** Supports quick/keyframe cuts, precise FFmpeg cuts, or **Trim after download** (downloads at full speed, then trims locally on-device with FFmpeg).

---

## 3. Does This App Need Network (Internet)?

**Short answer:** Yes for fetching online media, searching, and updates — **No** for offline conversion, local playback/management, and settings.

| Feature | Requires Network? | How It Works |
|---|---|---|
| **Link Reading & Format Fetching** | **Yes** | Connects directly to target sites via `yt-dlp`, `NewPipeExtractor`, or `gallery-dl` (and optionally your configured HTTP/SOCKS5 proxy). |
| **Video, Audio & Gallery Downloads** | **Yes** | Downloads media streams/files directly from the source site to your device. No third-party backend server is used. |
| **In-App Search & Auto-Suggestions** | **Yes** | Queries YouTube, YouTube Music, SoundCloud, Bandcamp, or image search engines. |
| **Stream Preview Playback** | **Yes** | Streams directly via Media3 ExoPlayer over OkHttp. |
| **Site Sign-In (`CookieWebViewActivity`)** | **Yes** | Opens the site's login page in WebView to extract session cookies locally. |
| **App / `yt-dlp` / `gallery-dl` Updates** | **Yes** | Checks GitHub Releases / PyPI (can be limited to Wi-Fi only or disabled in settings). |
| **Offline Video-to-Audio Converter** | **No (100% Offline)** | Reads local video files on your phone and converts them to MP3, M4A, OPUS, FLAC, etc. using the bundled on-device FFmpeg. |
| **Trim After Download (local cut step)** | **No (after download)** | Runs FFmpeg locally on the already-downloaded file. |
| **Downloads History & Open Files** | **No (100% Offline)** | Reads local DataStore history and opens files stored on your device. |
| **Backup & Restore** | **No (100% Offline)** | Exports/imports settings, history, and queue as local JSON files. |
| **Themes, Glass Look & App Language** | **No (100% Offline)** | Stored locally in Jetpack DataStore; translations are bundled in the APK. |

> **Note:** There is **no central backend server** owned by the app developer. Every network request goes directly from your Android device to the media website you entered (or to GitHub/PyPI when checking for engine/app updates), respecting your **Wi-Fi only** and **HTTP/SOCKS5 Proxy** settings.

---

## 4. Security & Privacy Architecture

- **No Tracking, Ads, or Accounts:** Zero analytics SDKs, zero ad networks, and zero developer servers.
- **TLS Certificate Validation Enforced by Default:** `yt-dlp`, `OkHttp`, `NewPipeDownloader`, and `gallery-dl` verify HTTPS certificates by default (`--no-check-certificates` is **only** passed if you explicitly turn it on in Advanced Settings).
- **Hardened WebViews:** `CookieWebViewActivity` (sign-in) and `PoTokenGenerator` disable `allowFileAccess` and `allowContentAccess`, enable Android Safe Browsing (API 26+), and strip `X-Requested-With` headers where supported.
- **Proxy Isolation (`NetworkProxy`):** When an HTTP or SOCKS5 proxy is enabled, a process-wide `ProxySelector` routes OkHttp, `NewPipeExtractor`, `ExoPlayer`, `Coil`, `yt-dlp`, `gallery-dl`, and WebViews strictly through that proxy without falling back to direct connections.
- **Private Credential Handling:** Saved site cookies live in app-private storage (`DataStore` + private Netscape cookie file), proxy passwords are stripped from exported backups (`NetworkProxy.withoutPassword`), and sensitive tokens are redacted in gallery logs (`GalleryLogs`).
- **Incognito Mode:** Prevents recording URLs, search terms, cached metadata, thumbnails, or download history to disk.
- **RAM-Only Crash Logs:** `CrashLogger` stores error traces only in memory for the current session (cleared when the app closes).

---

## 5. Android Installation & Troubleshooting

### Which APK Should I Download?
From [GitHub Releases](https://github.com/muhammedasadn/mediaconverter/releases/latest):
- **`MediaConverter-vX.Y.Z-arm64-v8a-*.apk` (~85 MB):** Best for almost all physical Android phones (2017 and newer).
- **`MediaConverter-vX.Y.Z-x86_64-*.apk` (~88 MB):** Best for PC Android emulators and Windows Subsystem for Android (BlueStacks, LDPlayer, Nox, Android Studio AVD). Do **not** install `arm64-v8a` on an x86_64 PC emulator, because Android's ARM translation layer cannot execute `libpython.so` directly (`ENOEXEC` / `no closing quote`).
- **`MediaConverter-vX.Y.Z-armeabi-v7a-*.apk`:** For older 32-bit Android phones.
- **`MediaConverter-vX.Y.Z-universal-*.apk` (~224 MB):** Works on every Android phone and PC emulator if you are unsure of your CPU architecture.

### Installation Steps
1. Download the `.apk` directly from the **Releases** page (no zip extraction needed).
2. Tap the `.apk` file in your browser or file manager.
3. If prompted, enable **Allow from this source** ("Install unknown apps").
4. If updating from a different build signature (for example moving between a `debug` build and a keystore-signed `release` build), uninstall the older build first so Android's package signature check passes.

### Permissions Used
- `INTERNET` / `ACCESS_NETWORK_STATE`: Required to fetch media links, download files, and check Wi-Fi status.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: Keeps long downloads running reliably in the background.
- `POST_NOTIFICATIONS` (Android 13+): Shows live download progress and Pause/Resume/Cancel actions.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Optional user-approved exemption so Android does not suspend long background downloads.
- `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (Android 7–10 only): Writes finished files into `Download/MediaConverter` and `Music/MediaConverter` on legacy Android versions (Android 11+ uses scoped `MediaStore` instead).
- `REQUEST_INSTALL_PACKAGES` (`github` flavor only): Allows the optional in-app updater to launch the Android installer for GitHub release APKs.

### Troubleshooting
- **Link fails with `libpython.so` / `no closing quote` / `Wrong APK installed`:** If you are using a PC Android emulator or WSA (BlueStacks, LDPlayer, Nox, AVD), uninstall the `arm64-v8a` APK and install the **`x86_64`** or **`universal`** APK. Android emulators with ARM translation cannot `execve` ARM `libpython.so` directly. On a physical phone, reinstall the matching ABI APK (`arm64-v8a` for modern phones) or the `universal` APK.
- **YouTube asks to sign in / "confirm you're not a bot":** Open **More → Cookies** and sign in to YouTube, or enable **Auto PO Tokens** in **More → Advanced settings**.
- **Downloads pause immediately on mobile data:** Turn off **Wi-Fi only** in Settings, or connect to Wi-Fi.
