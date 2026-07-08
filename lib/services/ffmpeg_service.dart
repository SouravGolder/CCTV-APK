import 'dart:io';

import 'package:open_file/open_file.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../background/upload_worker.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:device_info_plus/device_info_plus.dart';

/// FFmpeg Service - Platform Channel bridge to native Kotlin + MobileFFmpeg
///
/// Architecture:
/// Flutter UI → FFmpegService → Platform Channel → Kotlin → MobileFFmpeg → Video File
///
/// The native side uses MobileFFmpeg library (AAR) for stable FFmpeg execution.
/// No Flutter FFmpeg plugins needed — all processing happens natively in Kotlin.
class FFmpegService {
  static const platform = MethodChannel('com.example.cctv_app/recorder');
  static File? currentFile;
  static bool isRecording = false;
  static DateTime? recordingStartTime;
  static String? PUBLIC_CCTV_FOLDER;
  static String? currentRtspUrl;
  static int? segmentTime;
  static const _prefsKeyCustomFolderPath = 'cctv_custom_folder_path';
  static bool _prefsLoaded = false;
  static String? _customFolderPath;

  /// Load persisted settings early (optional but recommended).
  static Future<void> init() async {
    await _ensurePrefsLoaded();
    final custom = (_customFolderPath ?? '').trim();
    if (custom.isNotEmpty) {
      PUBLIC_CCTV_FOLDER = custom;
    }

    // Listen for native notifications about newly created recording files
    try {
      platform.setMethodCallHandler((call) async {
        if (call.method == 'onNewRecording') {
          try {
            final args = call.arguments;
            String? path;
            if (args is Map) {
              path = args['path'] as String?;
            } else if (args is String) {
              path = args;
            }
            if (path != null && path.isNotEmpty) {
              print('✓ Native reported new recording: $path');
              // Compute object key from file name and enqueue upload
              final name = path.split(Platform.pathSeparator).last;
              // Import UploadWorker lazily to avoid cycles
              await UploadWorker.processFile(path, name);
            }
          } catch (e) {
            print('✗ Error handling onNewRecording: $e');
          }
        }
      });
      print('✓ Registered native callback handler for new recordings');
    } catch (e) {
      print('✗ Failed to set method call handler: $e');
    }
  }

  static Future<void> _ensurePrefsLoaded() async {
    if (_prefsLoaded) return;
    final prefs = await SharedPreferences.getInstance();
    _customFolderPath = prefs.getString(_prefsKeyCustomFolderPath);
    _prefsLoaded = true;
  }

  static Future<void> _ensureAndroidStoragePermission() async {
    if (!Platform.isAndroid) return;

    final info = await DeviceInfoPlugin().androidInfo;
    final sdk = info.version.sdkInt;

    // Android 7–12 (API 24–32): WRITE/READ_EXTERNAL_STORAGE via Permission.storage
    if (sdk <= 32) {
      final status = await Permission.storage.status;
      if (status.isGranted) return;
      final req = await Permission.storage.request();
      if (!req.isGranted) {
        throw Exception('Storage permission is required to save recordings.');
      }
      return;
    }

    // Android 13+ (API 33+): direct path access typically requires Manage External Storage
    // when writing to arbitrary public directories. (Otherwise use MediaStore/SAF.)
    final manageStatus = await Permission.manageExternalStorage.status;
    if (manageStatus.isGranted) return;
    final req = await Permission.manageExternalStorage.request();
    if (!req.isGranted) {
      throw Exception(
        'All files access is required to save recordings to a custom folder on this Android version.',
      );
    }
  }

  /// Request whatever storage permission is needed for saving recordings
  /// to the selected folder on the current Android version.
  static Future<void> ensureStoragePermission() async {
    await _ensureAndroidStoragePermission();
  }

  static Future<void> ensureNotificationPermission() async {
    if (!Platform.isAndroid) return;

    final info = await DeviceInfoPlugin().androidInfo;
    final sdk = info.version.sdkInt;
    if (sdk < 33) return; // Android 13+

    final status = await Permission.notification.status;
    if (status.isGranted) return;
    final req = await Permission.notification.request();
    if (!req.isGranted) {
      throw Exception(
        'Notification permission is required to keep background recording active.',
      );
    }
  }

  /// Set a custom folder path (null/empty resets to default).
  static Future<void> setCustomFolderPath(String? folderPath) async {
    final normalized = (folderPath ?? '').trim();
    final prefs = await SharedPreferences.getInstance();
    if (normalized.isEmpty) {
      await prefs.remove(_prefsKeyCustomFolderPath);
      _customFolderPath = null;
      PUBLIC_CCTV_FOLDER = null; // forces recompute on next getCCTVFolder()
      _prefsLoaded = true;
      return;
    }
    await prefs.setString(_prefsKeyCustomFolderPath, normalized);
    _customFolderPath = normalized;
    PUBLIC_CCTV_FOLDER = normalized;
    _prefsLoaded = true;
  }

  /// Get the public CCTV folder (Downloads/CCTV_Recordings)
  static Future<Directory> getCCTVFolder() async {
    try {
      await _ensurePrefsLoaded();

      // If user selected a custom folder, use it.
      final custom = (_customFolderPath ?? '').trim();
      if (custom.isNotEmpty) {
        PUBLIC_CCTV_FOLDER = custom;
        final customDir = Directory(custom);
        if (!await customDir.exists()) {
          await customDir.create(recursive: true);
        }
        return customDir;
      }

      // Android 7 compatibility: prefer public Download folder instead of app-specific external dir.
      if (Platform.isAndroid) {
        final publicDownload = Directory(
          '/storage/emulated/0/Download/CCTV_Recordings',
        );
        try {
          if (!await publicDownload.exists()) {
            await publicDownload.create(recursive: true);
          }
          PUBLIC_CCTV_FOLDER = publicDownload.path;
          return publicDownload;
        } catch (_) {
          // If this fails, fall back to path_provider below.
        }
      }

      // Try to use Downloads directory first to make files visible to file managers
      final downloadsDir = await getDownloadsDirectory();
      if (downloadsDir != null) {
        PUBLIC_CCTV_FOLDER = '${downloadsDir.path}/CCTV_Recordings';
      } else {
        // Fallback to external storage
        final externalDir = await getExternalStorageDirectory();
        if (externalDir != null) {
          PUBLIC_CCTV_FOLDER = '${externalDir.path}/CCTV_Recordings';
        } else {
          throw Exception('Cannot access storage directory');
        }
      }

      final cctvDir = Directory(PUBLIC_CCTV_FOLDER!);
      if (!await cctvDir.exists()) {
        try {
          await cctvDir.create(recursive: true);
          print('✓ CCTV folder created: $PUBLIC_CCTV_FOLDER');
        } catch (e) {
          print('✗ Failed to create CCTV folder: $e');
          throw Exception('Cannot create CCTV folder: $e');
        }
      }

      return cctvDir;
    } catch (e) {
      print('✗ Error getting CCTV folder: $e');
      throw Exception('Failed to get CCTV folder: $e');
    }
  }

  /// Open CCTV folder with file manager
  static Future<void> openCCTVFolder() async {
    try {
      if (PUBLIC_CCTV_FOLDER == null) {
        await getCCTVFolder();
      }
      final result = await OpenFile.open(PUBLIC_CCTV_FOLDER!);
      if (result.type == ResultType.error) {
        print('✗ Cannot open folder: ${result.message}');
        throw Exception('Failed to open folder: ${result.message}');
      }
      print('✓ Folder opened: $PUBLIC_CCTV_FOLDER');
    } catch (e) {
      print('✗ Error opening folder: $e');
      throw Exception('Failed to open folder: $e');
    }
  }

  /// Start recording RTSP stream using native MobileFFmpeg
  static Future<String> start(String rtsp, int time) async {
    try {
      if (rtsp.isEmpty) {
        throw Exception('RTSP URL cannot be empty');
      }

      await ensureNotificationPermission();
      await ensureStoragePermission();
      final dir = await getCCTVFolder();
      currentRtspUrl = rtsp;
      segmentTime = time;
      isRecording = true;
      recordingStartTime = DateTime.now();

      print('✓ Starting continuous RTSP segment recording via MobileFFmpeg');
      print('  URL: $rtsp');
      print('  Segment Duration: ${time}s');
      print('  Output Folder: ${dir.path}');

      // Call native Kotlin → MobileFFmpeg to record RTSP stream
      final result = await platform.invokeMethod('startRecording', {
        'rtspUrl': rtsp,
        'duration': time,
        'folderPath': dir.path,
      });

      print('✓ Native recording started: $result');

      return 'Recording started from RTSP: $rtsp';
    } catch (e) {
      print('✗ Error starting RTSP recording: $e');
      isRecording = false;
      throw Exception('Failed to start RTSP recording: $e');
    }
  }

  /// Stop recording - Cancel native MobileFFmpeg execution
  static Future<String> stop() async {
    try {
      if (!isRecording) {
        return 'No active recording';
      }

      print('✓ Stopping recording...');

      // Call native method to cancel FFmpeg and verify file
      final result = await platform.invokeMethod('stopRecording');
      print('✓ Native stop result: $result');

      isRecording = false;
      final duration = recordingStartTime != null
          ? DateTime.now().difference(recordingStartTime!)
          : Duration.zero;

      print('✓ Recording stopped. Files saved in: $PUBLIC_CCTV_FOLDER');
      print('✓ Total elapsed time: ${duration.inSeconds}s');

      print('✓ Continuous recording session finished.');

      currentFile = null;
      recordingStartTime = null;
      currentRtspUrl = null;
      segmentTime = null;

      return 'Recording stopped successfully';
    } catch (e) {
      print('✗ Error stopping recording: $e');
      isRecording = false;
      throw Exception('Failed to stop recording: $e');
    }
  }

  /// Get FFmpeg version from native side
  static Future<String> getFFmpegVersion() async {
    try {
      final version = await platform.invokeMethod('getFFmpegVersion');
      return version ?? 'Unknown';
    } catch (e) {
      return 'Not available';
    }
  }

  /// Get list of all recordings
  static Future<List<FileSystemEntity>> getRecordings() async {
    try {
      final dir = await getCCTVFolder();
      final files = await dir.list().toList();
      files.sort(
        (a, b) => b.statSync().modified.compareTo(a.statSync().modified),
      );
      return files;
    } catch (e) {
      print('✗ Error getting recordings: $e');
      return [];
    }
  }

  /// Get formatted file size
  static String getFormattedFileSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(2)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  /// Get CCTV folder path
  static String getCCTVFolderPath() {
    final custom = (_customFolderPath ?? '').trim();
    if (custom.isNotEmpty) return custom;
    return PUBLIC_CCTV_FOLDER ?? 'Initializing...';
  }

  /// Delete a recording file
  static Future<void> deleteRecording(String filePath) async {
    try {
      final file = File(filePath);
      if (await file.exists()) {
        await file.delete();
        print('✓ Deleted: $filePath');
      }
    } catch (e) {
      print('✗ Error deleting recording: $e');
      throw Exception('Failed to delete recording: $e');
    }
  }
}
