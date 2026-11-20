package com.woodlandweave.run.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SkyLightTest {
    @Test fun sunAndMoonEachTravelEastNorthWest() {
        val east = FloatArray(3)
        val apex = FloatArray(3)
        val west = FloatArray(3)

        SkyLights.SUN.direction(0.0, east)
        SkyLights.SUN.direction(0.5, apex)
        SkyLights.SUN.direction(1.0, west)
        assertTrue("sun rises in the east", east[0] > 0.98f)
        assertTrue("sun reaches its highest point at the middle of the arc", apex[1] > east[1])
        assertTrue("sun sets in the west", west[0] < -0.98f)
        assertTrue("culmination is due north in the game-world arc", apex[2] < -0.98f)

        SkyLights.MOON.direction(0.0, east)
        SkyLights.MOON.direction(0.5, apex)
        SkyLights.MOON.direction(1.0, west)
        assertTrue("moon rises in the east", east[0] > 0.98f)
        assertTrue("moon culminates above the horizon", apex[1] > east[1])
        assertTrue("moon sets in the west", west[0] < -0.98f)
    }

    @Test fun activeDirectionIsARealSunOrMoonDirectionAndRemainsNormalized() {
        val direction = FloatArray(3)
        val sunDirection = FloatArray(3)
        val moonDirection = FloatArray(3)
        val mix = FloatArray(2)

        for (i in 0..10000) {
            val cycle = i / 10000.0
            SkyLights.sample(cycle, direction, sunDirection, moonDirection, mix)
            val length = sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2])
            assertTrue("direction remains normalized at cycle=$cycle", length in 0.98f..1.02f)
            assertTrue("light stays above the horizon at cycle=$cycle", direction[1] >= 0f)
            assertTrue("light stays within the configured 23-degree maximum elevation", direction[1] <= 0.40f)
            assertTrue("sun colour share stays in range", mix[0] in 0f..1f)
            assertTrue("intensity stays finite and non-negative", mix[1].isFinite() && mix[1] >= 0f)
        }
    }

    @Test fun bothSourcesFadeAtTheirHorizonInsteadOfBlendingAcrossTheSky() {
        val direction = FloatArray(3)
        val sunDirection = FloatArray(3)
        val moonDirection = FloatArray(3)
        val mix = FloatArray(2)

        SkyLights.sample(.25, direction, sunDirection, moonDirection, mix)
        assertEquals("sunrise hand-off is dark at the horizon", 0f, mix[1], 0.0001f)
        assertTrue("sunrise selects the rising eastern sun", direction[0] > 0.98f)

        SkyLights.sample(.75, direction, sunDirection, moonDirection, mix)
        assertEquals("sunset hand-off is dark at the horizon", 0f, mix[1], 0.0001f)
        assertTrue("sunset selects the rising eastern moon", direction[0] > 0.98f)

        SkyLights.sample(.5, direction, sunDirection, moonDirection, mix)
        assertTrue("day uses a strong sun", mix[1] > 2.0f)
        assertTrue("day colour is sunlight", mix[0] > .99f)

        SkyLights.sample(0.0, direction, sunDirection, moonDirection, mix)
        assertTrue("night uses moonlight", mix[1] > 1.0f)
        assertEquals("night colour is moonlight", 0f, mix[0], 0.001f)
    }
}
