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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "✓ RecordingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rtspUrl = intent?.getStringExtra("rtspUrl")
        val duration = intent?.getIntExtra("duration", 10) ?: 10
        val folderPath = intent?.getStringExtra("folderPath")
        val action = intent?.getStringExtra("action") ?: "START"

        Log.d(TAG, "Service command received: $action")

        when (action) {
            "START" -> {
                if (!isRecording && rtspUrl != null && folderPath != null) {
                    startRecording(rtspUrl, duration, folderPath)
                }
            }
            "STOP" -> {
                stopRecording()
            }
        }

        // Return START_STICKY to restart service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
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
        startForeground(NOTIFICATION_ID, notification)

        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
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
}
