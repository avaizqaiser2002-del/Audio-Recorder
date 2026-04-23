package com.example.audiorecorder

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.text.InputFilter
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private var isRecording = false

    // UI
    private lateinit var btnStartStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvElapsedTime: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var tvFileCount: TextView
    private lateinit var tvSavePath: TextView
    private lateinit var spinnerInterval: Spinner
    private lateinit var spinnerQuality: Spinner
    private lateinit var spinnerChannels: Spinner
    private lateinit var etPrefix: EditText
    private lateinit var settingsContainer: LinearLayout

    // Elapsed time ticker
    private var elapsedSeconds = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val h = elapsedSeconds / 3600
            val m = (elapsedSeconds % 3600) / 60
            val s = elapsedSeconds % 60
            tvElapsedTime.text = String.format("%02d:%02d:%02d", h, m, s)
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RecordingService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    updateUI(true)
                    tvCurrentFile.text = "📄 ${intent.getStringExtra(RecordingService.EXTRA_FILE_NAME)}"
                    tvFileCount.text = "Segments saved: ${intent.getIntExtra(RecordingService.EXTRA_FILE_COUNT, 0)}"
                }
                RecordingService.ACTION_SEGMENT_SAVED -> {
                    val count = intent.getIntExtra(RecordingService.EXTRA_FILE_COUNT, 0)
                    tvFileCount.text = "Segments saved: $count"
                    tvCurrentFile.text = "📄 ${intent.getStringExtra(RecordingService.EXTRA_FILE_NAME)}"
                    elapsedSeconds = 0 // reset per-segment timer
                }
                RecordingService.ACTION_RECORDING_STOPPED -> {
                    isRecording = false
                    updateUI(false)
                    uiHandler.removeCallbacks(tickRunnable)
                }
                RecordingService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(RecordingService.EXTRA_ERROR_MSG) ?: "Unknown error"
                    Toast.makeText(this@MainActivity, "Error: $msg", Toast.LENGTH_LONG).show()
                    isRecording = false
                    updateUI(false)
                    uiHandler.removeCallbacks(tickRunnable)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSpinners()
        requestRequiredPermissions()

        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_SEGMENT_SAVED)
            addAction(RecordingService.ACTION_RECORDING_STOPPED)
            addAction(RecordingService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        btnStartStop.setOnClickListener {
            if (isRecording) sendStopCommand() else sendStartCommand()
        }
        
        findViewById<Button>(R.id.btnMyRecordings).setOnClickListener {
            startActivity(android.content.Intent(this, FilesActivity::class.java))
        }
    }

    private fun bindViews() {
        btnStartStop    = findViewById(R.id.btnStartStop)
        tvStatus        = findViewById(R.id.tvStatus)
        tvElapsedTime   = findViewById(R.id.tvElapsedTime)
        tvCurrentFile   = findViewById(R.id.tvCurrentFile)
        tvFileCount     = findViewById(R.id.tvFileCount)
        tvSavePath      = findViewById(R.id.tvSavePath)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        spinnerQuality  = findViewById(R.id.spinnerQuality)
        spinnerChannels = findViewById(R.id.spinnerChannels)
        etPrefix        = findViewById(R.id.etPrefix)
        settingsContainer = findViewById(R.id.settingsContainer)

        // Restrict prefix to safe filename chars
        etPrefix.filters = arrayOf(InputFilter { src, _, _, _, _, _ ->
            src.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        })

        tvSavePath.text = "💾 Saved to: Music/AudioRecorder/"
    }

    private fun setupSpinners() {
        spinnerInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("5 min", "10 min", "15 min", "20 min", "30 min", "45 min", "60 min")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerInterval.setSelection(3) // default: 20 min

        spinnerQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Low  – 32 kbps / 22 kHz", "Medium – 96 kbps / 44 kHz", "High – 192 kbps / 44 kHz")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerQuality.setSelection(1) // default: Medium

        spinnerChannels.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Mono", "Stereo")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerChannels.setSelection(1) // default: Stereo
    }

    private fun sendStartCommand() {
        if (!hasPermissions()) { requestRequiredPermissions(); return }

        val intervalMin = when (spinnerInterval.selectedItemPosition) {
            0 -> 5; 1 -> 10; 2 -> 15; 3 -> 20; 4 -> 30; 5 -> 45; 6 -> 60; else -> 20
        }
        val quality  = spinnerQuality.selectedItemPosition  // 0=Low,1=Med,2=High
        val channels = spinnerChannels.selectedItemPosition + 1 // 1=Mono,2=Stereo
        val prefix   = etPrefix.text.toString().trim().ifBlank { "Recording" }

        ContextCompat.startForegroundService(this,
            Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_INTERVAL_MINUTES, intervalMin)
                putExtra(RecordingService.EXTRA_QUALITY, quality)
                putExtra(RecordingService.EXTRA_CHANNELS, channels)
                putExtra(RecordingService.EXTRA_PREFIX, prefix)
            })

        elapsedSeconds = 0
        uiHandler.post(tickRunnable)
    }

    private fun sendStopCommand() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })
    }

    private fun updateUI(recording: Boolean) {
        if (recording) {
            btnStartStop.text       = "⏹  Stop Recording"
            btnStartStop.setBackgroundColor(0xFFB71C1C.toInt())
            tvStatus.text           = "● REC"
            tvStatus.setTextColor(0xFFFF1744.toInt())
            settingsContainer.alpha = 0.4f
            spinnerInterval.isEnabled = false
            spinnerQuality.isEnabled  = false
            spinnerChannels.isEnabled = false
            etPrefix.isEnabled        = false
        } else {
            btnStartStop.text       = "⏺  Start Recording"
            btnStartStop.setBackgroundColor(0xFF1B5E20.toInt())
            tvStatus.text           = "Idle"
            tvStatus.setTextColor(0xFF888888.toInt())
            tvCurrentFile.text      = "—"
            tvElapsedTime.text      = "00:00:00"
            tvFileCount.text        = "Segments saved: 0"
            settingsContainer.alpha = 1f
            spinnerInterval.isEnabled = true
            spinnerQuality.isEnabled  = true
            spinnerChannels.isEnabled = true
            etPrefix.isEnabled        = true
        }
    }

    private fun hasPermissions(): Boolean {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRequiredPermissions() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val denied = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty())
            ActivityCompat.requestPermissions(this, denied.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (results.any { it != PackageManager.PERMISSION_GRANTED })
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
        uiHandler.removeCallbacks(tickRunnable)
    }
}
