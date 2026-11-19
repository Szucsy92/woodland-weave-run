package com.woodlandweave.run.gl

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * The CSS "glass" panels of the web build blurred whatever was behind them (backdrop-filter). Compose cannot
 * blur the GL surface under it, so the UI publishes where its glass panels are and the effect shader does the
 * blur, saturation, tint and shadow. Everything is in dp, measured from the top left of the screen.
 */
class GlassState {
    class Panel(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        /** Gaussian sigma of the backdrop blur in dp (CSS blur(20px) -> 20). */
        val blurDp: Float,
        /** CSS saturate() amount, 1 = untouched. */
        val saturate: Float,
        /** Draw the soft drop shadow around the panel. */
        val shadow: Boolean,
        /** Overall opacity 0..1 (arrival fade, the pause button's 0.45). */
        val alpha: Float,
    )

    companion object { const val SLOTS = 2 }

    private val slots = AtomicReferenceArray<Panel?>(SLOTS)
    @Volatile var rootWidthDp = 1f
    @Volatile var rootHeightDp = 1f

    fun set(slot: Int, panel: Panel?) { slots.set(slot, panel) }
    fun get(slot: Int): Panel? = slots.get(slot)
}
