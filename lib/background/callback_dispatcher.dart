import 'dart:io';
import 'package:workmanager/workmanager.dart';
import '../services/upload_queue.dart';
import '../services/r2_service.dart';
import '../services/ffmpeg_service.dart';

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
          final objectKey = fileName; // Optionally prefix with camera/date

          // Add to upload queue
          UploadQueue.instance.add(
            UploadTask(localPath: path, objectKey: objectKey),
          );
        }
      }

      // return true when the background task completed successfully
      return Future.value(true);
    } catch (e) {
      // Keep the task failing so system may retry.
      return Future.value(false);
    }
  });
}
