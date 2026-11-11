package com.woodlandweave.run.gl

import android.content.Context
import android.opengl.GLSurfaceView
import com.woodlandweave.run.GameEngine

/** The 3D surface. Rendered at most at 1.5x density: sharp enough, and far kinder to mid-range GPUs. */
class GameView(context: Context, engine: GameEngine) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer(context.applicationContext, engine))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        if (d > 1.5f && w > 0 && h > 0) holder.setFixedSize((w * 1.5f / d).toInt(), (h * 1.5f / d).toInt())
    }
}
