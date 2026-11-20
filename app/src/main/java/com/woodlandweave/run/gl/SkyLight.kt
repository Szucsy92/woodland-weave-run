package com.woodlandweave.run.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A celestial directional light travelling from the eastern horizon, across the forward/northern
 * sky of this game world, to the western horizon. Progress is measured along its own arc,
 * independently of the other light.
 */
class SkyLight(
    val startCyc: Double,
    val lenCyc: Double,
    val minElDeg: Double,
    val maxElDeg: Double,
    val intensityLow: Float,
    val intensityHigh: Float,
    val fade: Double = .08,
) {
    /** 0 at rise, .5 at culmination, 1 at set; -1 while below the horizon. */
    fun progress(cyc: Double): Double {
        val d = ((cyc - startCyc) % 1.0 + 1.0) % 1.0
        return if (d <= lenCyc) d / lenCyc else -1.0
    }

    fun elevationDeg(t: Double): Double = minElDeg + (maxElDeg - minElDeg) * sin(PI * t)
    fun azimuthDeg(t: Double): Double = 90.0 - 180.0 * t
    fun intensity(t: Double): Float = intensityLow + (intensityHigh - intensityLow) * sin(PI * t).toFloat()

    /** Smoothly fades the source at the horizon, where it stops illuminating and casting shadows. */
    fun weight(t: Double): Float {
        if (t < 0.0) return 0f
        fun smooth(x: Double): Double {
            val c = x.coerceIn(0.0, 1.0)
            return c * c * (3.0 - 2.0 * c)
        }
        return (smooth(t / fade) * smooth((1.0 - t) / fade)).toFloat()
    }

    /** Unit vector pointing from the scene towards this light (x=east, y=up, z=south). */
    fun direction(t: Double, out: FloatArray) {
        val el = Math.toRadians(elevationDeg(t))
        val az = Math.toRadians(azimuthDeg(t))
        out[0] = (sin(az) * cos(el)).toFloat()
        out[1] = sin(el).toFloat()
        out[2] = (-cos(az) * cos(el)).toFloat()
    }
}

/**
 * Sun and moon share the Vivid renderer's real directional-light pipeline: one active key light
 * drives material lighting, the actual tree-crown shadow map, ground projections and volumetric shafts.
 * The twilight hand-off never invents an intermediate light position between the setting and
 * rising bodies. Each source fades out at its own horizon; the next source fades in from the opposite horizon.
 */
object SkyLights {
    // Keep the restrained, cinematic 23-degree peak from the previous rotating version.
    // A 9-degree horizon floor keeps the longest ~17 m crown shadows inside the existing 118 m caster reach.
    val SUN = SkyLight(
        startCyc = .25,
        lenCyc = .5,
        minElDeg = 9.0,
        maxElDeg = 23.0,
        intensityLow = 1.75f,
        intensityHigh = 2.55f,
        fade = .035,
    )
    val MOON = SkyLight(
        startCyc = .75,
        lenCyc = .5,
        minElDeg = 9.0,
        maxElDeg = 23.0,
        intensityLow = 1.35f,
        intensityHigh = 1.50f,
        fade = .035,
    )

    /**
     * Writes the dominant source direction to [outDirection]. [outMix] contains the sun colour share
     * (0) and key-light intensity (1). Separate scratch directions are supplied to avoid per-frame allocation.
     */
    fun sample(
        cycle: Double,
        outDirection: FloatArray,
        sunDirection: FloatArray,
        moonDirection: FloatArray,
        outMix: FloatArray,
    ) {
        val sunT = SUN.progress(cycle)
        val moonT = MOON.progress(cycle)

        // Keep both source vectors current even while one is below the horizon.
        SUN.direction(if (sunT >= 0.0) sunT else 0.0, sunDirection)
        MOON.direction(if (moonT >= 0.0) moonT else 0.0, moonDirection)

        val sunWeight = SUN.weight(sunT)
        val moonWeight = MOON.weight(moonT)
        val sunEnergy = if (sunWeight > 0f) SUN.intensity(sunT) * sunWeight else 0f
        val moonEnergy = if (moonWeight > 0f) MOON.intensity(moonT) * moonWeight else 0f
        val totalEnergy = sunEnergy + moonEnergy

        when {
            sunEnergy > moonEnergy && sunEnergy > 1e-5f -> System.arraycopy(sunDirection, 0, outDirection, 0, 3)
            moonEnergy > 1e-5f -> System.arraycopy(moonDirection, 0, outDirection, 0, 3)
            // At the exact hand-off both lights are intentionally at zero. Pick the source that is
            // entering its arc so the shadow camera remains finite and deterministic.
            cycle >= .25 && cycle < .75 -> System.arraycopy(sunDirection, 0, outDirection, 0, 3)
            else -> System.arraycopy(moonDirection, 0, outDirection, 0, 3)
        }

        outMix[0] = if (totalEnergy > 1e-5f) sunEnergy / totalEnergy else if (cycle >= .25 && cycle < .75) 1f else 0f
        // A single shadow map represents one directional source; use the dominant source's energy,
        // not the sum, so twilight never produces an artificial brightness spike.
        outMix[1] = max(sunEnergy, moonEnergy)
    }
}
