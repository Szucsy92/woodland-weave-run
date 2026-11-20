package com.woodlandweave.run.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max

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
    val SUN = SkyLight(startCyc = .25, lenCyc = .5, minElDeg = 4.0, maxElDeg = 23.0, intensityLow = 1.75f, intensityHigh = 2.75f)
    val MOON = SkyLight(startCyc = .75 - OVERLAP, lenCyc = .5 + 2 * OVERLAP, minElDeg = 4.0, maxElDeg = 23.0, intensityLow = 1.35f, intensityHigh = 1.55f)

    /**
     * Samples both celestial arcs and writes the shared direction into [outDirection].
     * [outMix] receives sun colour share (index 0) and directional-light intensity (index 1).
     * The renderer uses this one direction for scene lighting, the shadow map, shafts and ground projections.
     */
    fun sample(cycle: Double, outDirection: FloatArray, sunDirection: FloatArray, moonDirection: FloatArray, outMix: FloatArray) {
        val sunT = SUN.progress(cycle)
        val moonT = MOON.progress(cycle)
        val sunW = SUN.weight(sunT)
        val moonW = MOON.weight(moonT)
        val sunEnergy = if (sunW > 0f) SUN.intensity(sunT) * sunW else 0f
        val moonEnergy = if (moonW > 0f) MOON.intensity(moonT) * moonW else 0f

        if (sunEnergy > 0f) SUN.direction(sunT, sunDirection)
        if (moonEnergy > 0f) MOON.direction(moonT, moonDirection)
        val totalEnergy = sunEnergy + moonEnergy
        if (totalEnergy > 1e-5f) {
            if (sunEnergy > 1e-5f && moonEnergy > 1e-5f) {
                // At sunrise/sunset the sun is setting on one horizon while the moon rises on the
                // other. Blend their azimuths across the northern horizon and their elevations,
                // keeping the light within the configured 4..23 degree arc instead of snapping
                // the ground shadows 180 degrees or lifting the light unnaturally to the zenith.
                val moonShare = moonEnergy / totalEnergy
                val az = SUN.azimuthDeg(sunT) + (MOON.azimuthDeg(moonT) - SUN.azimuthDeg(sunT)) * moonShare
                val el = SUN.elevationDeg(sunT) + (MOON.elevationDeg(moonT) - SUN.elevationDeg(sunT)) * moonShare
                val azRad = Math.toRadians(az)
                val elRad = Math.toRadians(el)
                val horizontal = kotlin.math.cos(elRad)
                outDirection[0] = (kotlin.math.sin(azRad) * horizontal).toFloat()
                outDirection[1] = kotlin.math.sin(elRad).toFloat()
                outDirection[2] = (-kotlin.math.cos(azRad) * horizontal).toFloat()
            } else if (sunEnergy > 0f) {
                System.arraycopy(sunDirection, 0, outDirection, 0, 3)
            } else {
                System.arraycopy(moonDirection, 0, outDirection, 0, 3)
            }
        }
        outMix[0] = if (totalEnergy > 1e-5f) sunEnergy / totalEnergy else 0f
        // Use the stronger contribution, rather than adding both lights, to avoid an exposure spike at overlap.
        outMix[1] = max(sunEnergy, moonEnergy)
    }
}
