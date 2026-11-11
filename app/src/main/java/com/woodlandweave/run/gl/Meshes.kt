package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import com.woodlandweave.run.sim.hash
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
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

    /** Lumpy leaf mass; CROWN_SUBDIVISIONS keeps the instance count affordable on phones. */
    fun crown(subdivisions: Int): MeshData {
        val g = icosphere(subdivisions)
        g.forEachVertex { _, v ->
            val n = sin(v[0] * 5.1f + v[1] * 2.3f) * sin(v[2] * 4.7f - v[1] * 3.1f) * .16f + sin(v[0] * 11 + v[2] * 9) * .05f
            val lump = sin(v[0] * 2.1f + 1.3f) * sin(v[2] * 1.7f + v[1] * 2.6f) * .32f + sin(v[1] * 7.7f + v[0] * 6.1f) * cos(v[2] * 8.3f) * .12f
            val k = max(.35f, 1 + n + lump)
            v[0] *= k; v[1] *= k; v[2] *= k
            v[1] *= if (v[1] < 0) .45f else .8f
        }
        g.computeNormals()
        return g
    }

    fun jittered(subdivisions: Int, xz: Float, y: Float, seed: Int): MeshData {
        val g = icosphere(subdivisions)
        g.forEachVertex { i, p ->
            val r1 = hash(i * 7 + 1, i * 13 + 3, seed).toFloat()
            val r2 = hash(i * 17 + 5, i * 11 + 9, seed + 1).toFloat()
            p[0] *= 1 - xz + r1 * xz * 2; p[1] *= 1 - y + r2 * y * 2; p[2] *= 1 - xz + r2 * xz * 2
        }
        g.computeNormals()
        return g
    }

    /** 161 x 161 lattice over 200 m; the vertex shader lifts it with groundHeight(). */
    fun groundGrid(): Pair<FloatArray, ShortArray> {
        val n = 160
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
    private val indexCount = data.idx.size

    init {
        val vbo = IntArray(2)
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

    fun draw(matrices: FloatBuffer, count: Int) {
        if (count <= 0) return
        val n = minOf(count, instanceCapacity)
        matrices.position(0); matrices.limit(n * 16)
        GL.glBindVertexArray(vao[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, instVbo[0])
        GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, n * 64, matrices)
        GL.glDrawElementsInstanced(GL.GL_TRIANGLES, indexCount, GL.GL_UNSIGNED_SHORT, 0, n)
        GL.glBindVertexArray(0)
        matrices.limit(matrices.capacity())
    }
}
