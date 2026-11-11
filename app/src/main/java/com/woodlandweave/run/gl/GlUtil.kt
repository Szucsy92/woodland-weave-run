package com.woodlandweave.run.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun shader(type: Int, src: String): Int {
        val id = GL.glCreateShader(type)
        GL.glShaderSource(id, src)
        GL.glCompileShader(id)
        val ok = IntArray(1)
        GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GL.glGetShaderInfoLog(id) }
        return id
    }

    fun program(vs: String, fs: String): Int {
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, shader(GL.GL_VERTEX_SHADER, vs))
        GL.glAttachShader(p, shader(GL.GL_FRAGMENT_SHADER, fs))
        GL.glLinkProgram(p)
        val ok = IntArray(1)
        GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GL.glGetProgramInfoLog(p) }
        return p
    }

    fun texture(bitmap: Bitmap, repeat: Boolean, mipmaps: Boolean): Int {
        val t = IntArray(1)
        GL.glGenTextures(1, t, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmaps) GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, if (mipmaps) GL.GL_LINEAR_MIPMAP_LINEAR else GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        val wrap = if (repeat) GL.GL_REPEAT else GL.GL_CLAMP_TO_EDGE
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, wrap)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, wrap)
        return t[0]
    }

    fun assetTexture(context: Context, name: String): Int {
        val bmp = context.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val id = texture(bmp, repeat = true, mipmaps = true)
        bmp.recycle()
        return id
    }

    /** Radial white glow; only the alpha channel is sampled by the shaders. */
    fun glowTexture(stops: FloatArray, alphas: IntArray): Int {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(64f, 64f, 64f, IntArray(alphas.size) { (alphas[it] shl 24) or 0xFFFFFF }, stops, Shader.TileMode.CLAMP)
        Canvas(bmp).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val id = texture(bmp, repeat = false, mipmaps = false)
        bmp.recycle()
        return id
    }

    fun whiteTexture(): Int {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFFFFFF.toInt())
        val id = texture(bmp, repeat = false, mipmaps = false)
        bmp.recycle()
        return id
    }

    /** Soft glowing ring with faint uneven streaks (the black hole's accretion veil). */
    fun ringTexture(): Int {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val alphas = intArrayOf(0, 0, (0.55f * 255).toInt(), (0.22f * 255).toInt(), 0)
        paint.shader = RadialGradient(c, c, c, IntArray(5) { (alphas[it] shl 24) or 0xFFFFFF }, floatArrayOf(0f, .4f, .66f, .8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val streak = Paint(Paint.ANTI_ALIAS_FLAG)
        streak.style = Paint.Style.STROKE
        streak.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        for (k in 0 until 14) {
            val a0 = rnd(k.toDouble(), 61.0) * Math.PI * 2
            val len = .3 + rnd(k.toDouble(), 62.0) * .9
            streak.color = ((((.12 + rnd(k.toDouble(), 63.0) * .2) * 255).toInt()) shl 24)
            streak.strokeWidth = (14 + rnd(k.toDouble(), 64.0) * 30).toFloat()
            val r = c * (.55f + rnd(k.toDouble(), 65.0).toFloat() * .3f)
            canvas.drawArc(c - r, c - r, c + r, c + r, Math.toDegrees(a0).toFloat(), Math.toDegrees(len).toFloat(), false, streak)
        }
        val id = texture(bmp, repeat = false, mipmaps = false)
        bmp.recycle()
        return id
    }
}
