# 🔍 Troubleshooting Guide

## Problem Diagnosis Flowchart

```
Recording doesn't work?
│
├─ No FFmpeg binary error?
│  ├─ Check: android/app/src/main/assets/ffmpeg exists
│  ├─ Check: FFmpeg file size > 20MB
│  ├─ Fix: Re-run setup_ffmpeg.sh or manual download
│  └─ Rebuild: flutter clean && flutter build apk --release
│
├─ "Empty file created" error?
│  ├─ Check: RTSP URL is correct
│  ├─ Check: Camera is on same network
│  ├─ Check: Can you access camera from computer?
│  ├─ Test: ffmpeg -rtsp_transport tcp -i [URL] -t 5 test.mp4
│  └─ If test fails: camera issue, not app issue
│
├─ Recording starts but never stops?
│  ├─ Check: flutter logs for hanging process
│  ├─ Check: Network connectivity
│  ├─ Fix: Tap "Stop" button again
│  └─ If still stuck: Kill app and check file (may have partial video)
│
├─ "Permission denied" on file creation?
│  ├─ Check: Settings > Apps > CCTV App > Permissions
│  ├─ Check: Storage permission is granted
│  ├─ Fix: Grant "Files and media" permission
│  └─ Restart app
│
└─ App crashes on startup?
   ├─ Check: flutter logs -v | head -50
   ├─ Check: FFmpeg extraction may have failed
   ├─ Fix: Verify FFmpeg binary format (must be ARM64 ELF)
   └─ Rebuild: flutter clean && flutter build apk --release
```

## Detailed Solutions

### 1. "FFmpeg not found in assets" Error

**Symptom:**
```
✗ FFmpeg not available: java.io.IOException: error=2, No such file or directory
```

**Root Cause:**
- FFmpeg binary not in `android/app/src/main/assets/ffmpeg`
- FFmpeg binary wasn't built into the APK

**Diagnosis:**
```bash
# Check if file exists locally
ls -lh android/app/src/main/assets/ffmpeg

# Check if built into APK
unzip -l build/app/outputs/flutter-apk/app-release.apk | grep ffmpeg
```

**Solution:**

**Option A: Use setup script**
```bash
cd cctv_app
./setup_ffmpeg.sh
flutter clean
flutter build apk --release
flutter install --release
```

**Option B: Manual setup**
```bash
# 1. Download FFmpeg
mkdir -p temp_ffmpeg && cd temp_ffmpeg
wget https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz
tar xf ffmpeg-master-latest-linux64-gpl.tar.xz

# 2. Copy ARM64 binary
cp ffmpeg-master-latest-linux64-gpl/bin/ffmpeg \
   ../android/app/src/main/assets/ffmpeg

# 3. Verify
file ../android/app/src/main/assets/ffmpeg
# Should show: ELF 64-bit LSB shared object, ARM aarch64

# 4. Rebuild
cd ..
flutter clean
flutter build apk --release
flutter install --release
```

**Verify Fix:**
```bash
# Logs should show:
flutter logs | grep "FFmpeg extracted"
# ✓ FFmpeg extracted and made executable: /data/data/com.example.cctv_app/files/ffmpeg
```

---

### 2. "Recording created empty file" Error

**Symptom:**
```
✗ FFmpeg created empty file - stream may not be accessible
or
Recording file: /storage/.../recording.mp4 - File exists: true, Size: 0 bytes
```

**Root Cause:**
- RTSP URL is incorrect
- Camera is not accessible from Android device
- Network connectivity issue
- Camera requires authentication

**Diagnosis:**

**Step 1: Test camera from computer**
```bash
# On your computer (Linux/Mac/Windows with FFmpeg)
ffmpeg -rtsp_transport tcp -i rtsp://camera-ip:554/stream -t 5 test.mp4

# This should create a small video file
# If it fails, the problem is camera accessibility, not the app
```

**Step 2: Check network connectivity**
```bash
# On Android device (via ADB)
adb shell

# Inside ADB shell:
ping camera-ip
# Should show: replies, not "unreachable"

ifconfig
# Should show valid IP address on same subnet as camera
```

**Step 3: Check RTSP URL format**
```
Correct format: rtsp://[username:password@]host[:port]/path

Examples:
rtsp://192.168.1.100:554/stream
rtsp://192.168.1.100/stream
rtsp://admin:password@192.168.1.100:554/stream

Common camera URLs:
Hikvision:    rtsp://192.168.x.x:554/Streaming/Channels/101
Uniview:      rtsp://192.168.x.x:554/stream/
Dahua:        rtsp://192.168.x.x:554/stream
TP-Link:      rtsp://192.168.x.x:554/stream0
Generic:      rtsp://192.168.x.x:554/stream
```

**Step 4: Check RTSP authentication**
```bash
# If camera needs login, include credentials
rtsp://admin:password123@192.168.1.100:554/stream

# Get camera credentials from:
# - Camera admin panel (usually 192.168.x.x in browser)
# - Manual camera documentation
# - Settings app on camera itself
```

**Solution:**

**Option A: Verify RTSP URL on computer**
```bash
# This MUST work on your computer before trying on Android
ffmpeg -rtsp_transport tcp -i "YOUR_RTSP_URL" -t 10 test.mp4

# If successful:
# - File should be > 100 KB
# - Should be playable video
# - Then copy URL exactly to Android app
```

**Option B: Check network connectivity on device**
```bash
# Using ADB
adb shell ping camera-ip

# Using Android Terminal app on phone
ping camera-ip

# Should show replies, not "unreachable"
```

**Option C: Debug with detailed logs**
```bash
# Install app with verbose logging
flutter install --release

# Start recording and watch logs in real-time
flutter logs -v | grep -E "RTSPRecorder|ffmpeg"

# Should show FFmpeg command being executed:
# ffmpeg -rtsp_transport tcp -i rtsp://... -t 60 ...
```

**Verify Fix:**
```bash
# After fixing RTSP URL:
# - Recording should create file > 100 KB
# - File should be playable MP4 video
# - flutter logs should show: ✓ Embedded FFmpeg recording successful: 1024KB
```

---

### 3. Recording Hangs (Never Completes)

**Symptom:**
- Tap "Start" → recording starts
- Tap "Stop" → nothing happens
- App becomes unresponsive

**Root Cause:**
- FFmpeg process stuck waiting for stream data
- Network disconnected mid-recording
- Camera stream is very slow
- Bug in process termination

**Diagnosis:**

**Step 1: Check logs**
```bash
flutter logs -v | grep -i "recording\|ffmpeg\|process"

# Look for:
# - Process never reaching "waitFor()"
# - Timeout messages
# - Network errors
```

**Step 2: Kill app and check file**
```bash
# Kill the app
adb shell am force-stop com.example.cctv_app

# Check if partial file was created
adb shell ls -lh /storage/emulated/0/Downloads/CCTV_Recordings/

# If file exists, it may have partial video (corrupted)
```

**Step 3: Monitor process**
```bash
# See if FFmpeg process is actually running
adb shell ps aux | grep ffmpeg

# If process running:
# - FFmpeg still waiting for stream data
# - May need to increase timeout or kill manually
```

**Solution:**

**Option A: Increase timeout** (in MainActivity.kt)
```kotlin
// Current: 35 seconds
val exitCode = recordingProcess.waitFor(35, TimeUnit.SECONDS)

// Change to: 60 seconds
val exitCode = recordingProcess.waitFor(60, TimeUnit.SECONDS)
```

Then rebuild:
```bash
flutter clean && flutter build apk --release
```

**Option B: Force kill if hung**
```bash
# Via ADB
adb shell am force-stop com.example.cctv_app

# Or restart phone if needed
```

**Option C: Test with faster network**
```bash
# If camera is slow:
# - Try connecting phone to same WiFi as camera
# - Try camera's wired connection if available
# - Try lower resolution stream if camera supports it
```

**Verify Fix:**
```bash
# Recording should complete cleanly
# Logs should show: ✓ Process completed: exit code 0
```

---

### 4. "Permission denied" Error

**Symptom:**
```
✗ Permission denied: /storage/emulated/0/Downloads/CCTV_Recordings
or
Error creating directory
```

**Root Cause:**
- Storage permission not granted
- Directory doesn't exist
- Insufficient disk space

**Diagnosis:**

**Step 1: Check permissions**
```bash
# Via ADB
adb shell pm list permissions | grep cctv

# On phone:
Settings → Apps → CCTV App → Permissions → Files and media
```

**Step 2: Check directory**
```bash
# Via ADB
adb shell ls -ld /storage/emulated/0/Downloads/CCTV_Recordings/

# If not exists:
# -rw-r--r--: Directory doesn't exist
# drwxrwx---: Directory exists with proper permissions
```

**Step 3: Check disk space**
```bash
# Via ADB
adb shell df /storage/emulated/0/

# Should show available space (not 100% used)
```

**Solution:**

**Option A: Grant permissions (Android 12+)**
```
1. Open: Settings → Apps → CCTV App → Permissions
2. Tap: "Files and media"
3. Select: "Allow management of all files"
4. Restart app
```

**Option B: Manual permission grant (for all versions)**
```bash
# Via ADB
adb shell pm grant com.example.cctv_app android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.example.cctv_app android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.example.cctv_app android.permission.MANAGE_EXTERNAL_STORAGE

# Restart app
adb shell am force-stop com.example.cctv_app
adb shell am start -n com.example.cctv_app/.MainActivity
```

**Option C: Free up disk space**
```bash
# Check available space
adb shell df -h /storage/emulated/0/

# Delete old recordings
adb shell rm /storage/emulated/0/Downloads/CCTV_Recordings/*.mp4

# Or use phone Settings → Storage → Delete files
```

**Verify Fix:**
```bash
# Permission should now work
# Logs should show: ✓ Recording saved: /storage/.../recording.mp4
```

---

### 5. App Crashes on Startup

**Symptom:**
```
App opens briefly then closes
or
App doesn't start at all
```

**Root Cause:**
- FFmpeg extraction failed
- Corrupted FFmpeg binary
- Kotlin code error
- Permission issue during startup

**Diagnosis:**

**Step 1: Check logs immediately after crash**
```bash
# Clear old logs
adb logcat -c

# Start app
adb shell am start -n com.example.cctv_app/.MainActivity

# Show logs
adb logcat -v brief | grep -E "RTSPRecorder|MainActivity|Exception"

# Look for stack trace in red/ERROR lines
```

**Step 2: Verify FFmpeg file**
```bash
# Check file type
adb shell file /data/data/com.example.cctv_app/files/ffmpeg

# Should show: ELF 64-bit LSB shared object, ARM aarch64

# If shows: ERROR: cannot open (No such file)
# → FFmpeg extraction failed
```

**Step 3: Check APK contents**
```bash
unzip -t build/app/outputs/flutter-apk/app-release.apk | grep ffmpeg

# Should show:
# testing: assets/ffmpeg                    OK
# (not: ERROR or FAILED)
```

**Solution:**

**Option A: Clean rebuild**
```bash
flutter clean
rm -rf build/
flutter build apk --release
```

**Option B: Verify FFmpeg binary**
```bash
# Check local file
file android/app/src/main/assets/ffmpeg

# Should show: ELF 64-bit LSB shared object, ARM aarch64 vXXX

# If shows: ASCII text or similar:
# → Binary is corrupted, re-download

# Re-download correct binary:
./setup_ffmpeg.sh
```

**Option C: Check Kotlin compilation**
```bash
# Check for Kotlin errors during build
flutter build apk --release 2>&1 | grep -i "error\|exception"

# If any errors appear:
# - Fix errors in MainActivity.kt
# - Common: wrong import, syntax error
# - Check QUICKSTART.md for correct code
```

**Verify Fix:**
```bash
# App should start without crashing
# Logs should show: ✓ FFmpeg extracted and made executable
# Recording UI should be visible
```

---

### 6. Video File Not Playable

**Symptom:**
```
Recording created but:
- File won't open in media player
- File shows duration 0:00
- Video is corrupted
```

**Root Cause:**
- Recording interrupted
- Stream disconnected mid-recording
- FFmpeg didn't finish muxing

**Diagnosis:**

**Step 1: Check file size**
```bash
# File should be > 100 KB for 60-second recording
ls -lh /storage/emulated/0/Downloads/CCTV_Recordings/

# Typical sizes:
# 10 seconds @ 1Mbps: 1.25 MB
# 60 seconds @ 1Mbps: 7.5 MB
# 60 seconds @ 2Mbps: 15 MB

# If < 100 KB: incomplete
# If exactly 100 KB: likely app-created dummy file
```

**Step 2: Check file format**
```bash
# Verify it's actually an MP4 file
file /storage/emulated/0/Downloads/CCTV_Recordings/*.mp4

# Should show: ISO Media, MP4 v2 [iso14496-14 compliant]

# If shows: data or similar: corrupted
```

**Step 3: Try to play locally**
```bash
# Copy file to computer
adb pull /storage/emulated/0/Downloads/CCTV_Recordings/recording_*.mp4 .

# Try to open with media player
vlc recording_*.mp4

# If won't play: file is corrupted
# If plays: player issue on phone
```

**Solution:**

**Option A: Check FFmpeg stderr**
```bash
# Enable stderr capture in logs
flutter logs -v | grep -i "ffmpeg\|stdout\|stderr"

# Should show FFmpeg progress:
# frame=1800 fps=30 q=-1 Lsize=N/A

# If shows: "Connection refused" or "Not found"
# → Camera stream was inaccessible
```

**Option B: Re-test with better conditions**
```
- Check network connectivity is stable
- Move phone closer to WiFi router
- Try shorter recording (10 seconds instead of 60)
- Restart camera before recording
```

**Option C: Fix incomplete files**
```bash
# Use FFmpeg to repair corrupt MP4
ffmpeg -i corrupted.mp4 -c:v copy -c:a copy -y fixed.mp4

# If still won't repair: file was never written properly
```

**Verify Fix:**
```bash
# Recording should create playable video
# File should have reasonable size for duration
# Should play in media player
```

---

## Quick Diagnostic Checklist

Before testing, verify all of:

- [ ] FFmpeg binary exists: `android/app/src/main/assets/ffmpeg`
- [ ] Binary is ARM64 ELF: `file android/app/src/main/assets/ffmpeg`
- [ ] Binary size > 20MB: `ls -lh android/app/src/main/assets/ffmpeg`
- [ ] APK built: `ls -lh build/app/outputs/flutter-apk/app-release.apk`
- [ ] APK contains FFmpeg: `unzip -l build/...apk | grep ffmpeg`
- [ ] App installed: `adb shell pm list packages | grep cctv`
- [ ] Camera accessible: `ffmpeg -rtsp_transport tcp -i [URL] -t 5 test.mp4`
- [ ] Phone on same network as camera: `ping camera-ip`
- [ ] Permissions granted: Settings → Apps → CCTV App → Permissions
- [ ] Enough disk space: Settings → Storage → Shows available space

## Testing Workflow

1. **Setup**: Run `./setup_ffmpeg.sh` or manual download
2. **Build**: `flutter clean && flutter build apk --release`
3. **Install**: `flutter install --release`
4. **Test**: 
   - Start recording
   - Monitor: `flutter logs | grep RTSPRecorder`
   - Stop recording
   - Verify file created
   - Play in media player
5. **Debug if needed**: Use above solutions

---

**Remember:**
- ✅ Always test FFmpeg command on computer first
- ✅ Always check `flutter logs` for detailed error info
- ✅ Always verify RTSP URL is correct before blaming app
- ✅ Always check permissions if file operations fail

Good luck! 🚀
