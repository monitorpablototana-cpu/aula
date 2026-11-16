package es.aulaencalma

import kotlin.math.log10
import kotlin.math.roundToInt

/** Pure Kotlin logic, deliberately independent from Android for automated tests. */
object NoiseScale {
    const val MIN_DB = -75.0
    const val MAX_DB = -15.0

    /** Relative microphone power, NOT calibrated dB SPL. */
    fun scoreToDb(score: Int): Double = MIN_DB + score.coerceIn(0, 100) * 0.60
    fun dbToScore(db: Double): Int = (((db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / 0.60)
        .roundToInt()).coerceIn(0, 100)

    fun rmsDb(samples: ShortArray, count: Int): Double {
        if (count <= 0) return MIN_DB
        var total = 0.0
        for (index in 0 until count.coerceAtMost(samples.size)) {
            val sample = samples[index].toDouble() / 32768.0
            total += sample * sample
        }
        val actual = count.coerceAtMost(samples.size)
        if (actual <= 0) return MIN_DB
        val rms = kotlin.math.sqrt(total / actual)
        return if (rms <= 0.00000001) MIN_DB else (20.0 * log10(rms)).coerceIn(MIN_DB, MAX_DB)
    }

    fun suggestedThreshold(calibration: List<Double>): Int {
        if (calibration.isEmpty()) return 65
        val ordered = calibration.sorted()
        val index = ((ordered.size - 1) * 0.80).roundToInt()
        return dbToScore(ordered[index] + 8.0)
    }
}

data class DetectionConfig(
    val thresholdScore: Int,
    val attackMs: Long,
    val releaseMs: Long,
    val cooldownMs: Long,
    val repeatEnabled: Boolean,
    val repeatMs: Long,
    val hysteresisDb: Double = 5.0
)

data class DetectionResult(val alert: Boolean, val description: String, val above: Boolean)

/** Attack and release are duration gates; hysteresis avoids rearming on threshold flicker. */
class NoiseLogic {
    private var aboveSince = -1L
    private var belowSince = -1L
    private var latched = false
    private var lastAlertAt = -1L

    fun reset() {
        aboveSince = -1L
        belowSince = -1L
        latched = false
        // Keep lastAlertAt: calibration/settings changes must not defeat cooldown.
    }

    fun sample(now: Long, db: Double, config: DetectionConfig, suppressed: Boolean = false): DetectionResult {
        val upper = NoiseScale.scoreToDb(config.thresholdScore)
        val above = db >= upper
        if (suppressed) {
            // The app's own warning sound must not count toward attack/release.
            aboveSince = -1L
            belowSince = -1L
            return DetectionResult(false, "Reproduciendo aviso", above)
        }

        if (latched) {
            if (db <= upper - config.hysteresisDb) {
                if (belowSince < 0) belowSince = now
                if (now - belowSince >= config.releaseMs) {
                    latched = false
                    belowSince = -1L
                    aboveSince = -1L
                    return DetectionResult(false, "Aula recuperada", above)
                }
            } else {
                belowSince = -1L
            }
            if (config.repeatEnabled && above && lastAlertAt >= 0 &&
                now - lastAlertAt >= maxOf(config.repeatMs, config.cooldownMs)) {
                lastAlertAt = now
                return DetectionResult(true, "Aviso repetido", above)
            }
            return DetectionResult(false, "Esperando a que baje el ruido", above)
        }

        if (!above) {
            aboveSince = -1L
            return DetectionResult(false, "Ruido dentro del límite", false)
        }
        if (aboveSince < 0) aboveSince = now
        if (now - aboveSince < config.attackMs) {
            return DetectionResult(false, "Ruido elevado · comprobando duración", true)
        }
        if (lastAlertAt >= 0 && now - lastAlertAt < config.cooldownMs) {
            return DetectionResult(false, "Pausa entre avisos", true)
        }
        latched = true
        belowSince = -1L
        lastAlertAt = now
        return DetectionResult(true, "Aviso de ruido", true)
    }
}
