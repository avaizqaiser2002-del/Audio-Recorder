package com.example.audiorecorder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class RecordingService : Service() {

    companion object {
        const val ACTION_START             = "com.example.audiorecorder.START"
        const val ACTION_STOP              = "com.example.audiorecorder.STOP"
        const val ACTION_RECORDING_STARTED = "com.example.audiorecorder.RECORDING_STARTED"
        const val ACTION_SEGMENT_SAVED     = "com.example.audiorecorder.SEGMENT_SAVED"
        const val ACTION_RECORDING_STOPPED = "com.example.audiorecorder.RECORDING_STOPPED"
        const val ACTION_ERROR             = "com.example.audiorecorder.ERROR"

        const val EXTRA_INTERVAL_MINUTES = "interval_minutes"
        const val EXTRA_QUALITY          = "quality"
        const val EXTRA_CHANNELS         = "channels"
        const val EXTRA_PREFIX           = "prefix"
        const val EXTRA_FILE_NAME        = "file_name"
        const val EXTRA_FILE_COUNT       = "file_count"
        const val EXTRA_ERROR_MSG        = "error_msg"

        private const val NOTIF_ID   = 1001
        private const val CHANNEL_ID = "ar_recording_channel"
        private const val TAG        = "RecordingService"
    }

    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs  = 20L * 60 * 1000
    private var quality     = 1
    private var channels    = 2
    private var prefix      = "Recording"
    private var segmentCount = 0
    private var currentFileName = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private val splitRunnable = Runnable { splitRecording() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioRecorder::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                intervalMs   = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, 20).toLong() * 60_000
                quality      = intent.getIntExtra(EXTRA_QUALITY, 1)
                channels     = intent.getIntExtra(EXTRA_CHANNELS, 2)
                prefix       = intent.getStringExtra(EXTRA_PREFIX) ?: "Recording"
                segmentCount = 0

                // Try to launch as foreground service — but don't die if it fails
                // (Realme/ColorOS blocks notifications for sideloaded APKs)
                tryLaunchForeground()
                acquireWakeLock()
                startSegment()
            }
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }

    // ── Core recording ────────────────────────────────────────────────────────

    private fun startSegment() {
        releaseRecorder()

        val fileName = buildFileName()
        currentFileName = fileName
        val filePath = getOutputPath(fileName)

        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }

            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(channels)
                setOutputFile(filePath)

                when (quality) {
                    0 -> { setAudioSamplingRate(22050); setAudioEncodingBitRate(32_000) }
                    1 -> { setAudioSamplingRate(44100); setAudioEncodingBitRate(96_000) }
                    2 -> { setAudioSamplingRate(44100); setAudioEncodingBitRate(192_000) }
                }

                prepare()
                start()
            }

            segmentCount++
            Log.i(TAG, "Segment $segmentCount started → $filePath")
            tryUpdateNotification("Segment $segmentCount | splits every ${intervalMs / 60_000} min")

            // Broadcast success so MainActivity can toggle the button
            sendBroadcast(Intent(ACTION_RECORDING_STARTED).apply {
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_FILE_COUNT, segmentCount)
            })

            handler.postDelayed(splitRunnable, intervalMs)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start segment: ${e.message}")
            broadcastError(e.message ?: "Failed to start recording")
        }
    }

    private fun splitRecording() {
        Log.i(TAG, "Splitting → starting segment ${segmentCount + 1}")
        releaseRecorder()
        startSegment()
        sendBroadcast(Intent(ACTION_SEGMENT_SAVED).apply {
            putExtra(EXTRA_FILE_NAME, currentFileName)
            putExtra(EXTRA_FILE_COUNT, segmentCount)
        })
    }

    private fun stopEverything() {
        handler.removeCallbacks(splitRunnable)
        releaseRecorder()
        releaseWakeLock()
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        Log.i(TAG, "Recording stopped. Total segments: $segmentCount")
    }

    private fun releaseRecorder() {
        try { recorder?.stop() } catch (e: Exception) { Log.w(TAG, "stop(): ${e.message}") }
        try { recorder?.reset(); recorder?.release() } catch (e: Exception) { Log.w(TAG, "release(): ${e.message}") }
        recorder = null
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    private fun getOutputPath(fileName: String): String {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            java.io.File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AudioRecorder")
        } else {
            java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "AudioRecorder"
            )
        }
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, fileName).absolutePath
    }

    private fun buildFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "${prefix}_${ts}.m4a"
    }

    // ── Foreground / notification — all wrapped in try-catch ─────────────────
    // Realme UI (ColorOS) blocks notifications for sideloaded APKs,
    // which can cause startForeground() to throw. We catch and continue
    // so recording still works even without the status bar notification.

    private fun tryLaunchForeground() {
        try {
            val notif = buildNotification("Starting…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground() failed (notification blocked?): ${e.message}")
            // Recording continues — WakeLock will keep the CPU alive
        }
    }

    private fun tryUpdateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notify() failed: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎙 Audio Recorder")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while recording"
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(ACTION_ERROR).apply { putExtra(EXTRA_ERROR_MSG, msg) })
        stopSelf()
    }
}
