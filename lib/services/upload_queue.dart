import 'dart:io';
import 'dart:async';
import '../models/upload_task.dart';
import 'r2_service.dart';

class UploadQueue {
  UploadQueue._();
  static final instance = UploadQueue._();

  final _queue = <UploadTask>[];
  bool _running = false;

  void add(UploadTask task) {
    _queue.add(task);
    _run();
  }

  Future<void> _run() async {
    if (_running) return;
    _running = true;
    while (_queue.isNotEmpty) {
      final task = _queue.first;
      try {
        final file = File(task.localPath);
        if (!await file.exists()) {
          _queue.removeAt(0);
          continue;
        }
        final ok = await R2Service.instance.uploadFile(file, task.objectKey);
        if (ok) {
          try { await file.delete(); } catch (_) {}
          _queue.removeAt(0);
        } else {
          task.attempts++;
          // simple backoff: wait before retrying
          await Future.delayed(Duration(seconds: 5 * task.attempts));
        }
      } catch (e) {
        task.attempts++;
        await Future.delayed(Duration(seconds: 5 * task.attempts));
      }
    }
    _running = false;
  }
}
