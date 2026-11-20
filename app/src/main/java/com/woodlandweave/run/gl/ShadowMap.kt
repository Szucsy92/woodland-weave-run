package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import android.opengl.Matrix
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Directional (moon) shadow cascades: depth-only targets fitted around the runner in light space, snapped to
 * whole texels so the shadows never crawl. The textures use hardware comparison, so every tap is a bilinear PCF.
 */
class ShadowMap(val res: Int, val cascades: Int) {
    val tex = IntArray(cascades)
    private val fbo = IntArray(cascades)
    val vp = Array(cascades) { FloatArray(16) }
    var ok = false; private set
    private val view = FloatArray(16)
    private val proj = FloatArray(16)

    fun create() {
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* drop stale errors */ }
        GL.glGenTextures(cascades, tex, 0)
        GL.glGenFramebuffers(cascades, fbo, 0)
        ok = true
        for (c in 0 until cascades) {
            GL.glBindTexture(GL.GL_TEXTURE_2D, tex[c])
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_DEPTH_COMPONENT24, res, res)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_COMPARE_MODE, GL.GL_COMPARE_REF_TO_TEXTURE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[c])
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, tex[c], 0)
            GL.glDrawBuffers(1, intArrayOf(GL.GL_NONE), 0)
            GL.glReadBuffer(GL.GL_NONE)
            if (GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) != GL.GL_FRAMEBUFFER_COMPLETE) ok = false
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        if (GL.glGetError() != GL.GL_NO_ERROR) ok = false
    }

    /**
     * Fits cascade [c] around [cx],[cy],[cz] with half-extent [extent] metres, looking along -[moon] (the unit
     * vector pointing at the moon).
     */
    fun update(c: Int, cx: Float, cy: Float, cz: Float, moon: FloatArray, extent: Float) {
        // Light basis for texel snapping.
        val fx = -moon[0]; val fy = -moon[1]; val fz = -moon[2]
        var rx = fy * 0f - fz * 1f; var ry = fz * 0f - fx * 0f; var rz = fx * 1f - fy * 0f   // cross(f, up)
        val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f); rx /= rl; ry /= rl; rz /= rl
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        val texel = 2f * extent / res
        val a = cx * rx + cy * ry + cz * rz
        val b = cx * ux + cy * uy + cz * uz
        val da = floor(a / texel + 0.5f) * texel - a
        val db = floor(b / texel + 0.5f) * texel - b
        val sx = cx + rx * da + ux * db; val sy = cy + ry * da + uy * db; val sz = cz + rz * da + uz * db
        // Far enough back that every caster of the shadow ring (up to ~100 m towards the light) lies in front of the light's near plane.
        val dist = 130f
        Matrix.setLookAtM(view, 0, sx + moon[0] * dist, sy + moon[1] * dist, sz + moon[2] * dist, sx, sy, sz, 0f, 1f, 0f)
        Matrix.orthoM(proj, 0, -extent, extent, -extent, extent, 1f, 300f)
        Matrix.multiplyMM(vp[c], 0, proj, 0, view, 0)
    }

    fun begin(c: Int) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[c])
        GL.glViewport(0, 0, res, res)
        GL.glDepthMask(true); GL.glEnable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        GL.glColorMask(false, false, false, false)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_POLYGON_OFFSET_FILL)
        GL.glPolygonOffset(2f, 4f)
    }

    fun end() {
        GL.glDisable(GL.GL_POLYGON_OFFSET_FILL)
        GL.glColorMask(true, true, true, true)
    }

    fun release() {
        if (tex[0] != 0) { GL.glDeleteTextures(cascades, tex, 0); GL.glDeleteFramebuffers(cascades, fbo, 0) }
    }
}
