package es.aulaencalma

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Continuously analyses short PCM buffers. No PCM audio is stored or transmitted. */
class NoiseMonitorService : Service() {
    companion object {
        const val ACTION_START = "es.aulaencalma.START"
        const val ACTION_STOP = "es.aulaencalma.STOP"
        const val ACTION_CALIBRATE = "es.aulaencalma.CALIBRATE"
        const val ACTION_STATUS = "es.aulaencalma.STATUS"
        const val EXTRA_SCORE = "score"
        const val EXTRA_STATE = "state"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_CALIBRATION_SCORE = "calibration_score"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "aula_monitoring"
        private const val NOTIFICATION_ID = 37

        @Volatile var isRunning = false
            private set
        @Volatile var latestScore = 0
            private set
        @Volatile var latestState = "Vigilancia detenida"
            private set
        @Volatile var isCalibrating = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var monitorId = 0
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var calibrateRequested = false
    @Volatile private var playing = false
    @Volatile private var ignoreUntilMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var player: AlarmPlayer

    override fun onCreate() {
        super.onCreate()
        player = AlarmPlayer(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Vigilancia del aula", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Notificación mientras se mide el nivel de ruido" })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitor()
            ACTION_CALIBRATE -> if (recording.get()) calibrateRequested = true
            ACTION_STOP -> stopMonitor()
        }
        return START_NOT_STICKY // Never silently restart the microphone after process death.
    }

    private fun notification(): Notification {
        val launch = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = Intent(this, NoiseMonitorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle("Aula en Calma · vigilancia activa")
            .setContentText("Se está midiendo el ruido, incluso con la pantalla apagada")
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_wave), "Detener", stopPending).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startMonitor() {
        if (recording.get()) {
            sendStatus()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendStatus("No hay permiso para usar el micrófono")
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (_: Exception) {
            sendStatus("Android no ha permitido activar el micrófono")
            stopSelf()
            return
        }

        try {
            val (audio, sampleRate) = prepareAudioRecord()
            recorder = audio
            audio.startRecording()

            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }

            val thisMonitor = ++monitorId
            recording.set(true)
            isRunning = true
            latestState = "Midiendo ruido"
            sendStatus()
            worker = Thread({ recordLoop(audio, sampleRate, thisMonitor) }, "AulaNoiseMeter").apply { start() }
        } catch (failure: Exception) {
            sendStatus("No se puede usar el micrófono: ${failure.localizedMessage ?: "error"}")
            if (worker == null) {
                try { recorder?.release() } catch (_: Exception) { }
                recorder = null
            }
            stopMonitor()
        }
    }

    private fun prepareAudioRecord(): Pair<AudioRecord, Int> {
        // Low sample rate saves power; fall back if the device rejects 16 kHz.
        for (rate in intArrayOf(16_000, 44_100, 48_000)) {
            val minSize = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minSize <= 0) continue
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize * 2, rate / 5 * 2)
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate to rate
            } catch (_: Exception) {
                // Try another supported sample rate.
            }
            try { candidate?.release() } catch (_: Exception) { }
        }
        error("No se pudo inicializar el micrófono con ninguna frecuencia compatible")
    }

    private fun recordLoop(audio: AudioRecord, sampleRate: Int, thisMonitor: Int) {
        val engine = NoiseLogic()
        val frame = ShortArray(sampleRate / 10) // 100 ms at the chosen rate.
        var filteredDb: Double? = null
        var lastUpdate = 0L
        var startedCalibrationAt = -1L
        val calibration = ArrayList<Double>(100)
        try {
            while (recording.get() && thisMonitor == monitorId) {
                val count = audio.read(frame, 0, frame.size)
                if (!recording.get() || thisMonitor != monitorId) break
                if (count == 0) continue
                if (count < 0) throw IllegalStateException("Lectura del micrófono interrumpida ($count)")
                val now = SystemClock.elapsedRealtime()
                val raw = NoiseScale.rmsDb(frame, count)
                filteredDb = filteredDb?.let { 0.55 * it + 0.45 * raw } ?: raw
                val db = filteredDb
                latestScore = NoiseScale.dbToScore(db)

                if (calibrateRequested) {
                    calibrateRequested = false
                    startedCalibrationAt = now
                    calibration.clear()
                    isCalibrating = true
                    engine.reset()
                }
                if (startedCalibrationAt >= 0) {
                    calibration.add(db)
                    latestState = "Calibrando el aula (8 segundos)…"
                    if (now - startedCalibrationAt >= 8_000) {
                        startedCalibrationAt = -1L
                        isCalibrating = false
                        val newScore = NoiseScale.suggestedThreshold(calibration)
                        Preferences.prefs(this).edit().putInt("threshold", newScore).apply()
                        engine.reset()
                        // A running update is sent to the Activity, which refreshes its slider.
                        sendStatus(calibrationScore = newScore)
                    }
                } else {
                    val result = engine.sample(
                        now, db, Preferences.read(this).detector(), playing || now < ignoreUntilMs
                    )
                    latestState = result.description
                    if (result.alert && recording.get() && thisMonitor == monitorId) playAlert(thisMonitor)
                }
                if (now - lastUpdate >= 350) {
                    sendStatus()
                    lastUpdate = now
                }
            }
        } catch (failure: Exception) {
            if (recording.get() && thisMonitor == monitorId) {
                handler.post {
                    if (thisMonitor != monitorId) return@post
                    sendStatus("Micrófono interrumpido: ${failure.localizedMessage ?: "error"}")
                    stopMonitor()
                }
            }
        } finally {
            try { audio.stop() } catch (_: Exception) { }
            try { audio.release() } catch (_: Exception) { }
            if (recorder === audio) recorder = null
        }
    }

    private fun playAlert(thisMonitor: Int) {
        playing = true // Block feedback BEFORE posting playback to the UI thread.
        handler.post {
            if (!recording.get() || thisMonitor != monitorId) {
                if (thisMonitor == monitorId) playing = false
                return@post
            }
            player.play(Preferences.read(this)) {
                if (thisMonitor == monitorId) {
                    ignoreUntilMs = SystemClock.elapsedRealtime() + 1_500
                    playing = false
                }
            }
        }
    }

    private fun sendStatus(error: String? = null, calibrationScore: Int? = null) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_SCORE, latestScore)
            .putExtra(EXTRA_STATE, latestState)
            .putExtra(EXTRA_RUNNING, isRunning)
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        if (calibrationScore != null) intent.putExtra(EXTRA_CALIBRATION_SCORE, calibrationScore)
        sendBroadcast(intent)
    }

    private fun stopMonitor() {
        recording.set(false)
        monitorId++
        isRunning = false
        isCalibrating = false
        calibrateRequested = false
        playing = false
        worker = null
        latestState = "Vigilancia detenida"
        player.stop()
        try { recorder?.stop() } catch (_: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        sendStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recording.set(false)
        monitorId++
        isRunning = false
        isCalibrating = false
        player.stop()
        try { recorder?.stop() } catch (_: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }
}
