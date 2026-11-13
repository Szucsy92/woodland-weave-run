package com.woodlandweave.run.gl

import android.content.Context
import android.opengl.GLSurfaceView
import com.woodlandweave.run.GameEngine

/**
 * The 3D surface. Its buffer is at most 2x density; the renderer's effect pass adapts the actual draw resolution
 * (starting at the web build's 1.5x) to what the device can hold at 60 fps.
 */
class GameView(context: Context, engine: GameEngine) : GLSurfaceView(context) {
    companion object { const val MAX_DENSITY = 2.0f }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)  // the scene is drawn into the effect pass's own target; the window needs no depth
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer(context.applicationContext, engine))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        if (d > MAX_DENSITY && w > 0 && h > 0) holder.setFixedSize((w * MAX_DENSITY / d).toInt(), (h * MAX_DENSITY / d).toInt())
    }
}
