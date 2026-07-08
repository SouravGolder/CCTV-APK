import 'dart:io';
import 'dart:async';
import '../models/upload_task.dart';
import 'r2_service.dart';

class UploadQueue {
  UploadQueue._();
  static final instance = UploadQueue._();

  static const int _maxAttempts = 10;
  static const int _minFileSizeBytes = 1024; // 1 KB – skip empty/corrupt segments

  final _onQueueChangedController = StreamController<void>.broadcast();
  Stream<void> get onQueueChanged => _onQueueChangedController.stream;

  final _queue = <UploadTask>[];
  bool _running = false;

  void add(UploadTask task) {
    // Prevent duplicate entries for the same objectKey
    if (_queue.any((t) => t.objectKey == task.objectKey)) {
      print('UploadQueue: skipping duplicate ${task.objectKey}');
      return;
    }
    print('UploadQueue: enqueued ${task.objectKey} -> ${task.localPath}');
    _queue.add(task);
    _onQueueChangedController.add(null);
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
          print('UploadQueue: file not found, removing ${task.localPath}');
          _queue.removeAt(0);
          _onQueueChangedController.add(null);
          continue;
        }

        // Skip very small / empty files (likely corrupt or incomplete)
        final fileSize = await file.length();
        if (fileSize < _minFileSizeBytes) {
          print('UploadQueue: file too small ($fileSize bytes), skipping ${task.objectKey}');
          _queue.removeAt(0);
          _onQueueChangedController.add(null);
          continue;
        }

        // Give up after too many attempts
        if (task.attempts >= _maxAttempts) {
          print('UploadQueue: ✗ max attempts ($_maxAttempts) reached for ${task.objectKey}, giving up');
          _queue.removeAt(0);
          _onQueueChangedController.add(null);
          continue;
        }

        print('UploadQueue: uploading ${task.objectKey} (attempt ${task.attempts + 1}/$_maxAttempts, $fileSize bytes)');
        final ok = await R2Service.instance.uploadFile(file, task.objectKey);
        if (ok) {
          print('UploadQueue: ✓ upload succeeded for ${task.objectKey}, deleting local file');
          try { await file.delete(); } catch (_) {}
          _queue.removeAt(0);
          _onQueueChangedController.add(null);
        } else {
          task.attempts++;
          print('UploadQueue: ✗ upload failed for ${task.objectKey}, will retry (attempt ${task.attempts}/$_maxAttempts)');
          await Future.delayed(Duration(seconds: 5 * task.attempts));
        }
      } catch (e) {
        task.attempts++;
        print('UploadQueue: ✗ exception uploading ${task.objectKey}: $e');
        await Future.delayed(Duration(seconds: 5 * task.attempts));
      }
    }
    _running = false;
  }
}
