# YT Downloader - Android App

A simple Android app to download YouTube videos. Pure Kotlin — no Python dependencies.

## Features

- **Paste or Share**: Paste a YouTube URL or share directly from YouTube/browser
- **Quality Selection**: Choose from Best, 1080p, 720p, 480p, or Audio-only
- **Download Progress**: Real-time progress with speed and ETA
- **Background Downloads**: Foreground service with notification progress
- **Material Design 3**: Modern UI with Jetpack Compose and dynamic colors

## How It Works

1. **Share** a YouTube link to the app, or **paste** a URL manually
2. The app fetches video info (title, uploader, duration, available formats)
3. **Select quality** (Best, 1080p, 720p, 480p, or Audio)
4. Tap **Download** — files are saved to `Downloads/YTDownloader/`

## Architecture

- **Kotlin + Jetpack Compose** for the UI
- **NewPipeExtractor** for video metadata and stream extraction (handles YouTube
  signature/n-param deciphering via Rhino)
- **OkHttp** for networking and file downloads
- **MediaStore** (API 29+) / public Downloads dir (API 26–28) for saving files
- **MVVM** with a thin repository layer (`extractor/`, `download/`) feeding a
  ViewModel + StateFlow

> Combined audio+video streams from YouTube top out at ~720p. Higher resolutions
> exist only as separate video-only streams, and this app does not bundle a muxer,
> so quality options only list streams that already include audio (plus an
> audio-only option).

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Steps

1. Clone this repository
2. Open in Android Studio
3. Sync Gradle
4. Build and run on a device or emulator (API 26+)

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

Or download a pre-built APK from the [Actions](../../actions) tab.

## Permissions

- **INTERNET**: Required to fetch video info and download
- **POST_NOTIFICATIONS**: Show download progress notifications
- **FOREGROUND_SERVICE**: Keep downloads running in background

## Project Structure

```
app/src/main/java/com/ytdownloader/app/
├── MainActivity.kt              # Entry point, singleTop share-intent handling
├── YTDownloaderApp.kt           # Application: NewPipe init, notification channel
├── core/
│   └── IntentUrlExtractor.kt    # Pull a YouTube URL out of a launch intent
├── domain/
│   └── Models.kt                # VideoMeta, StreamOption, DownloadProgress, ...
├── extractor/
│   ├── OkHttpDownloader.kt      # NewPipe Downloader backed by OkHttp
│   ├── NewPipeInitializer.kt    # Idempotent NewPipe.init()
│   └── StreamInfoRepository.kt  # Fetch StreamInfo → quality options
├── download/
│   ├── DownloadRepository.kt    # OkHttp streaming + progress StateFlow
│   ├── ProgressReporter.kt      # Speed / ETA formatting
│   ├── OpenFile.kt              # ACTION_VIEW intent for finished files
│   └── media/                   # MediaSink: MediaStore (29+) / legacy (26–28)
├── ui/
│   ├── screens/MainScreen.kt    # Main Compose UI
│   └── theme/Theme.kt           # Material 3 theming
├── viewmodel/
│   └── DownloadViewModel.kt     # UiState + StateFlow
├── service/
│   └── DownloadService.kt       # Foreground download service
└── util/
    └── CrashLog.kt              # In-app crash log
```
