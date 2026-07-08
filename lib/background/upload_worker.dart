import 'dart:io';
import '../services/upload_queue.dart';
import '../models/upload_task.dart';

/// Background upload worker helpers used by UI and background callbacks.
class UploadWorker {
  static Future<void> processFile(String localPath, String objectKey) async {
    final task = UploadTask(localPath: localPath, objectKey: objectKey);
    UploadQueue.instance.add(task);
  }

  /// Scan a directory and enqueue all files for upload.
  static Future<void> processDirectory(Directory dir, {String? prefix}) async {
    final list = await dir.list().toList();
    for (final entry in list) {
      if (entry is File) {
        final name = entry.path.split(Platform.pathSeparator).last;
        if (!name.endsWith('.mp4')) continue;
        final key = prefix == null ? name : '$prefix/$name';
        UploadQueue.instance.add(UploadTask(localPath: entry.path, objectKey: key));
      }
    }
  }
}
