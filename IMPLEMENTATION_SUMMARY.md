# CCTV Recording App — Implementation Summary

## Architecture

**Flutter UI → Platform Channel → Kotlin → MobileFFmpeg Library → Video File**

- ✅ Flutter UI for user interaction
- ✅ Platform Channel bridge to native Kotlin
- ✅ MobileFFmpeg AAR library for FFmpeg execution
- ✅ No Flutter FFmpeg plugins (no `ffmpeg_kit_flutter`)
- ✅ No raw binary extraction — native `.so` loading
- ✅ Stable long-term — no Maven dependency issues

## Project Structure

```
cctv_app/
├── 📄 pubspec.yaml               ← Flutter dependencies (no FFmpeg plugins!)
├── 📄 IMPLEMENTATION_SUMMARY.md  ← This file
├── 📄 TROUBLESHOOTING.md         ← Debug guide
│
├── lib/
│   ├── main.dart                 ← App entry point
│   ├── screens/
│   │   ├── login.dart            ← Login screen
│   │   └── home.dart             ← Recording UI
│   └── services/
│       └── ffmpeg_service.dart   ← Platform Channel bridge to Kotlin
│
└── android/
    ├── build.gradle.kts          ← Clean Gradle config (no ffmpeg_kit workarounds)
    └── app/
        ├── build.gradle.kts      ← MobileFFmpeg AAR dependency
        ├── libs/
        │   ├── README.md         ← AAR download instructions
        │   └── mobile-ffmpeg.aar ← ⭐ MobileFFmpeg library (place here)
        └── src/main/
            ├── AndroidManifest.xml
            └── kotlin/com/example/cctv_app/
                └── MainActivity.kt  ← Native FFmpeg execution via MobileFFmpeg API
```

## Key Files

| File | Role | Description |
|------|------|-------------|
| `lib/services/ffmpeg_service.dart` | Platform Channel bridge | Calls Kotlin code via MethodChannel |
| `lib/screens/home.dart` | Recording UI | Start/stop recording, list recordings |
| `lib/screens/login.dart` | Authentication | Simple login screen |
| `MainActivity.kt` | Native recording logic | MobileFFmpeg API execution, callbacks |
| `android/app/libs/mobile-ffmpeg.aar` | FFmpeg library | Native `.so` libraries + Java/Kotlin API |
| `android/app/build.gradle.kts` | App build config | Dependencies, minSdk, ABI filters |

## Recording Flow

```
User taps "Start Recording"
     │
     ├─ Flutter: home.dart
     │  └─ Call: FFmpegService.start(url, duration)
     │
     ├─ Dart: ffmpeg_service.dart
     │  └─ Platform Channel: invokeMethod('startRecording', {...})
     │
     ├─ Kotlin: MainActivity.kt
     │  ├─ Validate parameters
     │  ├─ Build FFmpeg command
     │  └─ FFmpeg.executeAsync(command) { ... }
     │     └─ MobileFFmpeg runs natively (no process spawning)
     │
     └─ Result: Video saved to Downloads/CCTV_Recordings/
```

## FFmpeg Command

```bash
ffmpeg \
  -rtsp_transport tcp \
  -i rtsp://camera-ip:554/stream \
  -t 60 \
  -c:v copy \
  -c:a aac \
  -y \
  /storage/emulated/0/Downloads/CCTV_Recordings/recording_20260420_143022.mp4
```

| Flag | Purpose |
|------|---------|
| `-rtsp_transport tcp` | Use TCP for reliable RTSP transport |
| `-i` | Input RTSP stream URL |
| `-t` | Recording duration in seconds |
| `-c:v copy` | Copy video codec (no re-encoding = fast) |
| `-c:a aac` | Re-encode audio to AAC |
| `-y` | Overwrite output without asking |

## MobileFFmpeg vs Old Approach

| Feature | Old (Raw Binary) | New (MobileFFmpeg) |
|---------|-------------------|---------------------|
| FFmpeg source | Manual binary in `assets/` | AAR library in `libs/` |
| Loading | Extract from APK + chmod | Automatic `.so` loading |
| Execution | `ProcessBuilder` / `Runtime.exec()` | `FFmpeg.executeAsync()` |
| Progress | Manual stderr parsing | `Config.enableStatisticsCallback()` |
| Logging | Manual stdout capture | `Config.enableLogCallback()` |
| Stop/Cancel | `process.destroy()` | `FFmpeg.cancel(executionId)` |
| ABI support | Single architecture | Multi-ABI built-in |
| Reliability | Fragile | Stable |

## Setup Instructions

### 1. Download MobileFFmpeg AAR

Download `mobile-ffmpeg-full-gpl-4.4.aar` from:
- https://github.com/tanersener/mobile-ffmpeg/releases/tag/v4.4

Rename to `mobile-ffmpeg.aar` and place at:
```
android/app/libs/mobile-ffmpeg.aar
```

### 2. Build

```bash
flutter clean
flutter build apk --debug
```

### 3. Verify

```bash
# Check AAR is included
unzip -l build/app/outputs/flutter-apk/app-debug.apk | grep "\.so"

# Expected: libmobileffmpeg.so, libavcodec.so, libavformat.so, etc.
```

### 4. Install & Test

```bash
flutter install --debug
flutter logs -v | grep RTSPRecorder
```

Expected log output:
```
✓ MobileFFmpeg initialized — version: 4.4
✓ Starting RTSP recording via MobileFFmpeg
✓ FFmpeg async execution started (ID: 1)
✓ Recording completed successfully
✓ File saved: .../recording_20260420_143022.mp4 (5120KB)
```

## App Size

| Component | Approximate Size |
|-----------|-----------------|
| Flutter app (base) | ~15MB |
| MobileFFmpeg AAR (full-gpl) | ~30-40MB |
| **Total APK** | **~50-55MB** |

> **Tip:** Use `mobile-ffmpeg-min` (~15MB) instead of `full-gpl` if you only need basic codecs.

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Access RTSP streams over network |
| `WRITE_EXTERNAL_STORAGE` | Save recordings to Downloads |
| `READ_EXTERNAL_STORAGE` | List saved recordings |
| `MANAGE_EXTERNAL_STORAGE` | File manager access on Android 11+ |

## Log Messages Reference

| Message | Meaning |
|---------|---------|
| `✓ MobileFFmpeg initialized` | Library loaded successfully |
| `✓ FFmpeg async execution started` | Recording started |
| `Recording progress: Xs, YKB` | In-progress stats |
| `✓ Recording completed successfully` | FFmpeg finished cleanly |
| `✓ Recording was cancelled` | User stopped recording |
| `✗ Recording failed with return code: N` | FFmpeg error |

## Quick Reference

| Task | Command |
|------|---------|
| Build debug APK | `flutter build apk --debug` |
| Build release APK | `flutter build apk --release` |
| Install on device | `flutter install` |
| View logs | `flutter logs -v \| grep RTSPRecorder` |
| Clean build | `flutter clean && flutter pub get` |
