import 'dart:io';
import '../services/upload_queue.dart';
import '../models/upload_task.dart';

/// Minimal background worker entrypoint. Integrate with Workmanager/WorkManager
/// or other background plugin to call [processFile] when a recording finishes.
class UploadWorker {
  static Future<void> processFile(String localPath, String objectKey) async {
    final task = UploadTask(localPath: localPath, objectKey: objectKey);
    UploadQueue.instance.add(task);
  }
}
