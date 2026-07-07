class UploadTask {
  final String localPath;
  final String objectKey;
  int attempts;

  UploadTask({required this.localPath, required this.objectKey, this.attempts = 0});
}
