package com.woodlandweave.run.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One celestial light (sun or moon) with its own arc and its own on/off window in the day cycle
 * (0 night, .25 dawn, .5 day, .75 dusk, 1 night again).
 *
 * The arc seen from the runner (who heads NORTH): the light rises in the EAST (right, bearing 90),
 * climbs to its highest point straight ahead in the NORTH (bearing 0) and sets in the WEST (left, bearing -90).
 * Height: [minElDeg] at rise and set, [maxElDeg] at the highest point, a sine curve in between.
 * Outside its window the light is OFF (weight 0): it neither lights nor casts shadows.
 */
class SkyLight(
    /** Cycle position where the light comes on (rises). */
    val startCyc: Double,
    /** Length of the on-window in cycle units (.5 = half a day). */
    val lenCyc: Double,
    /** Lowest point (at rise and set) in degrees above the horizon. */
    val minElDeg: Double,
    /** Highest point (middle of the arc) in degrees above the horizon. */
    val maxElDeg: Double,
    /** Intensity at the horizon and at the highest point. */
    val intensityLow: Float,
    val intensityHigh: Float,
    /** Fraction of the arc over which the light fades on after rising / off before setting. */
    val fade: Double = .08,
) {
    /** Progress along the arc: 0 at rise, .5 at the highest point, 1 at set; -1 while the light is off. */
    fun progress(cyc: Double): Double {
        val d = ((cyc - startCyc) % 1.0 + 1.0) % 1.0
        return if (d <= lenCyc) d / lenCyc else -1.0
    }

    fun elevationDeg(th: Double) = minElDeg + (maxElDeg - minElDeg) * sin(PI * th)
    fun azimuthDeg(th: Double) = 90.0 - 180.0 * th
    fun intensity(th: Double) = intensityLow + (intensityHigh - intensityLow) * sin(PI * th).toFloat()

    /** 0 = off, 1 = fully on; eases on after the rise and off before the set. */
    fun weight(th: Double): Float {
        if (th < 0) return 0f
        fun sm(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
        return (sm(th / fade) * sm((1 - th) / fade)).toFloat()
    }

    /** Unit direction towards the light (x east, y up, z south; the runner starts out heading -z). */
    fun direction(th: Double, out: FloatArray) {
        val el = Math.toRadians(elevationDeg(th)); val az = Math.toRadians(azimuthDeg(th))
        out[0] = (sin(az) * cos(el)).toFloat()
        out[1] = sin(el).toFloat()
        out[2] = (-cos(az) * cos(el)).toFloat()
    }
}

/**
 * The two lights of the forest. The sun shines from sunrise (.25) to sunset (.75); the moon comes up as the sun goes down
 * (and goes down as the sun comes up) with a short overlap, while both are still faded to almost nothing at the horizon.
 * Edit the numbers here to move the lowest / highest points.
 */
object SkyLights {
    private const val OVERLAP = .015   // cycle units (about 20 minutes): the moon rises just before the sun has fully set

    // Both lights: 4 degrees at rise / set, 23 degrees at the highest point, exactly in the middle of the arc (due NORTH, straight ahead).
    val SUN = SkyLight(startCyc = .25, lenCyc = .5, minElDeg = 4.0, maxElDeg = 23.0, intensityLow = 2.75f, intensityHigh = 3.45f)
    val MOON = SkyLight(startCyc = .75 - OVERLAP, lenCyc = .5 + 2 * OVERLAP, minElDeg = 4.0, maxElDeg = 23.0, intensityLow = 2.05f, intensityHigh = 2.05f)
}
