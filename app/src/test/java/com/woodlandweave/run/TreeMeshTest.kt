package com.woodlandweave.run

import com.woodlandweave.run.gl.Geo
import com.woodlandweave.run.gl.MeshData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The one-surface tree body: how many triangles it needs against the old separate-tube body, that it is one watertight
 * connected surface (open only at the buried bottom ring and the top ring inside the crown), and that the canopy only
 * loses faces that were buried inside the neighbouring lobes.
 */
class TreeMeshTest {
    private fun tris(m: MeshData) = m.idx.size / 3

    /** Counts triangles per directed edge after welding vertices that share a position (the UV seam duplicates). */
    private class Edges(m: MeshData) {
        val directed = HashMap<Pair<Int, Int>, Int>()
        val welded = IntArray(m.vertexCount)
        val components: Int

        init {
            val ids = HashMap<Triple<Long, Long, Long>, Int>()
            for (v in 0 until m.vertexCount) {
                val key = Triple(Math.round(m.pos[v * 3] * 1e4), Math.round(m.pos[v * 3 + 1] * 1e4), Math.round(m.pos[v * 3 + 2] * 1e4))
                welded[v] = ids.getOrPut(key) { ids.size }
            }
            val parent = IntArray(ids.size) { it }
            fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
            var i = 0
            while (i < m.idx.size) {
                val a = welded[m.idx[i].toInt() and 0xFFFF]; val b = welded[m.idx[i + 1].toInt() and 0xFFFF]; val c = welded[m.idx[i + 2].toInt() and 0xFFFF]
                for (e in arrayOf(a to b, b to c, c to a)) directed[e] = (directed[e] ?: 0) + 1
                parent[find(a)] = find(b); parent[find(b)] = find(c)
                i += 3
            }
            components = welded.map { find(it) }.toSet().size
        }

        /** Edges with no partner running the other way: the open rim. */
        fun rimEdges() = directed.count { (e, _) -> !directed.containsKey(e.second to e.first) }
        fun duplicated() = directed.count { (_, n) -> n > 1 }
    }

    @Test fun bodyTriangleCountsArePinned() {
        // oak, pine, ancient, birch
        val low = intArrayOf(656, 690, 782, 564)
        val medium = intArrayOf(912, 690, 1092, 612)
        val shadow = intArrayOf(144, 96, 168, 96)
        for (v in 0..3) {
            assertEquals("low $v", low[v], tris(Geo.treeBody(false, variant = v)))
            assertEquals("medium $v", medium[v], tris(Geo.treeBody(true, variant = v)))
            assertEquals("shadow $v", shadow[v], tris(Geo.treeBody(true, shadow = true, variant = v)))
        }
    }

    @Test fun bodyIsAtLeastAThirdCheaperThanTheOldOne() {
        for (v in 0..3) {
            for (lifelike in booleanArrayOf(false, true)) {
                val now = tris(Geo.treeBody(lifelike, variant = v)); val before = tris(Geo.treeBodyLegacy(lifelike, variant = v))
                assertTrue("variant $v lifelike $lifelike: $now vs $before", now * 3 <= before * 2)
            }
            val now = tris(Geo.treeBody(true, shadow = true, variant = v)); val before = tris(Geo.treeBodyLegacy(true, shadow = true, variant = v))
            assertTrue("shadow $v: $now vs $before", now * 2 <= before)
        }
    }

    @Test fun bodyIsOneWatertightSurface() {
        val ridges = intArrayOf(6, 4, 7, 4)
        for (v in 0..3) for (lifelike in booleanArrayOf(false, true)) {
            val m = Geo.treeBody(lifelike, variant = v)
            val e = Edges(m)
            assertEquals("one connected piece, variant $v", 1, e.components)
            assertEquals("no edge shared by two faces in the same direction, variant $v", 0, e.duplicated())
            // Open only at the bottom ring (buried in the soil) and the top ring (inside the crown): two rings of `radial` edges.
            val radial = if (lifelike) maxOf(12, 3 * ridges[v]) else maxOf(12, 2 * ridges[v])
            assertEquals("rim of variant $v lifelike $lifelike", 2 * radial, e.rimEdges())
        }
    }

    @Test fun bodyIsWellFormed() {
        for (v in 0..3) for (shadow in booleanArrayOf(false, true)) {
            val m = Geo.treeBody(true, shadow = shadow, variant = v)
            assertTrue(m.vertexCount in 1..65535)
            assertEquals(m.vertexCount * 3, m.nor.size)
            assertEquals(m.vertexCount * 2, m.uv.size)
            for (i in m.idx) assertTrue((i.toInt() and 0xFFFF) < m.vertexCount)
            for (f in m.pos) assertTrue(f.isFinite())
            for (vtx in 0 until m.vertexCount) {
                val l = sqrt(m.nor[vtx * 3] * m.nor[vtx * 3] + m.nor[vtx * 3 + 1] * m.nor[vtx * 3 + 1] + m.nor[vtx * 3 + 2] * m.nor[vtx * 3 + 2])
                assertTrue("normal length $l", abs(l - 1f) < 1e-3f)
                // The bark shader reads a negative U as the old branch marker: the new body must not use it.
                assertTrue(m.uv[vtx * 2] >= -1e-6f)
            }
        }
    }

    @Test fun bodyFacesOutward() {
        // Every vertex normal of the trunk's lower stem points away from the axis.
        for (v in 0..3) {
            val m = Geo.treeBody(true, variant = v)
            var checked = 0
            for (vtx in 0 until m.vertexCount) {
                val y = m.pos[vtx * 3 + 1]
                if (y < 5f || y > 6f) continue
                val ox = m.pos[vtx * 3]; val oz = m.pos[vtx * 3 + 2]
                if (sqrt(ox * ox + oz * oz) > 1.2f) continue
                assertTrue(m.nor[vtx * 3] * ox + m.nor[vtx * 3 + 2] * oz > 0f)
                checked++
            }
            assertTrue(checked > 0)
        }
    }

    @Test fun canopyOnlyLosesBuriedFaces() {
        val full = intArrayOf(2240, 1600, 2560, 1920)       // lobes x 320 triangles (icosphere, 2 subdivisions)
        for (v in 0..3) {
            val near = tris(Geo.treeCanopy(2, variant = v))
            assertTrue("variant $v: $near of ${full[v]}", near <= full[v])
            // The pine's tiers barely touch; the oak, ancient and birch crowns overlap and lose a sixth or more.
            if (v != 1) assertTrue("variant $v should lose buried faces: $near", near < full[v] * 9 / 10)
            val m = Geo.treeCanopy(2, variant = v)
            for (i in m.idx) assertTrue((i.toInt() and 0xFFFF) < m.vertexCount)
            assertEquals(m.vertexCount * 3, m.nor.size)
            assertFalse(m.pos.any { !it.isFinite() })
        }
    }
}
