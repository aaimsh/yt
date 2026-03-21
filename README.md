# YT Downloader - Android App

A simple Android app to download YouTube videos using [yt-dlp](https://github.com/yt-dlp/yt-dlp).

## Features

- **Paste or Share**: Paste a YouTube URL or share directly from YouTube/browser
- **Quality Selection**: Choose from Best, 1080p, 720p, 480p, or Audio-only (MP3)
- **Download Progress**: Real-time progress with speed and ETA
- **Background Downloads**: Foreground service with notification progress
- **Material Design 3**: Modern UI with Jetpack Compose and dynamic colors

## How It Works

1. **Share** a YouTube link to the app, or **paste** a URL manually
2. The app fetches video info (title, uploader, duration)
3. **Select quality** (Best, 1080p, 720p, 480p, or Audio MP3)
4. Tap **Download** - files are saved to `Downloads/YTDownloader/`

## Architecture

- **Kotlin + Jetpack Compose** for the UI
- **Chaquopy** to embed Python and run yt-dlp natively on Android
- **yt-dlp** (Python) handles all video extraction and downloading
- **MVVM** pattern with ViewModel + StateFlow

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Python 3.8+ (Chaquopy uses its own bundled Python)

### Steps

1. Clone this repository
2. Open in Android Studio
3. Sync Gradle (Chaquopy will download yt-dlp automatically via pip)
4. Build and run on a device or emulator (API 26+)

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

- **INTERNET**: Required to download videos
- **POST_NOTIFICATIONS**: Show download progress notifications
- **FOREGROUND_SERVICE**: Keep downloads running in background

## Project Structure

```
app/src/main/
├── java/com/ytdownloader/app/
│   ├── MainActivity.kt          # Entry point, handles share intents
│   ├── YTDownloaderApp.kt       # Application class, notification channels
│   ├── ui/
│   │   ├── screens/MainScreen.kt  # Main Compose UI
│   │   └── theme/Theme.kt         # Material 3 theming
│   ├── viewmodel/
│   │   └── DownloadViewModel.kt   # State management
│   ├── service/
│   │   └── DownloadService.kt     # Foreground download service
│   └── util/
│       ├── VideoInfo.kt           # Data models
│       └── YtDlpWrapper.kt        # Kotlin-Python bridge
└── python/
    └── ytdl_bridge.py             # yt-dlp Python wrapper
```
