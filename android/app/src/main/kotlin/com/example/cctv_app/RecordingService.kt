package com.example.cctv_app

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import android.os.FileObserver
import io.flutter.plugin.common.MethodChannel
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.LinkedBlockingQueue
import android.content.pm.ServiceInfo

/**
 * Foreground Service for continuous CCTV recording
 * Keeps the app running in background even when user closes the app
 */
class RecordingService : Service() {
    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "cctv_recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RETRIES = 500
        private const val RETRY_DELAY_MS = 5000L  // 5 seconds between retries
    }

    private var isRecording = false
    private var activeExecutionId: Long = 0
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var notificationManager: NotificationManager? = null
    private var recordingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var fileObserver: FileObserver? = null
    private val CHANNEL = "com.example.cctv_app/recorder"
    private var uploadQueue: NativeUploadQueue? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "✓ RecordingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var rtspUrl = intent?.getStringExtra("rtspUrl")
        var duration = intent?.getIntExtra("duration", 60) ?: 60
        var folderPath = intent?.getStringExtra("folderPath")
        val action = intent?.getStringExtra("action") ?: "START"

        Log.d(TAG, "Service command received: $action")

        val prefs = getSharedPreferences("cctv_service_prefs", MODE_PRIVATE)

        when (action) {
            "START" -> {
                if (rtspUrl != null && folderPath != null) {
                    // Save to SharedPreferences
                    prefs.edit().apply {
                        putString("rtspUrl", rtspUrl)
                        putInt("duration", duration)
                        putString("folderPath", folderPath)
                        putString("r2AccountId", intent?.getStringExtra("r2AccountId"))
                        putString("r2BucketName", intent?.getStringExtra("r2BucketName"))
                        putString("r2AccessKey", intent?.getStringExtra("r2AccessKey"))
                        putString("r2SecretKey", intent?.getStringExtra("r2SecretKey"))
                        putString("r2Endpoint", intent?.getStringExtra("r2Endpoint"))
                        putBoolean("isExplicitlyStopped", false)
                        apply()
                    }
                } else {
                    // Restore from SharedPreferences (OS restart case)
                    rtspUrl = prefs.getString("rtspUrl", null)
                    duration = prefs.getInt("duration", 60)
                    folderPath = prefs.getString("folderPath", null)
                }

                val isExplicitlyStopped = prefs.getBoolean("isExplicitlyStopped", true)

                if (!isRecording && !isExplicitlyStopped && rtspUrl != null && folderPath != null) {
                    startRecording(rtspUrl, duration, folderPath)
                }
            }
            "STOP" -> {
                prefs.edit().putBoolean("isExplicitlyStopped", true).apply()
                stopRecording()
            }
        }

        // Return START_STICKY to restart service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved called - keeping service alive in background")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmService.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        releaseWakeLock()
        Log.d(TAG, "✓ RecordingService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CCTV Recording Service",
                // On Android 13+ (and aggressive OEMs), LOW can get hidden/collapsed.
                // DEFAULT helps keep the foreground notification visible.
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Ongoing CCTV recording in background"
            channel.setShowBadge(true)

            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "✓ Notification channel created")
        }
    }

    private fun acquireWakeLock() {
        // Acquire Power WakeLock
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CCTVRecordingService::WakeLock"
            )
            wakeLock?.setReferenceCounted(false)
        }
        wakeLock?.acquire()
        Log.d(TAG, "✓ Power WakeLock acquired")

        // Acquire WiFi Lock
        if (wifiLock == null) {
            val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "CCTVRecordingService::WifiLock"
            )
        }
        wifiLock?.acquire()
        Log.d(TAG, "✓ WiFi Lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
        Log.d(TAG, "✓ Power WakeLock released")

        wifiLock?.release()
        wifiLock = null
        Log.d(TAG, "✓ WiFi Lock released")
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Recording Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun startRecording(rtspUrl: String, duration: Int, folderPath: String) {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        isRecording = true
        acquireWakeLock()
        val notification = createNotification("Starting RTSP recording...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences("cctv_service_prefs", MODE_PRIVATE)
        val r2AccountId = prefs.getString("r2AccountId", "") ?: ""
        val r2BucketName = prefs.getString("r2BucketName", "") ?: ""
        val r2AccessKey = prefs.getString("r2AccessKey", "") ?: ""
        val r2SecretKey = prefs.getString("r2SecretKey", "") ?: ""
        val r2Endpoint = prefs.getString("r2Endpoint", "") ?: ""

        if (r2AccountId.isNotEmpty() && r2BucketName.isNotEmpty() && r2AccessKey.isNotEmpty() && r2SecretKey.isNotEmpty() && r2Endpoint.isNotEmpty()) {
            uploadQueue = NativeUploadQueue(
                r2AccountId,
                r2BucketName,
                r2AccessKey,
                r2SecretKey,
                r2Endpoint
            )
            Log.d(TAG, "✓ NativeUploadQueue initialized successfully")
        } else {
            Log.w(TAG, "⚠ R2 credentials missing. Native uploads will be disabled.")
            uploadQueue = null
        }

        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
            
            // Scan for pending uploads on start
            uploadQueue?.let { queue ->
                try {
                    val folder = File(folderPath)
                    if (folder.exists()) {
                        val files = folder.listFiles()
                        if (files != null) {
                            files.sortBy { it.lastModified() }
                            for (file in files) {
                                if (file.isFile && file.name.endsWith(".mp4") && file.length() >= 1024) {
                                    queue.enqueue(file, file.name)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "✗ Initial directory scan failed: ${e.message}")
                }
            }

            var retryCount = 0

            while (isRecording && retryCount < MAX_RETRIES) {
                try {
                    val folder = File(folderPath)
                    if (!folder.exists()) {
                        folder.mkdirs()
                    }

                    // Start observing folder for completed files so Flutter can upload them
                    try {
                      fileObserver?.stopWatching()
                      fileObserver = object : FileObserver(folderPath, CLOSE_WRITE) {
                          override fun onEvent(event: Int, path: String?) {
                              if (path == null || !path.endsWith(".mp4")) return
                              val full = "$folderPath/$path"
                              Log.d(TAG, "Recording segment complete (CLOSE_WRITE): $full")
                              notifyFlutterNewFile(full)
                              uploadQueue?.enqueue(File(full), path)
                          }
                      }
                      fileObserver?.startWatching()
                        Log.d(TAG, "✓ FileObserver started on: $folderPath")
                    } catch (e: Exception) {
                        Log.w(TAG, "✗ Failed to start FileObserver: ${e.message}")
                    }

                    val outputPathPattern = "$folderPath/recording_%Y%m%d_%H%M%S.mp4"

                    Log.d(TAG, "Starting RTSP segment recording:")
                    Log.d(TAG, "  URL: $rtspUrl")
                    Log.d(TAG, "  Segment Duration: ${duration}s")
                    Log.d(TAG, "  Output Pattern: $outputPathPattern")

                    // Update notification
                    val status = if (retryCount == 0) {
                        "Recording from RTSP stream..."
                    } else {
                        "Recording... (retry ${retryCount + 1}/$MAX_RETRIES)"
                    }
                    notificationManager?.notify(
                        NOTIFICATION_ID,
                        createNotification(status)
                    )

                    if (retryCount > 0) {
                        Log.d(TAG, "⚠ Stream disconnected, retrying (attempt ${retryCount + 1}/$MAX_RETRIES) in ${RETRY_DELAY_MS / 1000}s...")
                        Thread.sleep(RETRY_DELAY_MS)
                    } else {
                        Log.d(TAG, "✓ Executing FFmpeg command (attempt 1/$MAX_RETRIES)...")
                    }

                    val command = "-rtsp_transport tcp -i $rtspUrl -c:v copy -c:a aac -f segment -segment_time $duration -reset_timestamps 1 -strftime 1 -y \"$outputPathPattern\""
                    val session = FFmpegKit.execute(command)
                    val returnCode = session.returnCode

                    Log.d(TAG, "FFmpeg execution completed with return code: $returnCode")

                    if (!isRecording || ReturnCode.isCancel(returnCode)) {
                        Log.d(TAG, "✓ Recording was cancelled (stopped by user)")
                        break
                    } else {
                        // Stream disconnected or ended cleanly. We MUST restart!
                        if (ReturnCode.isSuccess(returnCode)) {
                            Log.w(TAG, "⚠ Stream ended cleanly (EOF), restarting...")
                        } else {
                            Log.w(TAG, "✗ Recording failed with return code: $returnCode, restarting...")
                            val output = session.output
                            if (output.isNotEmpty()) {
                                Log.w(TAG, "FFmpeg output: $output")
                            }
                        }
                         
                        // Calculate duration of the session to reset retry count if it was a stable connection
                        val sessionDurationMs = session.duration ?: 0
                        if (sessionDurationMs > 10000) {
                            Log.d(TAG, "Session was stable for ${sessionDurationMs}ms, resetting retry count")
                            retryCount = 0
                        } else {
                            retryCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "✗ Exception during recording: ${e.message}", e)
                    retryCount++
                }
            }

            // Stop watching when recording ends
            try {
                fileObserver?.stopWatching()
                fileObserver = null
                Log.d(TAG, "✓ FileObserver stopped")
            } catch (e: Exception) {
                Log.w(TAG, "✗ Error stopping FileObserver: ${e.message}")
            }
            if (isRecording && retryCount >= MAX_RETRIES) {
                Log.e(TAG, "✗ Max retries ($MAX_RETRIES) reached. Recording stopped.")
                notificationManager?.notify(
                    NOTIFICATION_ID,
                    createNotification("Max retries reached. Recording stopped.")
                )
            }

            isRecording = false
        }
        recordingThread?.start()

        Log.d(TAG, "✓ Recording service started in foreground")
    }

    private fun notifyFlutterNewFile(filePath: String) {
        if (!MainActivity.isAppInForeground) {
            Log.d(TAG, "App is in background, skipping Flutter notification for new file: $filePath")
            return
        }
        try {
            val messenger = MainActivity.flutterMessenger
            if (messenger != null) {
                // MethodChannel.invokeMethod MUST run on the main (UI) thread.
                // This method is called from the recording background thread.
                Handler(Looper.getMainLooper()).post {
                    try {
                        val channel = MethodChannel(messenger, CHANNEL)
                        val args: HashMap<String, String> = HashMap()
                        args["path"] = filePath
                        channel.invokeMethod("onNewRecording", args)
                        Log.d(TAG, "✓ Notified Flutter of new file: $filePath")
                    } catch (ex: Exception) {
                        Log.e(TAG, "✗ Failed to invoke Flutter method: ${ex.message}", ex)
                    }
                }
            } else {
                Log.w(TAG, "Flutter messenger not available; unable to notify Dart about new file: $filePath")
                // Fallback: persist pending upload so Dart/Workmanager can pick it up later
                try {
                    val f = File(filePath)
                    val parent = f.parentFile
                    if (parent != null) {
                        val pending = File(parent, ".pending_uploads.txt")
                        pending.appendText(filePath + "\n")
                        Log.d(TAG, "✓ Appended pending upload: ${pending.absolutePath}")
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "✗ Failed to persist pending upload: ${ex.message}", ex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Exception notifying Flutter: ${e.message}", e)
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            return
        }

        try {
            Log.d(TAG, "Stopping recording service...")
            FFmpegKit.cancel()
            Log.d(TAG, "✓ Cancel signal sent to FFmpeg")

            releaseWakeLock()
            isRecording = false

            // Wait for thread to finish with timeout
            recordingThread?.join(5000)

            // Update notification
            notificationManager?.notify(
                NOTIFICATION_ID,
                createNotification("Recording stopped")
            )

            Log.d(TAG, "✓ Recording stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error stopping recording: ${e.message}", e)
        }
    }

    private fun notifyFlutterUploadComplete(filePath: String) {
        if (!MainActivity.isAppInForeground) {
            Log.d(TAG, "App is in background, skipping Flutter notification for completed upload: $filePath")
            return
        }
        try {
            val messenger = MainActivity.flutterMessenger
            if (messenger != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val channel = MethodChannel(messenger, CHANNEL)
                        val args: HashMap<String, String> = HashMap()
                        args["path"] = filePath
                        channel.invokeMethod("onUploadComplete", args)
                        Log.d(TAG, "✓ Notified Flutter of completed upload: $filePath")
                    } catch (ex: Exception) {
                        Log.e(TAG, "✗ Failed to invoke Flutter method: ${ex.message}", ex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Exception notifying Flutter: ${e.message}", e)
        }
    }

    inner class NativeUploadQueue(
        private val accountId: String,
        private val bucketName: String,
        private val accessKey: String,
        private val secretKey: String,
        private val endpoint: String
    ) {
        inner class UploadTask(val file: File, val objectKey: String) {
            var attempts = 0
        }

        private val queue = LinkedBlockingQueue<UploadTask>()
        private var isRunning = false
        private val lock = Any()

        fun enqueue(file: File, objectKey: String) {
            synchronized(lock) {
                val exists = queue.any { it.objectKey == objectKey }
                if (exists) {
                    Log.d(TAG, "NativeUploadQueue: skipping duplicate $objectKey")
                    return
                }
                Log.d(TAG, "NativeUploadQueue: enqueued $objectKey")
                queue.offer(UploadTask(file, objectKey))
            }
            startWorker()
        }

        private fun startWorker() {
            synchronized(lock) {
                if (isRunning) return
                isRunning = true
            }

            Thread {
                while (isRecording) {
                    val task = queue.peek() ?: break

                    if (!task.file.exists()) {
                        Log.d(TAG, "NativeUploadQueue: file not found, removing ${task.file.absolutePath}")
                        queue.poll()
                        continue
                    }

                    if (task.file.length() < 1024) {
                        Log.d(TAG, "NativeUploadQueue: file too small (${task.file.length()} bytes), skipping ${task.objectKey}")
                        queue.poll()
                        continue
                    }

                    if (task.attempts >= 10) {
                        Log.d(TAG, "NativeUploadQueue: max attempts reached for ${task.objectKey}, giving up")
                        queue.poll()
                        continue
                    }

                    task.attempts++
                    Log.d(TAG, "NativeUploadQueue: uploading ${task.objectKey} (attempt ${task.attempts}/10)")

                    val ok = uploadToR2(task.file, task.objectKey)
                    if (ok) {
                        Log.d(TAG, "NativeUploadQueue: upload succeeded for ${task.objectKey}, deleting local file")
                        try {
                            task.file.delete()
                            notifyFlutterUploadComplete(task.file.absolutePath)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete file: ${e.message}")
                        }
                        queue.poll()
                    } else {
                        Log.d(TAG, "NativeUploadQueue: upload failed for ${task.objectKey}, will retry in ${5 * task.attempts} seconds")
                        try {
                            Thread.sleep(5000L * task.attempts)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
                synchronized(lock) {
                    isRunning = false
                }
            }.start()
        }

        private fun uploadToR2(file: File, objectKey: String): Boolean {
            val sdfAmz = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val sdfDateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            
            val now = Date()
            val amzDate = sdfAmz.format(now)
            val dateStamp = sdfDateStamp.format(now)
            val contentType = "video/mp4"

            val host = try {
                URI(endpoint).host ?: ""
            } catch (e: Exception) {
                endpoint.replace("https://", "").replace("http://", "").split("/").first()
            }

            val encodedKey = objectKey.split("/").joinToString("/") { encodeUriComponent(it) }
            val canonicalUri = "/${encodeUriComponent(bucketName)}/$encodedKey"

            val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-content-sha256:UNSIGNED-PAYLOAD\nx-amz-date:$amzDate\n"
            val signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date"

            val canonicalRequest = listOf(
                "PUT",
                canonicalUri,
                "",
                canonicalHeaders,
                signedHeaders,
                "UNSIGNED-PAYLOAD"
            ).joinToString("\n")

            val credentialScope = "$dateStamp/auto/s3/aws4_request"
            val canonicalRequestHash = bytesToHex(sha256(canonicalRequest))
            val stringToSign = listOf(
                "AWS4-HMAC-SHA256",
                amzDate,
                credentialScope,
                canonicalRequestHash
            ).joinToString("\n")

            val signingKey = deriveSigningKey(secretKey, dateStamp, "auto", "s3")
            val signature = bytesToHex(hmacSha256(signingKey, stringToSign))
            val authorization = "AWS4-HMAC-SHA256 " +
                    "Credential=$accessKey/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, " +
                    "Signature=$signature"

            var connection: HttpURLConnection? = null
            try {
                val requestUrl = "$endpoint/$bucketName/$encodedKey"
                val url = URL(requestUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                connection.setRequestProperty("x-amz-date", amzDate)
                connection.setRequestProperty("Authorization", authorization)

                java.io.FileInputStream(file).use { fileInputStream ->
                    connection.outputStream.use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        outputStream.flush()
                    }
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    return true
                } else {
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val responseBody = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e(TAG, "✗ Upload failed for $objectKey. Code: $responseCode, Body: $responseBody")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ Exception uploading $objectKey: ${e.message}", e)
                return false
            } finally {
                connection?.disconnect()
            }
        }

        private fun encodeUriComponent(s: String): String {
            return java.net.URLEncoder.encode(s, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
        }

        private fun sha256(data: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data.toByteArray(Charsets.UTF_8))
        }

        private fun hmacSha256(key: ByteArray, data: String): ByteArray {
            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key, "HmacSHA256")
            sha256HMAC.init(secretKey)
            return sha256HMAC.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef".toCharArray()
            val hexArray = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                hexArray[i * 2] = hexChars[v ushr 4]
                hexArray[i * 2 + 1] = hexChars[v and 0x0F]
            }
            return String(hexArray)
        }

        private fun deriveSigningKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
            val kDate = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
            val kRegion = hmacSha256(kDate, region)
            val kService = hmacSha256(kRegion, service)
            return hmacSha256(kService, "aws4_request")
        }
    }
}
