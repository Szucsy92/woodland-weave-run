package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import com.woodlandweave.run.sim.hash
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MeshData(val pos: FloatArray, val uv: FloatArray, val idx: ShortArray) {
    var nor: FloatArray = FloatArray(pos.size)
    val vertexCount get() = pos.size / 3

    fun computeNormals() {
        nor = FloatArray(pos.size)
        var i = 0
        while (i < idx.size) {
            val a = idx[i].toInt() and 0xFFFF; val b = idx[i + 1].toInt() and 0xFFFF; val c = idx[i + 2].toInt() and 0xFFFF
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { nor[k * 3] += nx; nor[k * 3 + 1] += ny; nor[k * 3 + 2] += nz }
            i += 3
        }
        for (k in 0 until vertexCount) {
            val l = sqrt(nor[k * 3] * nor[k * 3] + nor[k * 3 + 1] * nor[k * 3 + 1] + nor[k * 3 + 2] * nor[k * 3 + 2])
            if (l > 1e-8f) { nor[k * 3] /= l; nor[k * 3 + 1] /= l; nor[k * 3 + 2] /= l }
        }
    }

    /** Per-face normals for a triangle soup (what three.js computes for non-indexed geometry): a faceted look. */
    fun computeFlatNormals() {
        nor = FloatArray(pos.size)
        var i = 0
        while (i + 2 < vertexCount) {
            val a = i * 3; val b = (i + 1) * 3; val c = (i + 2) * 3
            // (C - B) x (A - B), the same expression as three.js's computeVertexNormals().
            val cbx = pos[c] - pos[b]; val cby = pos[c + 1] - pos[b + 1]; val cbz = pos[c + 2] - pos[b + 2]
            val abx = pos[a] - pos[b]; val aby = pos[a + 1] - pos[b + 1]; val abz = pos[a + 2] - pos[b + 2]
            var nx = cby * abz - cbz * aby; var ny = cbz * abx - cbx * abz; var nz = cbx * aby - cby * abx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-12f) { nx /= l; ny /= l; nz /= l }
            for (k in intArrayOf(a, b, c)) { nor[k] = nx; nor[k + 1] = ny; nor[k + 2] = nz }
            i += 3
        }
    }

    fun forEachVertex(f: (i: Int, p: FloatArray) -> Unit) {
        val p = FloatArray(3)
        for (i in 0 until vertexCount) {
            p[0] = pos[i * 3]; p[1] = pos[i * 3 + 1]; p[2] = pos[i * 3 + 2]
            f(i, p)
            pos[i * 3] = p[0]; pos[i * 3 + 1] = p[1]; pos[i * 3 + 2] = p[2]
        }
    }
}

object Geo {
    /** Open-ended cylinder like THREE.CylinderGeometry (no caps), y from +h/2 (top) to -h/2. */
    fun cylinder(rTop: Float, rBottom: Float, h: Float, radial: Int, hseg: Int): MeshData {
        val verts = (radial + 1) * (hseg + 1)
        val pos = FloatArray(verts * 3); val uv = FloatArray(verts * 2)
        var n = 0
        for (iy in 0..hseg) {
            val v = iy.toFloat() / hseg
            val r = v * (rBottom - rTop) + rTop
            for (ix in 0..radial) {
                val u = ix.toFloat() / radial
                val th = u * 2f * PI.toFloat()
                pos[n * 3] = r * sin(th); pos[n * 3 + 1] = -v * h + h / 2; pos[n * 3 + 2] = r * cos(th)
                uv[n * 2] = u; uv[n * 2 + 1] = 1 - v
                n++
            }
        }
        val idx = ArrayList<Short>()
        for (iy in 0 until hseg) for (ix in 0 until radial) {
            val a = iy * (radial + 1) + ix; val b = (iy + 1) * (radial + 1) + ix
            val c = (iy + 1) * (radial + 1) + ix + 1; val d = iy * (radial + 1) + ix + 1
            idx += a.toShort(); idx += b.toShort(); idx += d.toShort()
            idx += b.toShort(); idx += c.toShort(); idx += d.toShort()
        }
        return MeshData(pos, uv, idx.toShortArray()).also { it.computeNormals() }
    }

    fun icosphere(subdivisions: Int): MeshData {
        val t = (1f + sqrt(5f)) / 2f
        val v = arrayListOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
        )
        for (p in v) { val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); p[0] /= l; p[1] /= l; p[2] /= l }
        var f = arrayListOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdivisions) {
            val cache = HashMap<Long, Int>()
            fun mid(a: Int, b: Int): Int {
                val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                return cache.getOrPut(key) {
                    val p = floatArrayOf((v[a][0] + v[b][0]) / 2, (v[a][1] + v[b][1]) / 2, (v[a][2] + v[b][2]) / 2)
                    val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); p[0] /= l; p[1] /= l; p[2] /= l
                    v += p; v.size - 1
                }
            }
            val next = ArrayList<IntArray>()
            for (tri in f) {
                val a = mid(tri[0], tri[1]); val b = mid(tri[1], tri[2]); val c = mid(tri[2], tri[0])
                next += intArrayOf(tri[0], a, c); next += intArrayOf(tri[1], b, a); next += intArrayOf(tri[2], c, b); next += intArrayOf(a, b, c)
            }
            f = next
        }
        val pos = FloatArray(v.size * 3); val uv = FloatArray(v.size * 2)
        for (i in v.indices) {
            pos[i * 3] = v[i][0]; pos[i * 3 + 1] = v[i][1]; pos[i * 3 + 2] = v[i][2]
            uv[i * 2] = 0.5f + atan2(v[i][2], v[i][0]) / (2f * PI.toFloat()); uv[i * 2 + 1] = 0.5f - v[i][1] / 2f
        }
        val idx = ShortArray(f.size * 3) { f[it / 3][it % 3].toShort() }
        return MeshData(pos, uv, idx).also { it.computeNormals() }
    }

    /**
     * THREE.IcosahedronGeometry(1, detail): an unindexed triangle soup in three.js's exact vertex order, so that
     * the per-vertex displacement below (which is keyed by vertex index) tears and bumps it the same way. Call
     * [MeshData.computeFlatNormals] once it is displaced.
     */
    fun polyhedron(detail: Int): MeshData {
        val t = (1f + sqrt(5f)) / 2f
        val vs = floatArrayOf(
            -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t, 0f,
            0f, -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t,
            t, 0f, -1f, t, 0f, 1f, -t, 0f, -1f, -t, 0f, 1f,
        )
        val ix = intArrayOf(
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
        )
        val cols = detail + 1
        val out = ArrayList<Float>()
        fun lerp(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
        fun push(v: FloatArray) {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            out += v[0] / l; out += v[1] / l; out += v[2] / l
        }
        for (f in 0 until ix.size / 3) {
            val a = FloatArray(3) { vs[ix[f * 3] * 3 + it] }
            val b = FloatArray(3) { vs[ix[f * 3 + 1] * 3 + it] }
            val c = FloatArray(3) { vs[ix[f * 3 + 2] * 3 + it] }
            val v = Array(cols + 1) { i -> Array(cols - i + 1) { FloatArray(3) } }
            for (i in 0..cols) {
                val aj = lerp(a, c, i.toFloat() / cols)
                val bj = lerp(b, c, i.toFloat() / cols)
                val rows = cols - i
                for (j in 0..rows) v[i][j] = if (j == 0 && i == cols) aj else lerp(aj, bj, j.toFloat() / rows)
            }
            for (i in 0 until cols) {
                for (j in 0 until 2 * (cols - i) - 1) {
                    val k = j / 2
                    if (j % 2 == 0) { push(v[i][k + 1]); push(v[i + 1][k]); push(v[i][k]) }
                    else { push(v[i][k + 1]); push(v[i + 1][k + 1]); push(v[i + 1][k]) }
                }
            }
        }
        val pos = out.toFloatArray()
        val n = pos.size / 3
        return MeshData(pos, FloatArray(n * 2), ShortArray(n) { it.toShort() })
    }

    /** A flat-shaded icosahedron, as three.js draws IcosahedronGeometry(1, detail) with computeVertexNormals(). */
    fun facetedIcosahedron(detail: Int): MeshData = polyhedron(detail).also { it.computeFlatNormals() }

    fun box(w: Float, h: Float, d: Float): MeshData {
        val hx = w / 2; val hy = h / 2; val hz = d / 2
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f, hx, -hy, hz, hx, -hy, -hz, hx, hy, -hz, hx, hy, hz),
            floatArrayOf(-1f, 0f, 0f, -hx, -hy, -hz, -hx, -hy, hz, -hx, hy, hz, -hx, hy, -hz),
            floatArrayOf(0f, 1f, 0f, -hx, hy, hz, hx, hy, hz, hx, hy, -hz, -hx, hy, -hz),
            floatArrayOf(0f, -1f, 0f, -hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, -hx, -hy, hz),
            floatArrayOf(0f, 0f, 1f, -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz),
            floatArrayOf(0f, 0f, -1f, hx, -hy, -hz, -hx, -hy, -hz, -hx, hy, -hz, hx, hy, -hz),
        )
        val pos = FloatArray(24 * 3); val uv = FloatArray(24 * 2); val idx = ShortArray(36)
        val corner = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        for (fi in 0..5) {
            val fc = faces[fi]
            for (k in 0..3) {
                val vi = fi * 4 + k
                pos[vi * 3] = fc[3 + k * 3]; pos[vi * 3 + 1] = fc[4 + k * 3]; pos[vi * 3 + 2] = fc[5 + k * 3]
                uv[vi * 2] = corner[k][0]; uv[vi * 2 + 1] = corner[k][1]
            }
            val b = fi * 4
            val tri = shortArrayOf(b.toShort(), (b + 1).toShort(), (b + 2).toShort(), b.toShort(), (b + 2).toShort(), (b + 3).toShort())
            tri.copyInto(idx, fi * 6)
        }
        return MeshData(pos, uv, idx).also { it.computeNormals() }
    }

    /** Horizontal unit square in the XZ plane (contact shadows). */
    fun flatQuad(): MeshData {
        val pos = floatArrayOf(-.5f, 0f, -.5f, .5f, 0f, -.5f, .5f, 0f, .5f, -.5f, 0f, .5f)
        val uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        return MeshData(pos, uv, shortArrayOf(0, 2, 1, 0, 3, 2)).also { it.computeNormals() }
    }


    /** Unit square in the XY plane facing +Z (accretion veils, gate veils). */
    fun quadXY(): MeshData {
        val pos = floatArrayOf(-.5f, -.5f, 0f, .5f, -.5f, 0f, .5f, .5f, 0f, -.5f, .5f, 0f)
        val uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        return MeshData(pos, uv, shortArrayOf(0, 1, 2, 0, 2, 3)).also { it.computeNormals() }
    }

    /** THREE.TorusGeometry: ring of radius R in the XY plane with tube radius r. */
    fun torus(R: Float, r: Float, radial: Int, tubular: Int): MeshData {
        val verts = (radial + 1) * (tubular + 1)
        val pos = FloatArray(verts * 3); val uv = FloatArray(verts * 2)
        var n = 0
        for (j in 0..radial) for (i in 0..tubular) {
            val u = i.toFloat() / tubular * 2f * PI.toFloat()
            val v = j.toFloat() / radial * 2f * PI.toFloat()
            pos[n * 3] = (R + r * cos(v)) * cos(u); pos[n * 3 + 1] = (R + r * cos(v)) * sin(u); pos[n * 3 + 2] = r * sin(v)
            uv[n * 2] = i.toFloat() / tubular; uv[n * 2 + 1] = j.toFloat() / radial
            n++
        }
        val idx = ArrayList<Short>()
        for (j in 1..radial) for (i in 1..tubular) {
            val a = (tubular + 1) * j + i - 1; val b = (tubular + 1) * (j - 1) + i - 1
            val c = (tubular + 1) * (j - 1) + i; val d = (tubular + 1) * j + i
            idx += a.toShort(); idx += b.toShort(); idx += d.toShort()
            idx += b.toShort(); idx += c.toShort(); idx += d.toShort()
        }
        return MeshData(pos, uv, idx.toShortArray()).also { it.computeNormals() }
    }

    // ---- the forest's own shapes (ported from ForestRunner.tsx) ------------------------------

    /** Gnarled trunk: knots, irregular section, gentle wander, buttressed root flare. */
    fun trunk(): MeshData {
        val g = cylinder(.27f, .4f, 22f, 16, 32)
        g.forEachVertex { _, p ->
            val y = p[1]
            val a = atan2(p[2], p[0])
            val knot = Math.pow(max(0.0, (sin(a * 3 + y * 1.7) * sin(y * 2.3 + a)).toDouble()), 4.0).toFloat() * .16f
            val swell = 1 + sin(a * 5 + y * .9f) * .07f + sin(a * 9 - y * .4f) * .035f + sin(a * 2 + y * .23f) * .06f + knot
            val foot = max(0f, 1 - (y + 11) / 2.6f)
            val root = 1 + foot * foot * (.75f + max(0f, cos(a * 5 + .4f)) * 1.3f)
            val wx = sin(y * .3f) * .09f + sin(y * .71f + 1.3f) * .05f
            val wz = cos(y * .26f + .7f) * .08f
            p[0] = p[0] * swell * root + wx; p[2] = p[2] * swell * root + wz; p[1] = y + 11f
        }
        g.computeNormals()
        return g
    }

    /** Surface root: lies along +x, tapers and dives into the soil. */
    fun root(): MeshData {
        val g = cylinder(.025f, .16f, 2.4f, 7, 10)
        g.forEachVertex { _, p ->
            val y = p[1] + 1.2f; val t = y / 2.4f; val flat = 1 - t * .35f
            val nx = y; val ny = p[0] * .75f * flat - t * t * .45f + sin(t * 6) * .04f; val nz = p[2] + sin(t * 5.3f) * .12f * t
            p[0] = nx; p[1] = ny; p[2] = nz
        }
        g.computeNormals()
        return g
    }

    fun branch(): MeshData {
        val g = cylinder(.03f, .07f, 2.2f, 6, 8)
        g.forEachVertex { _, p ->
            val y = p[1] + 1.1f; val t = y / 2.2f
            p[0] = p[0] + sin(t * 7.3f) * .09f * t + t * t * .25f; p[1] = y; p[2] = p[2] + sin(t * 5.1f + 1) * .08f * t
        }
        g.computeNormals()
        return g
    }


    // ---- lifelike trees (Medium and High) ----

    /**
     * Builds the triangles for a swept tube whose rows each hold (radial + 1) vertices (the last repeats the first).
     * Rows run from the first to the last ring; winding is flipped automatically so the faces look outward, and the
     * normals along the seam are welded so the surface is truly round.
     */
    private fun finishTube(pos: FloatArray, uv: FloatArray, rows: Int, radial: Int, centers: FloatArray): MeshData {
        val idx = ShortArray((rows - 1) * radial * 6)
        var k = 0
        for (iy in 0 until rows - 1) for (ix in 0 until radial) {
            val a = iy * (radial + 1) + ix; val b = (iy + 1) * (radial + 1) + ix
            val c = (iy + 1) * (radial + 1) + ix + 1; val d = iy * (radial + 1) + ix + 1
            idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = d.toShort()
            idx[k++] = b.toShort(); idx[k++] = c.toShort(); idx[k++] = d.toShort()
        }
        val m = MeshData(pos, uv, idx)
        m.computeNormals()
        var outward = 0f
        for (row in 0 until rows) for (ix in 0 until radial) {
            val v = row * (radial + 1) + ix
            outward += m.nor[v * 3] * (pos[v * 3] - centers[row * 3]) + m.nor[v * 3 + 1] * (pos[v * 3 + 1] - centers[row * 3 + 1]) +
                m.nor[v * 3 + 2] * (pos[v * 3 + 2] - centers[row * 3 + 2])
        }
        if (outward < 0f) {
            var i = 0
            while (i < idx.size) { val t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; i += 3 }
            m.computeNormals()
        }
        for (row in 0 until rows) {
            val a = row * (radial + 1); val b = a + radial
            var nx = m.nor[a * 3] + m.nor[b * 3]; var ny = m.nor[a * 3 + 1] + m.nor[b * 3 + 1]; var nz = m.nor[a * 3 + 2] + m.nor[b * 3 + 2]
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f)
            nx /= l; ny /= l; nz /= l
            m.nor[a * 3] = nx; m.nor[a * 3 + 1] = ny; m.nor[a * 3 + 2] = nz
            m.nor[b * 3] = nx; m.nor[b * 3 + 1] = ny; m.nor[b * 3 + 2] = nz
        }
        return m
    }

    /**
     * A trunk with a real shape: round cross-sections (28 sides), a buttress flare whose ridges run into the roots,
     * slightly elliptical and lobed sections, shallow bark fissures and knots, a gentle sway of the centre line.
     * Same height, radius and attachment points as the plain trunk, so branches and crowns still sit where they did.
     */
    fun trunkLifelike(radial: Int = 28, coarseRows: List<Float>? = null): MeshData {
        // Rows run top to bottom: coarse up the stem, fine through the flare where the shape changes quickly.
        val hs = ArrayList<Float>()
        if (coarseRows != null) hs.addAll(coarseRows) else {
            var h = 22f
            while (h > 4.2f) { hs += h; h -= 1.2f }
            h = 4.2f
            while (h >= -0.001f) { hs += max(h, 0f); h -= 0.3f }
        }
        val rows = hs.size
        val pos = FloatArray(rows * (radial + 1) * 3); val uv = FloatArray(rows * (radial + 1) * 2); val centers = FloatArray(rows * 3)
        var n = 0
        for (iy in 0 until rows) {
            val y = hs[iy]
            val wx = sin(y * .3f) * .09f + sin(y * .71f + 1.3f) * .05f
            val wz = cos(y * .26f + .7f) * .08f
            centers[iy * 3] = wx; centers[iy * 3 + 1] = y; centers[iy * 3 + 2] = wz
            val r0 = .27f + .13f * (1f - y / 22f)
            for (ix in 0..radial) {
                val th = ix.toFloat() / radial * 2f * PI.toFloat()
                val sx = sin(th); val cz = cos(th)
                val a = atan2(cz, sx)
                val knot = Math.pow(max(0.0, (sin(a * 3 + y * 1.7f) * sin(y * 2.3f + a)).toDouble()), 4.0).toFloat() * .15f
                val swell = 1 + sin(a * 5 + y * .9f) * .06f + sin(a * 7 - y * .4f) * .03f + sin(a * 2 + y * .23f) * .05f +
                    sin(a * 7 + sin(y * .5f) * 1.4f) * .018f + knot
                val ell = 1 + sin(2 * a + y * .15f) * .06f
                val foot = max(0f, 1 - y / 3.4f)
                val ridge = Math.pow(max(0.0, cos(a * 5 + .4f).toDouble()), 2.2).toFloat()
                val flare = 1 + foot * foot * (.55f + ridge * 1.6f)
                val rr = r0 * swell * flare
                pos[n * 3] = sx * rr * ell + wx; pos[n * 3 + 1] = y; pos[n * 3 + 2] = cz * rr / ell + wz
                uv[n * 2] = ix.toFloat() / radial; uv[n * 2 + 1] = y / 22f
                n++
            }
        }
        return finishTube(pos, uv, rows, radial, centers)
    }

    /**
     * A surface root: a smooth tube along +x that rises out of the trunk flare, meanders, tapers and dives into the
     * soil. Wider than tall, with soft lumps, 12 sides and 24 steps.
     */
    fun rootLifelike(segs: Int = 24, radial: Int = 12): MeshData {
        val rows = segs + 1
        val pos = FloatArray(rows * (radial + 1) * 3); val uv = FloatArray(rows * (radial + 1) * 2); val centers = FloatArray(rows * 3)
        var n = 0
        for (iy in 0..segs) {
            val t = iy.toFloat() / segs
            val x = -.3f + t * 2.7f
            val cy = -t * t * .45f + sin(t * 6) * .04f + exp(-t * 4.5f) * .14f
            val cz = sin(t * 5.3f) * .12f * t
            centers[iy * 3] = x; centers[iy * 3 + 1] = cy; centers[iy * 3 + 2] = cz
            val rad = .025f + .135f * (1f - t).pow(1.15f)
            for (ix in 0..radial) {
                val th = ix.toFloat() / radial * 2f * PI.toFloat()
                val lump = 1 + sin(th * 3 + t * 11) * .07f + sin(th * 2 - t * 7) * .05f
                pos[n * 3] = x
                pos[n * 3 + 1] = cy + cos(th) * rad * .75f * lump
                pos[n * 3 + 2] = cz + sin(th) * rad * lump
                uv[n * 2] = ix.toFloat() / radial; uv[n * 2 + 1] = t
                n++
            }
        }
        return finishTube(pos, uv, rows, radial, centers)
    }

    /**
     * Builds a tapered, gently irregular tube along a 3D centreline. Used for roots, primary limbs
     * and secondary forks. Every limb starts inside its parent surface, so no detached/floating ends
     * are exposed when the tree is rendered translucently by the ghost power-up.
     */
    private fun sweptTube(points: List<FloatArray>, radii: FloatArray, radial: Int, verticalScale: Float = 1f): MeshData {
        require(points.size >= 2 && points.size == radii.size && radial >= 3)
        val rows = points.size
        val pos = FloatArray(rows * (radial + 1) * 3)
        val uv = FloatArray(rows * (radial + 1) * 2)
        val centers = FloatArray(rows * 3)
        var n = 0
        for (row in 0 until rows) {
            val p = points[row]
            val before = points[if (row == 0) 0 else row - 1]
            val after = points[if (row == rows - 1) rows - 1 else row + 1]
            var tx = after[0] - before[0]
            var ty = after[1] - before[1]
            var tz = after[2] - before[2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= tl; ty /= tl; tz /= tl
            // Pick a stable reference axis so the tube frame cannot flip on near-vertical limbs.
            val refX = 0f
            val refY = if (abs(ty) > .88f) 0f else 1f
            val refZ = if (abs(ty) > .88f) 1f else 0f
            var ux = ty * refZ - tz * refY
            var uy = tz * refX - tx * refZ
            var uz = tx * refY - ty * refX
            val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
            ux /= ul; uy /= ul; uz /= ul
            val vx = ty * uz - tz * uy
            val vy = tz * ux - tx * uz
            val vz = tx * uy - ty * ux
            centers[row * 3] = p[0]; centers[row * 3 + 1] = p[1]; centers[row * 3 + 2] = p[2]
            for (side in 0..radial) {
                val a = side.toFloat() / radial * 2f * PI.toFloat()
                val wobble = 1f + sin(a * 3f + row * .73f) * .035f + sin(a * 5f - row * .41f) * .018f
                val r = radii[row] * wobble
                val ca = cos(a); val sa = sin(a)
                pos[n * 3] = p[0] + (ux * ca + vx * sa) * r
                pos[n * 3 + 1] = p[1] + (uy * ca + vy * sa) * r * verticalScale
                pos[n * 3 + 2] = p[2] + (uz * ca + vz * sa) * r
                uv[n * 2] = side.toFloat() / radial
                uv[n * 2 + 1] = row.toFloat() / (rows - 1)
                n++
            }
        }
        return finishTube(pos, uv, rows, radial, centers)
    }

    /** Branch UV marker lets the shared bark shader avoid applying trunk-foot darkening to limbs. */
    private fun markAsBranch(data: MeshData): MeshData {
        val uv = data.uv.copyOf()
        for (v in 0 until data.vertexCount) uv[v * 2] = -(uv[v * 2] + 1f)
        return MeshData(data.pos.copyOf(), uv, data.idx).also { it.nor = data.nor.copyOf() }
    }

    /** The plain branch, with enough sides to be round up close. */
    fun branchRound(radial: Int = 10, heightSegs: Int = 10): MeshData {
        val g = cylinder(.03f, .07f, 2.2f, radial, heightSegs)
        g.forEachVertex { _, p ->
            val y = p[1] + 1.1f; val t = y / 2.2f
            p[0] = p[0] + sin(t * 7.3f) * .09f * t + t * t * .25f; p[1] = y; p[2] = p[2] + sin(t * 5.1f + 1) * .08f * t
        }
        g.computeNormals()
        return g
    }

    /**
     * Joins already transformed sub-meshes into one static mesh. Tree components are merged once when a
     * quality tier is built, not per frame. The resulting geometry remains suitable for GLES 3.0 instancing.
     */
    private fun mergeTreeParts(parts: List<Pair<MeshData, FloatArray>>): MeshData {
        val vertices = parts.sumOf { it.first.vertexCount }
        require(vertices <= 65535) { "Merged tree mesh exceeds GLES2/3 unsigned-short index range: $vertices" }
        val indexTotal = parts.sumOf { it.first.idx.size }
        val pos = FloatArray(vertices * 3)
        val uv = FloatArray(vertices * 2)
        val mergedNormals = FloatArray(vertices * 3)
        val idx = ShortArray(indexTotal)
        var vertexBase = 0
        var indexBase = 0
        for ((data, m) in parts) {
            val sx = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).coerceAtLeast(1e-8f)
            val sy = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]).coerceAtLeast(1e-8f)
            val sz = kotlin.math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]).coerceAtLeast(1e-8f)
            for (v in 0 until data.vertexCount) {
                val x = data.pos[v * 3]; val y = data.pos[v * 3 + 1]; val z = data.pos[v * 3 + 2]
                val d = (vertexBase + v) * 3
                pos[d] = m[0] * x + m[4] * y + m[8] * z + m[12]
                pos[d + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                pos[d + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
                uv[(vertexBase + v) * 2] = data.uv[v * 2]
                uv[(vertexBase + v) * 2 + 1] = data.uv[v * 2 + 1]
                val nx = data.nor[v * 3] / sx; val ny = data.nor[v * 3 + 1] / sy; val nz = data.nor[v * 3 + 2] / sz
                var tx = m[0] / sx * nx + m[4] / sy * ny + m[8] / sz * nz
                var ty = m[1] / sx * nx + m[5] / sy * ny + m[9] / sz * nz
                var tz = m[2] / sx * nx + m[6] / sy * ny + m[10] / sz * nz
                val nl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-8f)
                tx /= nl; ty /= nl; tz /= nl
                // Normals are packed after positions by Mesh; keep the merged normal data separately below.
                mergedNormals[(vertexBase + v) * 3] = tx
                mergedNormals[(vertexBase + v) * 3 + 1] = ty
                mergedNormals[(vertexBase + v) * 3 + 2] = tz
            }
            for (j in data.idx.indices) idx[indexBase + j] = ((data.idx[j].toInt() and 0xffff) + vertexBase).toShort()
            vertexBase += data.vertexCount
            indexBase += data.idx.size
        }
        return MeshData(pos, uv, idx).also { it.nor = mergedNormals }
    }

    /**
     * One reusable, low-cost tree-body mesh. Roots grow out of the buttress flare and sink into
     * the soil; limbs begin inside the trunk and taper continuously into smaller forks. All pieces
     * are merged once per species variant and submitted through a shared instanced batch.
     */
    fun treeBody(lifelike: Boolean, shadow: Boolean = false, variant: Int = 0): MeshData {
        val parts = ArrayList<Pair<MeshData, FloatArray>>(20)
        fun add(data: MeshData, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0,
                rx: Double = 0.0, ry: Double = 0.0, rz: Double = 0.0,
                sx: Double = 1.0, sy: Double = 1.0, sz: Double = 1.0, order: Int = Mat.XYZ) {
            val m = FloatArray(16)
            Mat.compose(m, 0, x, y, z, rx, ry, rz, order, sx, sy, sz)
            parts += data to m
        }
        val trunkRadial = if (shadow) 10 else if (lifelike) 20 else 12
        val species = variant.coerceIn(0, 3) // oak, pine, ancient, birch
        val trunk = if (shadow) {
            trunkLifelike(trunkRadial, listOf(22f, 16f, 10f, 5f, 2.2f, .7f, 0f))
        } else trunkLifelike(trunkRadial)
        // Species silhouette comes from the trunk profile, not additional geometry.
        val trunkScale = doubleArrayOf(1.0, .80, 1.30, .70)[species]
        val lean = doubleArrayOf(.006, .014, .032, .010)[species]
        add(trunk, rx = lean, rz = -lean * .55, sx = trunkScale, sz = trunkScale)

        fun h(salt: Int) = hash(species, 0, salt)
        // The trunk's small centre-line wander, repeated here so roots and limbs enter the actual
        // trunk surface rather than appearing beside it.
        fun trunkX(y: Double) = sin(y * .3) * .09 + sin(y * .71 + 1.3) * .05
        fun trunkZ(y: Double) = cos(y * .26 + .7) * .08

        // Six broad, curved buttress roots radiate from inside the trunk flare. Their wide
        // proximal collars blend into the buried trunk; the flattened profile and descending
        // centreline make them read as roots rather than pipes laid on the ground.
        val rootRadial = if (shadow) 4 else if (lifelike) 7 else 5
        val rootCount = if (shadow) 4 else intArrayOf(6, 4, 7, 4)[species]
        val rootLength = doubleArrayOf(1.0, .76, 1.32, .78)[species]
        val rootWidth = doubleArrayOf(1.0, .76, 1.22, .74)[species]
        val rootPoints = listOf(
            floatArrayOf(-.38f, 1.34f, 0f), floatArrayOf(.02f, 1.32f, .015f),
            floatArrayOf(.48f, 1.27f, .045f), floatArrayOf(.98f, 1.19f, .09f),
            floatArrayOf(1.52f, 1.08f, .15f), floatArrayOf(2.10f, .91f, .22f),
            floatArrayOf(2.62f, .66f, .29f)
        )
        val rootRadii = floatArrayOf(.46f, .43f, .37f, .29f, .20f, .095f, .009f).map { it * rootWidth.toFloat() }.toFloatArray()
        val root = sweptTube(rootPoints, rootRadii, rootRadial, verticalScale = .78f)
        for (r in 0 until rootCount) {
            val ang = (r / rootCount.toDouble()) * PI * 2 + (h(210 + r) - .5) * .28
            val rootScale = (.88 + h(230 + r) * .24) * rootLength
            add(root, cos(ang) * .12, 0.0, -sin(ang) * .12, 0.0, ang, 0.0,
                rootScale, .92, rootScale, Mat.XYZ)
        }

        // Primary limbs grow from broad collars buried in the stem. Their centre lines arc
        // outward and upward; a long radius falloff makes the joint organic instead of a pipe
        // inserted into the trunk. Secondary forks begin inside the parent limb for ghost safety.
        val branchCount = if (shadow) 5 else intArrayOf(8, 9, 9, 6)[species]
        val branchRadial = if (shadow) 4 else if (lifelike) 7 else 5
        for (b in 0 until branchCount) {
            val angle = b / branchCount.toDouble() * PI * 2 + (h(202 + b) - .5) * .42
            val dx = cos(angle); val dz = sin(angle)
            val sideX = -dz; val sideZ = dx
            val level = b / (if (species == 1) 3.0 else 2.0)
            val y = when (species) {
                1 -> 5.0 + level * 4.2 + h(60 + b) * .42
                2 -> 6.8 + b * 1.32 + h(60 + b) * .5
                3 -> 10.0 + b * 1.55 + h(60 + b) * .45
                else -> 7.2 + b * 1.42 + h(60 + b) * .45
            }
            val rawLength = when (species) {
                1 -> 4.1 - level * .78
                2 -> 3.1 + h(95 + b) * 1.15
                3 -> 2.0 + h(95 + b) * .9
                else -> 3.0 + h(95 + b) * 1.25
            }
            val length = rawLength.coerceAtLeast(1.0) * doubleArrayOf(1.0, .9, 1.16, .92)[species]
            val bend = (h(80 + b) - .5) * if (species == 2) .82 else .58
            val cx = trunkX(y); val cz = trunkZ(y)
            val points = listOf(
                floatArrayOf((cx + dx * .015).toFloat(), (y - .34).toFloat(), (cz + dz * .015).toFloat()),
                floatArrayOf((cx + dx * (length * .13) + sideX * bend * .08).toFloat(), (y - .19).toFloat(), (cz + dz * (length * .13) + sideZ * bend * .08).toFloat()),
                floatArrayOf((cx + dx * (length * .31) + sideX * bend * .28).toFloat(), (y + .015).toFloat(), (cz + dz * (length * .31) + sideZ * bend * .28).toFloat()),
                floatArrayOf((cx + dx * (length * .55) + sideX * bend * .62).toFloat(), (y + .30).toFloat(), (cz + dz * (length * .55) + sideZ * bend * .62).toFloat()),
                floatArrayOf((cx + dx * (length * .80) + sideX * bend).toFloat(), (y + .72).toFloat(), (cz + dz * (length * .80) + sideZ * bend).toFloat()),
                floatArrayOf((cx + dx * length + sideX * bend * 1.12).toFloat(), (y + 1.28).toFloat(), (cz + dz * length + sideZ * bend * 1.12).toFloat())
            )
            val speciesThickness = doubleArrayOf(1.0, .82, 1.28, .72)[species]
            val thickness = ((.90 + h(90 + b) * .20) * speciesThickness).toFloat()
            val radii = floatArrayOf(.36f, .325f, .265f, .195f, .112f, .022f).map { it * thickness }.toFloatArray()
            add(markAsBranch(sweptTube(points, radii, branchRadial)))

            // A few small forks are also curved and tapered; their first ring is embedded in
            // the parent branch so the translucent ghost pass cannot reveal a floating cut end.
            if (lifelike && !shadow && (species >= 2 || b % 2 == 0)) {
                val forkBase = points[3]
                val forkDir = if (b % 4 == 0) 1.0 else -1.0
                val fx = forkBase[0].toDouble(); val fy = forkBase[1].toDouble(); val fz = forkBase[2].toDouble()
                val forkPoints = listOf(
                    forkBase.copyOf(),
                    floatArrayOf((fx + dx * .20 + sideX * .22 * forkDir).toFloat(), (fy + .12).toFloat(), (fz + dz * .20 + sideZ * .22 * forkDir).toFloat()),
                    floatArrayOf((fx + dx * .46 + sideX * .52 * forkDir).toFloat(), (fy + .34).toFloat(), (fz + dz * .46 + sideZ * .52 * forkDir).toFloat()),
                    floatArrayOf((fx + dx * .72 + sideX * .82 * forkDir).toFloat(), (fy + .66).toFloat(), (fz + dz * .72 + sideZ * .82 * forkDir).toFloat())
                )
                add(markAsBranch(sweptTube(forkPoints, floatArrayOf(.145f, .105f, .060f, .012f), max(5, branchRadial - 1))))
            }
        }

        return mergeTreeParts(parts)
    }

    /** Species-specific foliage silhouettes joined into one low-overdraw canopy mesh. */
    fun treeCanopy(nearSubdivisions: Int, farSubdivisions: Int? = null, variant: Int = 0): MeshData {
        val parts = ArrayList<Pair<MeshData, FloatArray>>(7)
        val crownMesh = if (farSubdivisions == null) crown(nearSubdivisions, false)
            else crownLod(nearSubdivisions, farSubdivisions)
        fun h(salt: Int) = hash(variant, 0, salt)
        val leanDir = .73
        val leanAmt = doubleArrayOf(.018, .045, .012, .030)[variant.coerceIn(0, 3)]
        val lx = cos(leanDir) * leanAmt
        val lz = sin(leanDir) * leanAmt
        fun ox(hh: Double) = -hh * lz
        fun oz(hh: Double) = hh * lx
        fun add(x: Double, y: Double, z: Double, rx: Double, ry: Double, rz: Double, sx: Double, sy: Double, sz: Double) {
            val m = FloatArray(16)
            Mat.compose(m, 0, x, y, z, rx, ry, rz, Mat.YXZ, sx, sy, sz)
            parts += crownMesh to m
        }
        if (variant == 1) {
            // Pine: five compact overlapping tiers form a readable conical crown.
            for (tier in 0 until 5) {
                val y = 7.8 + tier * 2.75
                val radius = 4.45 - tier * .70
                add((h(20 + tier) - .5) * .28, y, (h(30 + tier) - .5) * .28,
                    .025, h(40 + tier) * PI * 2, .025, radius, 1.55 - tier * .10, radius * .94)
            }
            return mergeTreeParts(parts)
        }
        if (variant == 3) {
            // Birch: tall, narrow crown with separated vertical lobes.
            add(0.0, 18.1, 0.0, .02, h(83) * PI * 2, -.02, 2.45, 3.65, 2.55)
            for (c in 0 until 5) {
                val a = c / 5.0 * PI * 2 + h(74 + c) * .35
                val spread = 1.35 + h(81 + c) * .75
                add(cos(a) * spread, 12.0 + h(71 + c) * 2.0, sin(a) * spread,
                    .12, a, -.10, 1.75, 2.65, 1.85)
            }
            return mergeTreeParts(parts)
        }
        // Oak and ancient: broad broken crowns, with extra spread and irregularity on the old tree.
        val ancient = variant == 2
        val lobeCount = if (ancient) 7 else 6
        add(ox(18.0), 18.0 + h(71) * 1.2, oz(18.0), .12, h(83) * PI * 2, -.08,
            if (ancient) 4.0 else 3.35, if (ancient) 2.65 else 3.0, if (ancient) 4.1 else 3.45)
        for (c in 0 until lobeCount) {
            val angle = c / lobeCount.toDouble() * PI * 2 + h(74 + c) * .48
            val spread = (if (ancient) 3.1 else 2.7) + h(81 + c) * (if (ancient) 1.65 else 1.25)
            val y = (if (ancient) 12.0 else 11.5) + h(71 + c * 3) * 2.5
            val r = (if (ancient) 3.1 else 2.65) + h(73 + c) * (if (ancient) 1.25 else .95)
            add(ox(y) + cos(angle) * spread, y, oz(y) + sin(angle) * spread,
                (h(83 + c) - .5) * .75, angle, (h(77 + c) - .5) * .75,
                r * (.84 + h(85 + c) * .30), r * (.72 + h(87 + c) * .28), r * (.84 + h(79 + c) * .30))
        }
        return mergeTreeParts(parts)
    }


    /**
     * The far crown: the triangles of [far] subdivisions, but the positions, normals and texture coordinates of the [near] crown at
     * the vertices the two levels share (the vertices of the lower level come first, in the same order). The lumps and the shading
     * therefore agree with the near crown; only the fine sampling between the shared vertices is dropped.
     */
    fun crownLod(near: Int, far: Int): MeshData {
        val hi = crown(near, false)
        val lo = icosphere(far)
        val n = lo.vertexCount
        val m = MeshData(hi.pos.copyOf(n * 3), hi.uv.copyOf(n * 2), lo.idx)
        m.nor = hi.nor.copyOf(n * 3)
        return m
    }

    /** Lumpy leaf mass; CROWN_SUBDIVISIONS keeps the instance count affordable on phones. */
    fun crown(subdivisions: Int, flat: Boolean = true): MeshData {
        val g = if (flat) polyhedron(subdivisions) else icosphere(subdivisions)
        g.forEachVertex { _, v ->
            val n = sin(v[0] * 5.1f + v[1] * 2.3f) * sin(v[2] * 4.7f - v[1] * 3.1f) * .16f + sin(v[0] * 11 + v[2] * 9) * .05f
            val lump = sin(v[0] * 2.1f + 1.3f) * sin(v[2] * 1.7f + v[1] * 2.6f) * .32f + sin(v[1] * 7.7f + v[0] * 6.1f) * cos(v[2] * 8.3f) * .12f
            val k = max(.35f, 1 + n + lump)
            v[0] *= k; v[1] *= k; v[2] *= k
            v[1] *= if (v[1] < 0) .45f else .8f
        }
        if (flat) g.computeFlatNormals() else g.computeNormals()
        return g
    }

    fun jittered(subdivisions: Int, xz: Float, y: Float, seed: Int, flat: Boolean = true): MeshData {
        val g = if (flat) polyhedron(subdivisions) else icosphere(subdivisions)
        g.forEachVertex { i, p ->
            val r1 = hash(i * 7 + 1, i * 13 + 3, seed).toFloat()
            val r2 = hash(i * 17 + 5, i * 11 + 9, seed + 1).toFloat()
            p[0] *= 1 - xz + r1 * xz * 2; p[1] *= 1 - y + r2 * y * 2; p[2] *= 1 - xz + r2 * xz * 2
        }
        if (flat) g.computeFlatNormals() else g.computeNormals()
        return g
    }

    /** 113 x 113 lattice over 200 m; the vertex shader lifts it with groundHeight(). */
    fun groundGrid(): Pair<FloatArray, ShortArray> {
        // 113 x 113 vertices retain smooth height displacement while halving ground triangles.
        val n = 112
        val pos = FloatArray((n + 1) * (n + 1) * 2)
        for (j in 0..n) for (i in 0..n) {
            pos[(j * (n + 1) + i) * 2] = -100f + i * (200f / n)
            pos[(j * (n + 1) + i) * 2 + 1] = -100f + j * (200f / n)
        }
        val idx = ShortArray(n * n * 6)
        var k = 0
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i; val b = a + 1; val c = a + n + 1; val d = c + 1
            idx[k++] = a.toShort(); idx[k++] = c.toShort(); idx[k++] = b.toShort()
            idx[k++] = b.toShort(); idx[k++] = c.toShort(); idx[k++] = d.toShort()
        }
        return pos to idx
    }
}

/** Static geometry plus a streaming per-instance matrix buffer. */
class Mesh(data: MeshData, private val instanceCapacity: Int) {
    private val vao = IntArray(1)
    private val instVbo = IntArray(1)
    private val vbo = IntArray(2)
    private val indexCount = data.idx.size

    init {
        GL.glGenVertexArrays(1, vao, 0)
        GL.glGenBuffers(2, vbo, 0)
        GL.glGenBuffers(1, instVbo, 0)
        GL.glBindVertexArray(vao[0])

        val inter = GlUtil.floatBuffer(data.vertexCount * 8)
        for (i in 0 until data.vertexCount) {
            inter.put(data.pos, i * 3, 3); inter.put(data.nor, i * 3, 3); inter.put(data.uv, i * 2, 2)
        }
        inter.position(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0])
        GL.glBufferData(GL.GL_ARRAY_BUFFER, inter.capacity() * 4, inter, GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 32, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 32, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, 32, 24)

        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, instVbo[0])
        GL.glBufferData(GL.GL_ARRAY_BUFFER, instanceCapacity * 64, null, GL.GL_STREAM_DRAW)
        for (k in 0..3) {
            GL.glEnableVertexAttribArray(3 + k)
            GL.glVertexAttribPointer(3 + k, 4, GL.GL_FLOAT, false, 64, k * 16)
            GL.glVertexAttribDivisor(3 + k, 1)
        }

        val idx = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        idx.put(data.idx).position(0)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, vbo[1])
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, indexCount * 2, idx, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
    }

    /** Frees the GPU buffers (used when a quality tier swaps meshes). */
    fun release() {
        GL.glDeleteVertexArrays(1, vao, 0)
        GL.glDeleteBuffers(2, vbo, 0)
        GL.glDeleteBuffers(1, instVbo, 0)
    }

    fun draw(matrices: FloatBuffer, count: Int) {
        if (count <= 0) return
        val n = minOf(count, instanceCapacity)
        matrices.position(0); matrices.limit(n * 16)
        GL.glBindVertexArray(vao[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, instVbo[0])
        // Re-specify (orphan) the store with the new matrices instead of patching it: the GPU may still be reading last frame's
        // copy, and glBufferSubData on a buffer in flight can stall the driver on tiling mobile GPUs.
        GL.glBufferData(GL.GL_ARRAY_BUFFER, n * 64, matrices, GL.GL_STREAM_DRAW)
        GL.glDrawElementsInstanced(GL.GL_TRIANGLES, indexCount, GL.GL_UNSIGNED_SHORT, 0, n)
        GL.glBindVertexArray(0)
        matrices.limit(matrices.capacity())
    }
}
