package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import com.woodlandweave.run.sim.SHOCKWAVE_GAP
import com.woodlandweave.run.sim.SHOCKWAVE_RINGS
import com.woodlandweave.run.sim.SHOCKWAVE_SPEED
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Off-screen scene target (4x MSAA, like the web build's composer), resolved into a texture and drawn
 * through the effect shader.
 *
 * The web build's composer rendered into a half-float target: lighting, additive glows and fog all blend in
 * linear light and only the last pass encodes to sRGB. This does the same with an RGBA16F target
 * ([linear] = true). Devices that cannot render to RGBA16F fall back to an RGBA8 target that holds
 * gamma-encoded colour (the previous behaviour); the shaders switch with the `uEncode` / `uSrcSrgb` flags.
 *
 * The target can be smaller than the surface ([setScale]) so the renderer can trade resolution for frame
 * rate; the final pass always draws at the surface's own resolution.
 */
class PostFx {
    /** True when the scene target is linear RGBA16F. Valid after [resize]. */
    var linear = false; private set

    private var program = 0
    private var msaaFbo = 0; private var colorRb = 0; private var depthRb = 0
    private var resolveFbo = 0; private var resolveTex = 0
    private var sw = 1; private var sh = 1          // surface
    private var w = 1; private var h = 1            // render target
    private var scale = 1f
    private var hdrAllowed = true
    private val locs = HashMap<String, Int>()
    private val glassRect = FloatArray(8)
    private val glassParam = FloatArray(8)

    val targetWidth get() = w
    val targetHeight get() = h

    fun create() {
        program = GlUtil.program(Shaders.POST_VS, Shaders.postFs(SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED))
        locs.clear()
        msaaFbo = 0; colorRb = 0; depthRb = 0; resolveFbo = 0; resolveTex = 0
        val ext = GL.glGetString(GL.GL_EXTENSIONS).orEmpty()
        val ver = GL.glGetString(GL.GL_VERSION).orEmpty()
        hdrAllowed = ext.contains("GL_EXT_color_buffer_float") || ver.contains("OpenGL ES 3.2")
    }

    fun resize(width: Int, height: Int, newScale: Float = scale) {
        sw = max(2, width); sh = max(2, height)
        scale = newScale.coerceIn(0.4f, 1f)
        rebuild()
    }

    /** 1 = render at the surface's resolution, below 1 = render smaller and scale up in the final pass. */
    fun setScale(s: Float) {
        val c = s.coerceIn(0.4f, 1f)
        if (kotlin.math.abs(c - scale) < 0.01f) return
        scale = c
        rebuild()
    }

    private fun rebuild() {
        w = max(2, (sw * scale).toInt()); h = max(2, (sh * scale).toInt())
        destroyTargets()
        var ok = hdrAllowed && build(true)
        if (!ok) { destroyTargets(); ok = build(false); linear = false } else linear = true
        if (!ok) throw IllegalStateException("Scene framebuffer is incomplete")
    }

    private fun destroyTargets() {
        if (msaaFbo != 0) {
            GL.glDeleteFramebuffers(2, intArrayOf(msaaFbo, resolveFbo), 0)
            GL.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthRb), 0)
            GL.glDeleteTextures(1, intArrayOf(resolveTex), 0)
        }
        msaaFbo = 0; colorRb = 0; depthRb = 0; resolveFbo = 0; resolveTex = 0
    }

    private fun build(hdr: Boolean): Boolean {
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* drop stale errors */ }
        val maxSamples = IntArray(1)
        GL.glGetIntegerv(GL.GL_MAX_SAMPLES, maxSamples, 0)
        val samples = min(4, maxSamples[0])
        val fmt = if (hdr) GL.GL_RGBA16F else GL.GL_RGBA8
        val ids = IntArray(2)
        GL.glGenRenderbuffers(2, ids, 0)
        colorRb = ids[0]; depthRb = ids[1]
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, colorRb)
        GL.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, fmt, w, h)
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, depthRb)
        GL.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, GL.GL_DEPTH_COMPONENT24, w, h)
        val fb = IntArray(2)
        GL.glGenFramebuffers(2, fb, 0)
        msaaFbo = fb[0]; resolveFbo = fb[1]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, msaaFbo)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_RENDERBUFFER, colorRb)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depthRb)
        val msaaOk = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE

        val tex = IntArray(1)
        GL.glGenTextures(1, tex, 0)
        resolveTex = tex[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, resolveTex)
        // Full mip chain: the glass panels blur through the pre-filtered levels.
        val levels = 1 + floor(ln(max(w, h).toDouble()) / ln(2.0)).toInt()
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, fmt, w, h)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, resolveFbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, resolveTex, 0)
        val resolveOk = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        return msaaOk && resolveOk && GL.glGetError() == GL.GL_NO_ERROR
    }

    fun bind() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, msaaFbo)
        GL.glViewport(0, 0, w, h)
    }

    private fun loc(n: String) = locs.getOrPut(n) { GL.glGetUniformLocation(program, n) }

    fun present(e: EffectState, glass: GlassState?) {
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, msaaFbo)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GL.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, sw, sh)
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)

        // Which glass panels are on screen right now (Compose publishes them in dp, top left origin).
        var glassCount = 0
        val rootW = glass?.rootWidthDp ?: 1f; val rootH = glass?.rootHeightDp ?: 1f
        if (glass != null) {
            for (slot in 0 until GlassState.SLOTS) {
                val p = glass.get(slot) ?: continue
                if (p.alpha <= 0.003f || p.right <= p.left || p.bottom <= p.top) continue
                val k = glassCount * 4
                glassRect[k] = p.left; glassRect[k + 1] = rootH - p.bottom; glassRect[k + 2] = p.right; glassRect[k + 3] = rootH - p.top
                glassParam[k] = p.blurDp; glassParam[k + 1] = p.saturate; glassParam[k + 2] = if (p.shadow) 1f else 0f; glassParam[k + 3] = p.alpha
                glassCount++
            }
        }

        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, resolveTex)
        if (glassCount > 0) GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glUseProgram(program)
        GL.glUniform1i(loc("tDiffuse"), 0)
        GL.glUniform1f(loc("uSrcSrgb"), if (linear) 0f else 1f)
        GL.glUniform1f(loc("blur"), e.blur.toFloat()); GL.glUniform1f(loc("glitch"), e.glitch.toFloat())
        GL.glUniform1f(loc("frost"), e.frost.toFloat()); GL.glUniform1f(loc("lens"), e.lens.toFloat())
        GL.glUniform1f(loc("wave"), e.wave.toFloat()); GL.glUniform1f(loc("waveT"), e.waveT.toFloat())
        GL.glUniform1f(loc("warp"), e.warp.toFloat()); GL.glUniform1f(loc("flash"), e.flash.toFloat())
        GL.glUniform1f(loc("hue"), e.hue.toFloat()); GL.glUniform1f(loc("time"), e.time.toFloat())
        GL.glUniform4f(loc("hole"), e.hole[0], e.hole[1], e.hole[2], e.hole[3])
        GL.glUniform1fv(loc("reach"), e.reach.size, e.reach, 0)
        GL.glUniform2f(loc("resolution"), w.toFloat(), h.toFloat())
        GL.glUniform2f(loc("uRootDp"), rootW, rootH)
        GL.glUniform1f(loc("uGlassCount"), glassCount.toFloat())
        GL.glUniform4fv(loc("uGlassRect"), 2, glassRect, 0)
        GL.glUniform4fv(loc("uGlassParam"), 2, glassParam, 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glEnable(GL.GL_DEPTH_TEST)
    }
}
