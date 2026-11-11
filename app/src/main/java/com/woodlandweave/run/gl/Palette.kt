package com.woodlandweave.run.gl

import kotlin.math.pow

/** The forest palette from styles.css. Lighting runs in linear space, output is gamma 2.2. */
object Palette {
    private fun lin(hex: Int) = floatArrayOf(
        (((hex shr 16) and 255) / 255f).pow(2.2f),
        (((hex shr 8) and 255) / 255f).pow(2.2f),
        ((hex and 255) / 255f).pow(2.2f),
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
