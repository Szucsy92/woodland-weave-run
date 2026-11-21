package com.woodlandweave.run.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.woodlandweave.run.GameEngine

/**
 * The 3D surface. Its buffer is at most 2x density; the renderer's effect pass adapts the actual draw resolution
 * (starting at the web build's 1.5x) to what the device can hold at 60 fps.
 */
class GameView(context: Context, engine: GameEngine) : GLSurfaceView(context) {
    companion object { const val MAX_DENSITY = 3.0f }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)  // the scene is drawn into the effect pass's own target; the window needs no depth
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer(context.applicationContext, engine))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        super.surfaceCreated(h)
        // A 90 or 120 Hz screen would otherwise ask for 90-120 renders a second of this scene (more heat, more load, a less
        // steady frame time); 60 is what it is tuned for.
        if (Build.VERSION.SDK_INT >= 30) h.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        if (d > MAX_DENSITY && w > 0 && h > 0) holder.setFixedSize((w * MAX_DENSITY / d).toInt(), (h * MAX_DENSITY / d).toInt())
    }
}
