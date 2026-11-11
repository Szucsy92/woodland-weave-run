package com.woodlandweave.run

import com.woodlandweave.run.sim.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected number below was produced by running the ORIGINAL simulation.ts (the web
 * version) under Node, so these tests prove the Kotlin port generates the same forest and the
 * same run, not merely a similar one.
 */
class SimulationParityTest {
    private val eps = 1e-9

    @Test fun hashMatchesJavaScript() {
        assertEquals(0.0, hash(0, 0, 0), eps)
        assertEquals(0.3958342600138472, hash(1, 2, 3), eps)
        assertEquals(0.7275680230761804, hash(-5, 17, 9), eps)
        assertEquals(0.8135099971698387, hash(123, -456, 300), eps)
        assertEquals(0.485344967452191, hash(40, -40, 700), eps)
    }

    @Test fun groundHeightMatches() {
        assertEquals(0.0, groundHeight(0.0, 0.0), eps)
        assertEquals(0.10655377335063092, groundHeight(10.5, -3.2), 1e-12)
        assertEquals(0.16369486513153678, groundHeight(-47.3, 88.1), 1e-12)
    }

    @Test fun forestLayoutMatches() {
        val t = treesAround(0, 0, 0.57, 0)
        assertEquals(426, t.size)
        assertEquals("-13:-13", t.first().id)
        assertEquals(-76.54528863787309, t.first().x, 1e-9)
        assertEquals(0.9151145050220924, t.first().scale, 1e-12)
        assertEquals("13:12", t.last().id)

        val o = obstaclesAround(0, 0, 0.84)
        assertEquals(49, o.size)
        assertEquals("o-13:-4", o.first().id)

        val t2 = treesAround(3, -4, 0.66, 2)
        assertEquals(482, t2.size)
        assertEquals("-10:-1~15851", t2[5].id)
        assertEquals(-57.914679439718526, t2[5].x, 1e-9)
    }

    @Test fun fullRunMatches() {
        val s = Simulation()
        beginRun(s)
        s.trees = treesAround(0, 0, 0.57, 0)
        s.obstacles = obstaclesAround(0, 0, 0.84)
        for (i in 0 until 600) stepSimulation(s, if (i % 200 < 100) 0.5 else -0.3, 1.0 / 60)
        assertEquals(Phase.RUNNING, s.phase)
        assertEquals(1, s.hits)
        assertEquals(18.57886753686809, s.x, 1e-6)
        assertEquals(-71.52537038365378, s.z, 1e-6)
        assertEquals(0.05550153050212353, s.heading, 1e-6)
        assertEquals(76.88676154585373, s.distance, 1e-6)
        assertEquals(100.68504488147492, s.stride, 1e-6)
    }

    @Test fun specialWorldsMatch() {
        val h = holesAround(300.0, 300.0, 0)[0]
        assertEquals("h0:2", h.id)
        assertEquals(175.44222553503752, h.x, 1e-9)
        assertEquals(535.10270401605, h.z, 1e-9)

        val sec = secretsAround(100.0, 100.0)[0]
        assertEquals("s0:1", sec.id)
        assertEquals(SecretType.RING, sec.type)
        assertEquals(52.22025678428361, sec.x, 1e-9)

        val g = gatesAround(100.0, 100.0)[0]
        assertEquals("g0:1", g.id)
        assertEquals(1.2883121263911737, g.angle, 1e-12)

        assertEquals(1.3403078351030844, universeHue(1), 1e-12)
        assertEquals(0.9793522178845835, universeDensity(2), 1e-12)
        assertEquals(1.1795333569356083, universeScale(3), 1e-12)
        assertNull(placePowerUp(10.0, -20.0, 1, 150, 0.3))

        val sp = placeSpore(5.0, 5.0, 3, 0, true)
        assertEquals(26.73672940156812, sp.x, 1e-9)
        assertEquals(-11.345431247340848, sp.z, 1e-9)
        assertEquals(2.943499185015331, sp.h, 1e-12)
    }

    @Test fun threeTreeHitsEndTheRun() {
        val s = Simulation()
        beginRun(s)
        s.phase = Phase.RUNNING
        val tree = Tree(0.0, -2.0, 1.0, "t")
        s.trees = listOf(tree)
        repeat(3) {
            s.x = 0.0; s.z = -2.0; s.cooldown = 0.0; s.lastTree = ""
            collide(s, tree)
        }
        assertEquals(Phase.FALLING, s.phase)
    }

    @Test fun pauseFreezesTimeAndExitReturnsToMenu() {
        val s = Simulation()
        beginRun(s)
        stepSimulation(s, 0.0, 0.05); repeat(30) { stepSimulation(s, 0.0, 0.05) }
        assertEquals(Phase.RUNNING, s.phase)
        assertTrue(requestPause(s))
        repeat(40) { stepSimulation(s, 0.0, 0.05) }
        assertEquals(PauseState.PAUSED, s.pause)
        val d = s.distance
        stepSimulation(s, 0.0, 0.05)
        assertEquals(d, s.distance, 0.0)
        assertTrue(requestExit(s))
        repeat(30) { stepSimulation(s, 0.0, 0.05) }
        assertEquals(Phase.MENU, s.phase)
    }
}
