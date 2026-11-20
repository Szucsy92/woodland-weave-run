package com.woodlandweave.run.gl

import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared directional key light for the forest.
 *
 * Its direction is fixed in world space so the volumetric shafts, the canopy shadows and the light pools
 * on the ground all describe the same openings, regardless of camera heading or time of day. The clock
 * changes only the key-light colour and intensity in GameRenderer.
 */
object SkyLights {
    const val KEY_AZIMUTH_DEG = 0.0
    const val KEY_ELEVATION_DEG = 23.0

    /** The key light never reaches zero, keeping the shared beam/shadow pattern readable at every hour. */
    const val KEY_INTENSITY_DAY = 2.55f
    const val KEY_INTENSITY_NIGHT = 1.50f

    /** Unit direction towards the fixed key light (x east, y up, z south; the runner starts out heading -z). */
    fun keyDirection(out: FloatArray) {
        val el = Math.toRadians(KEY_ELEVATION_DEG)
        val az = Math.toRadians(KEY_AZIMUTH_DEG)
        out[0] = (sin(az) * cos(el)).toFloat()
        out[1] = sin(el).toFloat()
        out[2] = (-cos(az) * cos(el)).toFloat()
    }
}
