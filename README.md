<div align="center">

# Media Converter

**A free, open-source media downloader and converter for Android powered by yt-dlp, FFmpeg, and gallery-dl.**

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/muhammedasadn/mediaconverter/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://github.com/muhammedasadn/mediaconverter)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE)

</div>

---

## Features

- **1,500+ Supported Sites:** Save videos, audio, playlists, channels, and live streams via `yt-dlp` and `NewPipeExtractor`.
- **Image & Gallery Downloads:** Download posts, profiles, and albums via `gallery-dl` (Instagram, X, Reddit, Pinterest, Tumblr, and more).
- **In-App Search & Playback:** Search and preview streams directly inside the app with Media3 ExoPlayer.
- **Format & Metadata Control:** Pick exact video/audio qualities, containers (`MP4`, `MKV`, `MP3`, `M4A`, `OPUS`, `FLAC`), audio tracks, and edit metadata before saving.
- **On-Device Processing:** Trim/cut video segments, strip sponsor segments via SponsorBlock, embed/split chapters, and embed subtitles.
- **Offline Audio Converter:** Convert local videos into audio formats on-device with FFmpeg.
- **Privacy First:** Zero ads, zero tracking, incognito mode, and built-in HTTP/SOCKS5 proxy + cookie sign-in support.
- **Modern Glass UI:** Material 3 expressive interface with 8 glass styles, dark/light themes, 20+ accent colors, and 10 languages.

## Requirements & Build

- **Android:** Android 7.0+ (API 24+)
- **Local Build:** JDK 17 + Android Studio / Android SDK:
  ```powershell
  .\gradlew.bat :app:assembleDebug
  ```
- **Cloud Build (GitHub Actions):** Push to `main` or trigger **Actions → CI → Run workflow** to build and download the APK artifact without installing Android Studio locally.
- **Prebuilt APKs:** Download directly from [GitHub Releases](https://github.com/muhammedasadn/mediaconverter/releases/latest).

## Documentation

Read [DOCUMENTATION.md](DOCUMENTATION.md) for full details on architecture, how link extraction and processing work, what features require internet vs. work offline, security/privacy, and Android installation troubleshooting.

## Project Checks

```powershell
python tools/strings/check.py resources
python tools/strings/check.py translations
```

## License & Attribution

Licensed under the [GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`).

**Media Converter** (`com.muhammedasadn.mediaconverter`) is maintained by [@muhammedasadn](https://github.com/muhammedasadn) and is based on the open-source [Hazel](https://github.com/SibtainOcn/Hazel) project by [SibtainOcn](https://github.com/SibtainOcn).

Powered by:
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)
- [youtubedl-android](https://github.com/yausername/youtubedl-android)
- [gallery-dl](https://github.com/mikf/gallery-dl)
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [AndroidX Media3](https://github.com/androidx/media)
