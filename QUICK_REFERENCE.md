# 🔥 Developer Quick Reference

## One-Page Cheat Sheet

### Installation (Copy-Paste Commands)

```bash
# Navigate to project
cd cctv_app

# 1. Install FFmpeg (automated)
./setup_ffmpeg.sh

# OR manual:
# - Download from: https://github.com/BtbN/FFmpeg-Builds/releases
# - Copy to: android/app/src/main/assets/ffmpeg
# - Verify: file android/app/src/main/assets/ffmpeg

# 2. Build
flutter clean
flutter build apk --release

# 3. Install on device
flutter install --release

# 4. Monitor logs
flutter logs -v | grep RTSPRecorder
```

### Expected Output

**First Run:**
```
✓ FFmpeg extracted and made executable: /data/data/com.example.cctv_app/files/ffmpeg
```

**Recording Start:**
```
✓ Starting RTSP stream recording from: rtsp://192.168.1.100:554/stream
✓ Attempting embedded FFmpeg recording...
✓ Executing: ffmpeg -rtsp_transport tcp...
```

**Recording Stop:**
```
✓ Recording stopped. File: /storage/emulated/0/.../recording.mp4
✓ Recording saved: recording_20260420_143022.mp4 (5120KB)
```

---

## File Locations

### Development
```
android/app/src/main/assets/ffmpeg              ← Add FFmpeg binary here
android/app/src/main/kotlin/com/example/cctv_app/MainActivity.kt
lib/services/ffmpeg_service.dart
lib/screens/home.dart
```

### Runtime (Android Device)
```
/data/data/com.example.cctv_app/files/ffmpeg   ← Extracted binary
/storage/emulated/0/Downloads/CCTV_Recordings/  ← Saved videos
```

### Build Output
```
build/app/outputs/flutter-apk/app-release.apk  ← Final APK (~50MB)
```

---

## Troubleshooting Commands

### Check FFmpeg in APK
```bash
unzip -l build/app/outputs/flutter-apk/app-release.apk | grep ffmpeg
```

### Verify FFmpeg Binary
```bash
file android/app/src/main/assets/ffmpeg
# Should show: ELF 64-bit LSB shared object, ARM aarch64

ls -lh android/app/src/main/assets/ffmpeg
# Should show: > 20MB
```

### View App Logs
```bash
# All logs
flutter logs

# Only CCTV app logs
flutter logs | grep RTSPRecorder

# Verbose logging
flutter logs -v

# Save to file
flutter logs > app_logs.txt 2>&1
```

### Connect to Device via ADB
```bash
# List connected devices
adb devices

# Install APK directly
adb install build/app/outputs/flutter-apk/app-release.apk

# Uninstall app
adb uninstall com.example.cctv_app

# Open app shell
adb shell

# Inside shell:
ls -la /data/data/com.example.cctv_app/files/
```

### Test RTSP URL on Computer
```bash
# Before testing in app, verify on computer:
ffmpeg -rtsp_transport tcp -i "rtsp://your-camera-ip:554/stream" -t 5 test.mp4

# If this works → URL is correct
# If this fails → camera issue, not app issue
```

---

## Code Organization

### Platform Channel Communication

**Flutter (Dart) → Kotlin:**
```dart
// In lib/services/ffmpeg_service.dart
const platform = MethodChannel('com.example.cctv_app/recorder');

await platform.invokeMethod('startRecording', {
  'rtspUrl': 'rtsp://...',
  'duration': 60,
  'folderPath': '/storage/emulated/0/Downloads/CCTV_Recordings',
});
```

**Kotlin Response:**
```kotlin
// In MainActivity.kt
channel.setMethodCallHandler { call, result ->
    when (call.method) {
        "startRecording" -> {
            recordRtspStream(...)
            result.success(mapOf("status" to "success", "file" to path))
        }
    }
}
```

### FFmpeg Command Building

```kotlin
val command = listOf(
    ffmpegPath,
    "-rtsp_transport", "tcp",
    "-i", rtspUrl,
    "-t", duration.toString(),
    "-c:v", "copy",
    "-c:a", "aac",
    "-q", "5",
    "-y",
    outputFile
)

val process = ProcessBuilder(*command.toTypedArray())
    .redirectErrorStream(true)
    .start()
```

---

## Key Metrics

| Metric | Value |
|--------|-------|
| **App Size** | ~20MB (Flutter + Kotlin) |
| **FFmpeg Size** | ~25MB (ARM64) |
| **Total APK** | ~49.6MB |
| **First Extract Time** | ~3-5 seconds |
| **Recording Start Delay** | 1-2 seconds |
| **RTSP Connection** | 1-3 seconds |
| **Typical Video** | 1-2 MB per 60 seconds |

---

## Permissions Required

### Android Manifest
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

### Runtime (Android 6+)
- WRITE_EXTERNAL_STORAGE
- READ_EXTERNAL_STORAGE

### Android 11+ Additional
- MANAGE_EXTERNAL_STORAGE

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `FFmpeg not available: error=2, No such file or directory` | Binary not in APK | Run `./setup_ffmpeg.sh` and rebuild |
| `Creating empty file` | Stream inaccessible | Test: `ffmpeg -rtsp_transport tcp -i [URL] -t 5 test.mp4` |
| `Recording process stuck` | Network issue | Increase timeout in MainActivity.kt (line ~140) |
| `Permission denied: /storage/...` | No storage permission | Grant in Settings → Apps → CCTV App → Permissions |
| `App crashes on startup` | FFmpeg extraction failed | Check logs: `flutter logs` and verify binary format |

---

## Testing Workflow

```
1. Setup FFmpeg: ./setup_ffmpeg.sh
2. Build: flutter clean && flutter build apk --release
3. Install: flutter install --release
4. Monitor: flutter logs -v | grep RTSPRecorder
5. Test: Enter RTSP URL, tap Start/Stop
6. Verify: Check Downloads/CCTV_Recordings/
7. Playback: Open MP4 in media player
```

---

## Code Changes Reference

### MainActivity.kt Changes
- **Line ~30-40**: Added imports (Context, BufferedReader)
- **Line ~50-60**: Added class variables (ffmpegPath, ffmpegReady)
- **Line ~70-80**: configureFlutterEngine() - added prepareFFmpeg() call
- **Line ~90-140**: New prepareFFmpeg() method
- **Line ~160-180**: recordRtspStream() - priority fallback chain
- **Line ~200-220**: tryRecordWithFFmpeg() - ProcessBuilder execution
- **Line ~280-290**: stopRecording() - file verification

### ffmpeg_service.dart Changes
- **stop()**: Added wait + file verification
- **Error messages**: Enhanced with details

### pubspec.yaml Changes
- None (ffmpeg_kit_flutter removed in prior work)

---

## Performance Optimization Tips

### Reduce APK Size
```bash
# Use app bundle for Play Store (splits by architecture)
flutter build appbundle --release

# Use ProGuard/R8
# Edit: android/app/build.gradle.kts → minifyEnabled = true
```

### Speed Up Build
```bash
# Use multidex
# Edit: android/app/build.gradle.kts → multiDexEnabled = true

# Run specific build phase
flutter build apk --release -v
```

### Monitor Performance
```bash
# Check CPU usage while recording
adb shell top -p $(adb shell pidof com.example.cctv_app) | head -10

# Check memory
adb shell dumpsys meminfo com.example.cctv_app
```

---

## Useful ADB Commands

```bash
# List all packages
adb shell pm list packages | grep cctv

# Force stop app
adb shell am force-stop com.example.cctv_app

# Start app
adb shell am start -n com.example.cctv_app/.MainActivity

# Clear app data
adb shell pm clear com.example.cctv_app

# View app storage
adb shell ls -la /data/data/com.example.cctv_app/

# Pull files from device
adb pull /storage/emulated/0/Downloads/CCTV_Recordings/ .

# Push files to device
adb push ffmpeg /data/local/tmp/
```

---

## FFmpeg Command Reference

### Basic Recording
```bash
ffmpeg -rtsp_transport tcp -i rtsp://url -t 60 output.mp4
```

### With Audio
```bash
ffmpeg -rtsp_transport tcp -i rtsp://url -t 60 -c:a aac output.mp4
```

### Copy Mode (Fastest)
```bash
ffmpeg -rtsp_transport tcp -i rtsp://url -t 60 -c:v copy -c:a copy output.mp4
```

### Custom Bitrate
```bash
ffmpeg -rtsp_transport tcp -i rtsp://url -t 60 -b:v 2M output.mp4
```

### Debug Output
```bash
ffmpeg -rtsp_transport tcp -i rtsp://url -t 60 -loglevel debug output.mp4
```

---

## Resources

| Resource | Link |
|----------|------|
| **FFmpeg Builds** | https://github.com/BtbN/FFmpeg-Builds |
| **FFmpeg Docs** | https://ffmpeg.org |
| **Flutter Platform Channels** | https://flutter.dev/docs/development/platform-integration/platform-channels |
| **Android Developers** | https://developer.android.com |
| **ProcessBuilder Docs** | https://developer.android.com/reference/kotlin/java/lang/ProcessBuilder |

---

## Quick Decision Tree

```
Does the app build?
├─ NO: Check flutter version: flutter --version
├─ YES: Does FFmpeg binary exist?
    ├─ NO: Run ./setup_ffmpeg.sh
    ├─ YES: Does it have correct format?
        ├─ NO (not ELF): Re-download correct binary
        ├─ YES: Does app start?
            ├─ NO: Check logs: flutter logs
            ├─ YES: Does recording create file?
                ├─ NO: Test RTSP URL on computer first
                ├─ YES: Check logs for errors
                    ├─ Empty file: Check camera accessible
                    ├─ Playback error: Check MP4 format
                    └─ Success! ✓
```

---

## Performance Targets

✅ **App startup**: < 2 seconds
✅ **FFmpeg extract**: < 5 seconds (first run only)
✅ **Recording start**: 1-3 seconds
✅ **Recording 60s video**: ~61 seconds
✅ **File save**: < 1 second

---

## Success Checklist

- [ ] FFmpeg binary in `android/app/src/main/assets/ffmpeg`
- [ ] `file` shows: `ELF 64-bit LSB shared object, ARM aarch64`
- [ ] APK builds without errors
- [ ] APK size ~49-50 MB
- [ ] APK contains ffmpeg in assets
- [ ] App installs successfully
- [ ] First run logs show: `✓ FFmpeg extracted`
- [ ] Recording creates MP4 file > 100 KB
- [ ] MP4 file is playable
- [ ] Second recording doesn't hang
- [ ] Error messages are helpful
- [ ] Works without root

---

**Status**: You're ready! 🚀 Start with `./setup_ffmpeg.sh`
