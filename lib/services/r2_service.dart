import 'dart:io';
import 'package:aws_signature_v4/aws_signature_v4.dart';
import 'package:http/http.dart' as http;
import 'r2_config.dart';

class R2Service {
  R2Service._();
  static final instance = R2Service._();

  final _signer = AWSSigV4Signer(
    credentialsProvider: AWSCredentialsProvider(
      AWSCredentials(r2AccessKey, r2SecretKey),
    ),
  );

  final _scope = AWSCredentialScope(region: 'auto', service: AWSService.s3);

  /// Uploads [file] to R2 at the given [objectKey] (e.g. "camera01/2026/.../video.mp4").
  /// Returns true on success.
  Future<bool> uploadFile(File file, String objectKey, {String contentType = 'video/mp4'}) async {
    final uri = Uri.parse('${r2Endpoint.replaceAll(RegExp(r"\/")+ r"\$", '')}/${r2BucketName}/$objectKey');

    final bytes = await file.readAsBytes();

    final request = AWSHttpRequest(
      method: AWSHttpMethod.put,
      uri: uri,
      headers: {
        AWSHeaders.contentType: contentType,
      },
      body: bytes,
    );

    // presign URL and then do a simple HTTP PUT
    final presigned = await _signer.presign(
      request,
      credentialScope: _scope,
      expiresIn: const Duration(minutes: 15),
    );

    final resp = await http.put(presigned, body: bytes, headers: {
      'Content-Type': contentType,
    });

    return resp.statusCode >= 200 && resp.statusCode < 300;
  }
}
