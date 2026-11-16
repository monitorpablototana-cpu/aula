package es.aulaencalma

import org.junit.Assert.*
import org.junit.Test

class NoiseLogicTest {
    private val conf = DetectionConfig(65, attackMs = 2_000, releaseMs = 3_000,
        cooldownMs = 15_000, repeatEnabled = true, repeatMs = 30_000)
    private val high = NoiseScale.scoreToDb(80)
    private val low = NoiseScale.scoreToDb(35)

    @Test fun briefSpikeDoesNotAlert() {
        val d = NoiseLogic()
        assertFalse(d.sample(0, high, conf).alert)
        assertFalse(d.sample(1_800, high, conf).alert)
        assertFalse(d.sample(2_000, low, conf).alert)
        assertFalse(d.sample(2_500, high, conf).alert)
    }

    @Test fun sustainedNoiseAlertsOnce() {
        val d = NoiseLogic()
        assertFalse(d.sample(100, high, conf).alert)
        assertTrue(d.sample(2_100, high, conf).alert)
        assertFalse(d.sample(3_000, high, conf).alert)
        assertFalse(d.sample(5_000, high, conf).alert)
    }

    @Test fun releasesAfterQuietAndHonorsCooldown() {
        val d = NoiseLogic()
        d.sample(0, high, conf)
        assertTrue(d.sample(2_000, high, conf).alert)
        assertFalse(d.sample(4_000, low, conf).alert)
        assertEquals("Aula recuperada", d.sample(7_000, low, conf).description)
        d.sample(7_100, high, conf)
        assertFalse(d.sample(9_100, high, conf).alert)
        assertTrue(d.sample(17_000, high, conf).alert)
    }

    @Test fun repeatingAlarmRespectsRepeatPeriod() {
        val d = NoiseLogic()
        d.sample(0, high, conf)
        assertTrue(d.sample(2_000, high, conf).alert)
        assertFalse(d.sample(16_000, high, conf).alert)
        assertTrue(d.sample(32_000, high, conf).alert)
    }

    @Test fun suppressPreventsSelfTriggering() {
        val d = NoiseLogic()
        d.sample(0, high, conf)
        assertFalse(d.sample(10_000, high, conf, suppressed = true).alert)
        assertFalse(d.sample(10_100, high, conf).alert)
        assertTrue(d.sample(12_100, high, conf).alert)
    }

    @Test fun hysteresisPreventsPrematureRearming() {
        val d = NoiseLogic()
        d.sample(0, high, conf)
        assertTrue(d.sample(2_000, high, conf).alert)
        val justBelow = NoiseScale.scoreToDb(63) // Below threshold but not 5 dB lower.
        assertFalse(d.sample(5_000, justBelow, conf).alert)
        assertEquals("Esperando a que baje el ruido", d.sample(12_000, justBelow, conf).description)
    }

    @Test fun noRepeatModeOnlyAlertsOnceWhileNoisePersists() {
        val d = NoiseLogic()
        val setting = conf.copy(repeatEnabled = false)
        d.sample(0, high, setting)
        assertTrue(d.sample(2_000, high, setting).alert)
        assertFalse(d.sample(120_000, high, setting).alert)
    }

    @Test fun calibrateUsesPercentileNotSingleOutlier() {
        val scores = List(80) { -60.0 } + List(19) { -55.0 } + listOf(-20.0)
        assertEquals(38, NoiseScale.suggestedThreshold(scores))
    }

    @Test fun calibrationWithQuietBaseline() {
        val score = NoiseScale.suggestedThreshold(List(60) { -55.0 })
        assertEquals(47, score) // -47 dBFS maps to score about 47
    }

    @Test fun scoreRoundtripAndSilence() {
        assertEquals(65, NoiseScale.dbToScore(NoiseScale.scoreToDb(65)))
        assertEquals(-75.0, NoiseScale.rmsDb(ShortArray(100), 100), 0.001)
    }
}
