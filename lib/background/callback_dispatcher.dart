import 'dart:io';
import 'package:workmanager/workmanager.dart';
import '../services/upload_queue.dart';
import '../services/ffmpeg_service.dart';
import '../models/upload_task.dart';

const String uploadTaskName = "r2_upload_scan";

@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      // Initialize any services that need prefs / storage paths
      await FFmpegService.init();

      final dir = await FFmpegService.getCCTVFolder();
      final files = await dir.list().toList();

      for (final f in files) {
        if (f is File) {
          final path = f.path;
          final fileName = path.split(Platform.pathSeparator).last;
          // Only enqueue .mp4 video files
          if (!fileName.endsWith('.mp4')) continue;
          final objectKey = fileName; // Optionally prefix with camera/date

          // Add to upload queue
          UploadQueue.instance.add(
            UploadTask(localPath: path, objectKey: objectKey),
          );
        }
      }

      // Also check for a pending uploads file written by native service
      try {
        final pendingFile = File('${(await FFmpegService.getCCTVFolder()).path}${Platform.pathSeparator}.pending_uploads.txt');
        if (await pendingFile.exists()) {
          final lines = await pendingFile.readAsLines();
          for (final line in lines) {
            final trimmed = line.trim();
            if (trimmed.isEmpty) continue;
            final f = File(trimmed);
            if (await f.exists()) {
              final name = trimmed.split(Platform.pathSeparator).last;
              UploadQueue.instance.add(UploadTask(localPath: trimmed, objectKey: name));
            }
          }
          // remove the pending file after enqueuing
          try { await pendingFile.delete(); } catch (_) {}
        }
      } catch (e) {
        print('CallbackDispatcher: error reading pending uploads: $e');
      }

      // return true when the background task completed successfully
      return Future.value(true);
    } catch (e) {
      // Keep the task failing so system may retry.
      return Future.value(false);
    }
  });
}
