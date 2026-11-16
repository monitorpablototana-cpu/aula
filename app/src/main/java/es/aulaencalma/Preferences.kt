package es.aulaencalma

import android.content.Context
import android.content.SharedPreferences

data class AppSettings(
    val threshold: Int = 65,
    val attackHalfSeconds: Int = 4,
    val releaseSeconds: Int = 3,
    val cooldownSeconds: Int = 15,
    val repeatEnabled: Boolean = true,
    val repeatSeconds: Int = 30,
    val alertVolume: Int = 85,
    val audioUri: String = "",
    val audioName: String = "Pitido integrado"
) {
    fun detector() = DetectionConfig(
        thresholdScore = threshold,
        attackMs = attackHalfSeconds * 500L,
        releaseMs = releaseSeconds * 1_000L,
        cooldownMs = cooldownSeconds * 1_000L,
        repeatEnabled = repeatEnabled,
        repeatMs = repeatSeconds * 1_000L
    )
}

object Preferences {
    private const val STORE = "aula_en_calma_settings"
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun read(context: Context): AppSettings {
        val p = prefs(context)
        return AppSettings(
            threshold = p.getInt("threshold", 65).coerceIn(0, 100),
            attackHalfSeconds = p.getInt("attack", 4).coerceIn(1, 20),
            releaseSeconds = p.getInt("release", 3).coerceIn(1, 15),
            cooldownSeconds = p.getInt("cooldown", 15).coerceIn(5, 120),
            repeatEnabled = p.getBoolean("repeatEnabled", true),
            repeatSeconds = p.getInt("repeat", 30).coerceIn(15, 120),
            alertVolume = p.getInt("volume", 85).coerceIn(0, 100),
            audioUri = p.getString("audioUri", "") ?: "",
            audioName = p.getString("audioName", "Pitido integrado") ?: "Pitido integrado"
        )
    }
}
