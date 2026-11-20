package com.woodlandweave.run.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SkyLightTest {
    @Test fun sunMovesEastToNorthToWestAndChangesElevation() {
        val rise = FloatArray(3)
        val noon = FloatArray(3)
        val set = FloatArray(3)
        SkyLights.SUN.direction(0.0, rise)
        SkyLights.SUN.direction(0.5, noon)
        SkyLights.SUN.direction(1.0, set)

        assertTrue("sun rises in the east", rise[0] > 0.99f)
        assertTrue("sun is highest at noon", noon[1] > rise[1])
        assertTrue("sun sets in the west", set[0] < -0.99f)
        assertEquals("noon azimuth points straight ahead", 0f, noon[0], 0.001f)
    }

    @Test fun sharedLightDirectionAndIntensityStayValidAcrossFullCycle() {
        val direction = FloatArray(3)
        val sunDirection = FloatArray(3)
        val moonDirection = FloatArray(3)
        val mix = FloatArray(2)

        for (i in 0..1000) {
            val cycle = i / 1000.0
            SkyLights.sample(cycle, direction, sunDirection, moonDirection, mix)
            val length = sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2])
            assertTrue("direction remains normalized at cycle=$cycle", length in 0.98f..1.02f)
            assertTrue("light remains above horizon at cycle=$cycle", direction[1] >= 0f)
            assertTrue("light stays within the configured 23-degree maximum elevation at cycle=$cycle", direction[1] <= 0.40f)
            assertTrue("light does not disappear at cycle=$cycle", mix[1] > 0.1f)
            assertTrue("sun colour share stays in range", mix[0] in 0f..1f)
        }
    }
}
