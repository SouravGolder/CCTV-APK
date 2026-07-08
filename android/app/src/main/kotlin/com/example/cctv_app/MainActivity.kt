package com.example.cctv_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.util.Log
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

/**
 * CCTV Recording App - Native Flutter + Kotlin + FFmpegKit + Background Service
 *
 * Architecture:
 * Flutter UI → Platform Channel → Kotlin (MainActivity) → RecordingService → FFmpegKit Library
 *
 * This approach:
 * ✓ No Flutter plugin dependencies (no ffmpeg_kit_flutter)
 * ✓ FFmpegKit loaded from Maven Central (io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb)
 * ✓ Modern fork supporting Android 15+ with 16KB page size
 * ✓ Full FFmpeg functionality with progress callbacks
 * ✓ Works on all modern Android devices (API 24+)
 * ✓ Community-maintained and up-to-date
 * ✓ Foreground Service for background recording (continues when app closes)
 *
 * Recording Flow:
 * 1. Flutter calls "startRecording" via MethodChannel
 * 2. Kotlin MainActivity starts RecordingService as foreground service
 * 3. RecordingService executes FFmpeg command asynchronously
 * 4. Recording continues even if app is closed
 * 5. Flutter calls "stopRecording" to cancel recording
 */

import io.flutter.plugin.common.BinaryMessenger

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "RTSPRecorder"
        private const val CHANNEL = "com.example.cctv_app/recorder"
        private const val MAX_RETRIES = 25
        private const val RETRY_DELAY_MS = 300000L  // 5 minutes between retries
        // Expose BinaryMessenger so background service can call Dart when new file created
        var flutterMessenger: BinaryMessenger? = null
    }

    private var isRecording = false
    private var recordingFile: File? = null
    private var activeExecutionId: Long = 0
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Expose messenger for RecordingService
        flutterMessenger = flutterEngine.dartExecutor.binaryMessenger

        // Configure MobileFFmpeg logging
        setupFFmpegLogging()

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> {
                    val rtspUrl = call.argument<String>("rtspUrl")
                    val duration = call.argument<Int>("duration")
                    val folderPath = call.argument<String>("folderPath")
                    startRecording(rtspUrl, duration, folderPath, result)
                }
                "stopRecording" -> {
                    stopRecording(result)
                }
                "getFFmpegVersion" -> {
                    result.success("FFmpegKit 6.1.7")
                }
                else -> result.notImplemented()
            }
        }
    }

    /**
     * Set up FFmpegKit configuration
     */
    private fun setupFFmpegLogging() {
        // FFmpegKit is already configured with default settings
        // Logging will be available through FFmpegSession after execution
        Log.d(TAG, "✓ FFmpegKit initialized")
    }

    /**
     * RecordingService (foreground service)
     * Recording will continue even if the app is closed
     */
    private fun startRecording(
        rtspUrl: String?,
        duration: Int?,
        folderPath: String?,
        result: MethodChannel.Result
    ) {
        if (rtspUrl.isNullOrEmpty()) {
            result.error("INVALID_URL", "RTSP URL is empty", null)
            return
        }

        if (duration == null || duration <= 0) {
            result.error("INVALID_DURATION", "Duration must be greater than 0", null)
            return
        }

        if (folderPath.isNullOrEmpty()) {
            result.error("INVALID_PATH", "Folder path is null", null)
            return
        }

        try {
            // Create the output folder
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }

            // Start the RecordingService as a foreground service
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("action", "START")
                putExtra("rtspUrl", rtspUrl)
                putExtra("duration", duration)
                putExtra("folderPath", folderPath)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            isRecording = true
            Log.d(TAG, "✓ Started RecordingService in foreground")
            result.success("Recording started in background service. Recording continues even if app closes.")

        } catch (e: Exception) {
            isRecording = false
            Log.e(TAG, "✗ Failed to start recording service: ${e.message}", e)
            result.error("RECORDING_ERROR", "Failed to start recording service: ${e.message}", null)
        }
    }

    /**
     * Stop the active recording by stopping the RecordingService
     */
    private fun stopRecording(result: MethodChannel.Result) {
        try {
            if (!isRecording) {
                result.success("No active recording")
                return
            }

            Log.d(TAG, "Stopping recording service...")

            // Stop the RecordingService
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("action", "STOP")
            }
            stopService(serviceIntent)

            isRecording = false
            Log.d(TAG, "✓ Sent stop signal to RecordingService")
            result.success("Recording stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Exception in stopRecording: ${e.message}", e)
            isRecording = false
            result.error("STOP_ERROR", "Failed to stop recording: ${e.message}", null)
        }
    }
}