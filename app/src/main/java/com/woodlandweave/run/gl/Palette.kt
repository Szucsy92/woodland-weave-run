package com.woodlandweave.run.gl

import kotlin.math.pow

/** The forest palette from styles.css. Lighting runs in linear space, output is gamma 2.2. */
object Palette {
    // Match Three.js ColorManagement's IEC 61966-2-1 sRGB transfer curve.
    // A simple pow(2.2) approximation shifts dark forest tones noticeably.
    private fun decodeSrgb(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    fun encodeSrgb(v: Float) = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f
    fun encodeSrgb(rgb: FloatArray) = FloatArray(3) { encodeSrgb(rgb[it].coerceIn(0f, 1f)) }

    private fun lin(hex: Int) = floatArrayOf(
        decodeSrgb(((hex shr 16) and 255) / 255f),
        decodeSrgb(((hex shr 8) and 255) / 255f),
        decodeSrgb((hex and 255) / 255f),
    )

    fun srgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

    val fog = lin(0x0b1424); val moon = lin(0x6f86b8); val lamp = lin(0xffe6b8)
    val green = lin(0x24d975); val purple = lin(0x883cdb); val lavender = lin(0xc8b8e0); val lemon = lin(0xf6ff4f)
    val trunk = lin(0xb8a58c); val ground = lin(0x9aa896); val crown = lin(0x17251c)
    val rock = lin(0x3a4048); val plant = lin(0x22301f)
    val ember = lin(0xff2a1a); val pursuer = lin(0xff1e10); val blood = lin(0x3a0605)
    val freezer = lin(0x2b6cff); val ghost = lin(0x2ee88a); val shock = lin(0xb04dff); val secret = lin(0x7fe8d8)
    val dayFog = lin(0x8f9b98); val dayMoon = lin(0xdfe6e2); val ghostGlow = lin(0xeaf6ff)

    fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    /** The odd, saturated colours the wormhole paints its relics and spores with. */
    val odd = intArrayOf(0xff6bd6, 0x6bffe1, 0xffd36b, 0x9b8cff, 0x8dff6b, 0xff7a6b, 0x6bb8ff, 0xe2ff6b).map { lin(it) }
}

/** Cheap deterministic noise, identical to the web build's rnd(). */
fun rnd(i: Double, k: Double): Double {
    val v = kotlin.math.sin(i * 127.1 + k * 311.7) * 43758.5453
    return v - kotlin.math.floor(v)
}
