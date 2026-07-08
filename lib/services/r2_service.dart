import 'dart:io';
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'r2_config.dart';

/// Uploads files to Cloudflare R2 using manual AWS Signature V4.
///
/// Key design decisions:
/// - Uses UNSIGNED-PAYLOAD so the request body is never hashed (critical for
///   large video files on mobile – avoids loading entire file into memory).
/// - Streams the file directly from disk to the network socket via
///   dart:io HttpClient.addStream().
/// - Does NOT use the aws_signature_v4 library because it forcibly overrides
///   x-amz-content-sha256 with its own hash, making UNSIGNED-PAYLOAD impossible.
class R2Service {
  R2Service._();
  static final instance = R2Service._();

  /// Uploads [file] to R2 at the given [objectKey].
  /// Returns true on success, false on failure.
  Future<bool> uploadFile(
    File file,
    String objectKey, {
    String contentType = 'video/mp4',
  }) async {
    final host = Uri.parse(r2Endpoint).host;
    final uri = Uri.parse('$r2Endpoint/$r2BucketName/$objectKey');

    try {
      final fileLength = await file.length();
      print('R2Service: uploading $objectKey ($fileLength bytes) to $uri');

      // ---- AWS Signature V4 (manual) ----
      final now = DateTime.now().toUtc();
      final dateStamp = _formatDateStamp(now);
      final amzDate = _formatAmzDate(now);

      // Headers that will be signed. Keys MUST be lowercase and sorted.
      final headersToSign = <String, String>{
        'content-type': contentType,
        'host': host,
        'x-amz-content-sha256': 'UNSIGNED-PAYLOAD',
        'x-amz-date': amzDate,
      };

      final signedHeaderKeys = headersToSign.keys.toList()..sort();
      final signedHeadersStr = signedHeaderKeys.join(';');

      final canonicalHeaders =
          signedHeaderKeys.map((k) => '$k:${headersToSign[k]}').join('\n') +
              '\n';

      // Path-style URI: /<bucket>/<key>
      final canonicalUri = '/${Uri.encodeComponent(r2BucketName)}/${objectKey.split('/').map(Uri.encodeComponent).join('/')}';

      // Canonical request
      final canonicalRequest = [
        'PUT',
        canonicalUri,
        '', // empty query string
        canonicalHeaders,
        signedHeadersStr,
        'UNSIGNED-PAYLOAD',
      ].join('\n');

      // String to sign
      final credentialScope = '$dateStamp/auto/s3/aws4_request';
      final canonicalRequestHash =
          sha256.convert(utf8.encode(canonicalRequest)).toString();

      final stringToSign = [
        'AWS4-HMAC-SHA256',
        amzDate,
        credentialScope,
        canonicalRequestHash,
      ].join('\n');

      // Derive signing key
      final signingKey =
          _deriveSigningKey(r2SecretKey, dateStamp, 'auto', 's3');
      final signature = Hmac(sha256, signingKey)
          .convert(utf8.encode(stringToSign))
          .toString();

      final authorization = 'AWS4-HMAC-SHA256 '
          'Credential=$r2AccessKey/$credentialScope, '
          'SignedHeaders=$signedHeadersStr, '
          'Signature=$signature';

      // ---- HTTP PUT with streaming body ----
      final client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 30);

      try {
        final request = await client.putUrl(uri);

        // Set signed headers (host is set automatically by HttpClient)
        request.headers.set('content-type', contentType);
        request.headers.set('x-amz-content-sha256', 'UNSIGNED-PAYLOAD');
        request.headers.set('x-amz-date', amzDate);
        request.headers.set('authorization', authorization);
        request.contentLength = fileLength;

        // Stream the file directly from disk – no readAsBytes()
        await request.addStream(file.openRead());
        final response = await request.close();

        final statusCode = response.statusCode;
        final responseBody = await response.transform(utf8.decoder).join();

        if (statusCode >= 200 && statusCode < 300) {
          print('R2Service: ✓ upload succeeded for $objectKey ($statusCode)');
          return true;
        } else {
          print('R2Service: ✗ upload failed $statusCode: $responseBody');
          return false;
        }
      } finally {
        client.close();
      }
    } catch (e, st) {
      print('R2Service: ✗ exception uploading $objectKey: $e\n$st');
      return false;
    }
  }

  // ---- AWS Sig V4 helpers ----

  static List<int> _deriveSigningKey(
    String secretKey,
    String dateStamp,
    String region,
    String service,
  ) {
    final kDate = Hmac(sha256, utf8.encode('AWS4$secretKey'))
        .convert(utf8.encode(dateStamp))
        .bytes;
    final kRegion = Hmac(sha256, kDate).convert(utf8.encode(region)).bytes;
    final kService = Hmac(sha256, kRegion).convert(utf8.encode(service)).bytes;
    return Hmac(sha256, kService).convert(utf8.encode('aws4_request')).bytes;
  }

  static String _formatDateStamp(DateTime dt) =>
      '${dt.year}${dt.month.toString().padLeft(2, '0')}${dt.day.toString().padLeft(2, '0')}';

  static String _formatAmzDate(DateTime dt) =>
      '${_formatDateStamp(dt)}T${dt.hour.toString().padLeft(2, '0')}${dt.minute.toString().padLeft(2, '0')}${dt.second.toString().padLeft(2, '0')}Z';
}
