package es.aulaencalma

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** User-facing controls; the foreground service performs all microphone work. */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_PERMISSIONS = 120
        private const val REQUEST_AUDIO = 121
    }

    private lateinit var stateView: TextView
    private lateinit var levelView: TextView
    private lateinit var meter: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var previewButton: Button
    private lateinit var audioName: TextView
    private lateinit var threshold: SeekBar
    private lateinit var attack: SeekBar
    private lateinit var release: SeekBar
    private lateinit var cooldown: SeekBar
    private lateinit var repeatDelay: SeekBar
    private lateinit var volume: SeekBar
    private lateinit var repeatSwitch: Switch
    private lateinit var preview: AlarmPlayer
    private var registered = false
    private var loadingSettings = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NoiseMonitorService.ACTION_STATUS) return
            val score = intent.getIntExtra(NoiseMonitorService.EXTRA_SCORE, 0)
            val running = intent.getBooleanExtra(NoiseMonitorService.EXTRA_RUNNING, false)
            val label = intent.getStringExtra(NoiseMonitorService.EXTRA_STATE) ?: "Midiendo ruido"
            showStatus(running, score, label)
            if (intent.hasExtra(NoiseMonitorService.EXTRA_CALIBRATION_SCORE)) {
                val value = intent.getIntExtra(NoiseMonitorService.EXTRA_CALIBRATION_SCORE, 65)
                loadingSettings = true
                threshold.progress = value
                findViewById<TextView>(R.id.tvThresholdValue).text = "$value / 100"
                loadingSettings = false
                Toast.makeText(this@MainActivity, "Calibración completada: umbral $value", Toast.LENGTH_LONG).show()
            }
            intent.getStringExtra(NoiseMonitorService.EXTRA_ERROR)?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val root = findViewById<View>(R.id.root)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }

        preview = AlarmPlayer(this)
        stateView = findViewById(R.id.tvState)
        levelView = findViewById(R.id.tvLevel)
        meter = findViewById(R.id.progressLevel)
        startButton = findViewById(R.id.btnStart)
        stopButton = findViewById(R.id.btnStop)
        calibrationButton = findViewById(R.id.btnCalibrate)
        previewButton = findViewById(R.id.btnPreview)
        audioName = findViewById(R.id.tvAudioName)
        threshold = findViewById(R.id.seekThreshold)
        attack = findViewById(R.id.seekAttack)
        release = findViewById(R.id.seekRelease)
        cooldown = findViewById(R.id.seekCooldown)
        repeatDelay = findViewById(R.id.seekRepeat)
        volume = findViewById(R.id.seekVolume)
        repeatSwitch = findViewById(R.id.switchRepeat)

        setUpSliders()
        loadSettings()

        startButton.setOnClickListener { requestStart() }
        stopButton.setOnClickListener { sendService(NoiseMonitorService.ACTION_STOP) }
        calibrationButton.setOnClickListener {
            if (NoiseMonitorService.isRunning) {
                Toast.makeText(this, "Mantén un ambiente adecuado durante 8 segundos", Toast.LENGTH_LONG).show()
                sendService(NoiseMonitorService.ACTION_CALIBRATE)
            }
        }
        findViewById<Button>(R.id.btnChooseAudio).setOnClickListener { chooseAudio() }
        findViewById<Button>(R.id.btnBuiltin).setOnClickListener {
            Preferences.prefs(this).edit()
                .putString("audioUri", "").putString("audioName", "Pitido integrado").apply()
            loadSettings()
        }
        previewButton.setOnClickListener {
            if (!NoiseMonitorService.isRunning) preview.play(Preferences.read(this)) { }
        }
        findViewById<Button>(R.id.btnIndividual).setOnClickListener {
            preset("Trabajo individual", 57, 4, 3, 15)
        }
        findViewById<Button>(R.id.btnGroup).setOnClickListener {
            preset("Trabajo en grupo", 74, 6, 4, 20)
        }
        findViewById<Button>(R.id.btnExam).setOnClickListener {
            preset("Examen", 48, 3, 2, 15)
        }
        showStatus(false, 0, "Vigilancia detenida")
    }

    private fun setUpSliders() {
        bind(threshold, R.id.tvThresholdValue, { "$it / 100" }) {
            Preferences.prefs(this).edit().putInt("threshold", it).apply()
        }
        bind(attack, R.id.tvAttackValue, { formatHalfSeconds(it + 1) }) {
            Preferences.prefs(this).edit().putInt("attack", it + 1).apply()
        }
        bind(release, R.id.tvReleaseValue, { "${it + 1} s" }) {
            Preferences.prefs(this).edit().putInt("release", it + 1).apply()
        }
        bind(cooldown, R.id.tvCooldownValue, { "${(it + 1) * 5} s" }) {
            Preferences.prefs(this).edit().putInt("cooldown", (it + 1) * 5).apply()
        }
        bind(repeatDelay, R.id.tvRepeatValue, { "Cada ${15 + it * 5} s" }) {
            Preferences.prefs(this).edit().putInt("repeat", 15 + it * 5).apply()
        }
        bind(volume, R.id.tvVolumeValue, { "$it %" }) {
            Preferences.prefs(this).edit().putInt("volume", it).apply()
        }
        repeatSwitch.setOnCheckedChangeListener { _, checked ->
            if (!loadingSettings) {
                Preferences.prefs(this).edit().putBoolean("repeatEnabled", checked).apply()
            }
            repeatDelay.isEnabled = checked
        }
    }

    private fun bind(bar: SeekBar, valueId: Int, display: (Int) -> String, save: (Int) -> Unit) {
        val label = findViewById<TextView>(valueId)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = display(progress)
                if (fromUser && !loadingSettings) save(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })
    }

    private fun loadSettings() {
        val settings = Preferences.read(this)
        loadingSettings = true
        threshold.progress = settings.threshold
        attack.progress = settings.attackHalfSeconds - 1
        release.progress = settings.releaseSeconds - 1
        cooldown.progress = settings.cooldownSeconds / 5 - 1
        repeatDelay.progress = (settings.repeatSeconds - 15) / 5
        volume.progress = settings.alertVolume
        repeatSwitch.isChecked = settings.repeatEnabled
        repeatDelay.isEnabled = settings.repeatEnabled
        audioName.text = settings.audioName
        loadingSettings = false
    }

    private fun formatHalfSeconds(halfSeconds: Int): String {
        return if (halfSeconds % 2 == 0) "${halfSeconds / 2},0 s"
        else "${halfSeconds / 2},5 s"
    }

    private fun preset(label: String, threshold: Int, attack: Int, release: Int, cooldown: Int) {
        Preferences.prefs(this).edit()
            .putInt("threshold", threshold)
            .putInt("attack", attack)
            .putInt("release", release)
            .putInt("cooldown", cooldown)
            .apply()
        loadSettings()
        Toast.makeText(this, "$label: configuración cargada", Toast.LENGTH_SHORT).show()
    }

    private fun requestStart() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else startMonitoring()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startMonitoring()
            } else {
                Toast.makeText(this, "Sin permiso de micrófono no es posible vigilar el aula", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoring() {
        try {
            val intent = Intent(this, NoiseMonitorService::class.java).setAction(NoiseMonitorService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (failure: Exception) {
            Toast.makeText(this, "No se ha podido iniciar: ${failure.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendService(action: String) {
        try {
            startService(Intent(this, NoiseMonitorService::class.java).setAction(action))
        } catch (failure: Exception) {
            Toast.makeText(this, "No se pudo enviar la orden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_AUDIO) }
        catch (_: Exception) {
            Toast.makeText(this, "No hay un selector de archivos disponible", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses system document picker, supporting Android 8 onwards")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var filename = "Audio personalizado"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) filename = cursor.getString(0) ?: filename
            }
            Preferences.prefs(this).edit()
                .putString("audioUri", uri.toString())
                .putString("audioName", filename)
                .apply()
            loadSettings()
            Toast.makeText(this, "Audio seleccionado: $filename", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo guardar el acceso al archivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun showStatus(running: Boolean, score: Int, status: String) {
        stateView.text = status
        stateView.setTextColor(
            if (!running) Color.parseColor("#586C75")
            else if (score >= Preferences.read(this).threshold) Color.parseColor("#C94F4D")
            else Color.parseColor("#176B68")
        )
        levelView.text = if (running) "$score / 100" else "— / 100"
        meter.progress = if (running) score else 0
        meter.progressTintList = ColorStateList.valueOf(
            if (!running || score < Preferences.read(this).threshold) Color.parseColor("#176B68")
            else Color.parseColor("#C94F4D")
        )
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        calibrationButton.isEnabled = running && !NoiseMonitorService.isCalibrating
        previewButton.isEnabled = !running
    }

    override fun onStart() {
        super.onStart()
        if (!registered) {
            val filter = IntentFilter(NoiseMonitorService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(stateReceiver, filter)
            registered = true
        }
        loadSettings()
        showStatus(NoiseMonitorService.isRunning, NoiseMonitorService.latestScore, NoiseMonitorService.latestState)
    }

    override fun onStop() {
        if (registered) {
            unregisterReceiver(stateReceiver)
            registered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        preview.stop()
        super.onDestroy()
    }
}
