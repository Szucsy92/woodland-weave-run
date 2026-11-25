package com.woodlandweave.run.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Direct replacement for the old AndroidHaptics JavaScript bridge. */
class Haptics(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** A tree hit: a hard thump at full amplitude that tails off (the old 65 ms pulse at 110/255 was barely felt on many phones). */
    fun hit() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(longArrayOf(50, 70), intArrayOf(255, 120), -1)
                else VibrationEffect.createWaveform(longArrayOf(0, 110), -1),
            )
        } catch (_: Exception) {
            // Vibration is optional and must never break a run.
        }
    }

    /** Pattern = alternating on/off milliseconds, e.g. pulse(70, 60, 110). */
    fun pulse(vararg pattern: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || pattern.isEmpty()) return
        try {
            val timings = LongArray(pattern.size + 1)
            val amps = IntArray(pattern.size + 1)
            for (i in pattern.indices) {
                timings[i + 1] = pattern[i].coerceIn(0L, 400L)
                amps[i + 1] = if (i % 2 == 0) 110 else 0
            }
            v.vibrate(
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
                else VibrationEffect.createWaveform(timings, -1),
            )
        } catch (_: Exception) {
            // Vibration is optional and must never break a run.
        }
    }
}
