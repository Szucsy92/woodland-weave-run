package com.woodlandweave.run.gl

import kotlin.math.cos
import kotlin.math.sin

/** Column-major 4x4 helpers that mirror three.js Euler composition (orders XYZ and YXZ). */
object Mat {
    const val XYZ = 0
    const val YXZ = 1

    private fun rx(a: Double) = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    private fun ry(a: Double) = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    private fun rz(a: Double) = doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        return o
    }

    /** M = T * R(euler, order) * S, written at out[offset .. offset+15]. */
    fun compose(
        out: FloatArray, offset: Int,
        px: Double, py: Double, pz: Double,
        ex: Double, ey: Double, ez: Double, order: Int,
        sx: Double, sy: Double, sz: Double,
    ) {
        val r = if (order == YXZ) mul3(mul3(ry(ey), rx(ex)), rz(ez)) else mul3(mul3(rx(ex), ry(ey)), rz(ez))
        val o = offset
        out[o] = (r[0] * sx).toFloat(); out[o + 1] = (r[3] * sx).toFloat(); out[o + 2] = (r[6] * sx).toFloat(); out[o + 3] = 0f
        out[o + 4] = (r[1] * sy).toFloat(); out[o + 5] = (r[4] * sy).toFloat(); out[o + 6] = (r[7] * sy).toFloat(); out[o + 7] = 0f
        out[o + 8] = (r[2] * sz).toFloat(); out[o + 9] = (r[5] * sz).toFloat(); out[o + 10] = (r[8] * sz).toFloat(); out[o + 11] = 0f
        out[o + 12] = px.toFloat(); out[o + 13] = py.toFloat(); out[o + 14] = pz.toFloat(); out[o + 15] = 1f
    }

    fun translate(out: FloatArray, offset: Int, x: Double, y: Double, z: Double, sx: Double = 1.0, sy: Double = 1.0, sz: Double = 1.0) =
        compose(out, offset, x, y, z, 0.0, 0.0, 0.0, XYZ, sx, sy, sz)
}
