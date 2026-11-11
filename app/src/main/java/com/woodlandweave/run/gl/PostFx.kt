package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import com.woodlandweave.run.sim.SHOCKWAVE_GAP
import com.woodlandweave.run.sim.SHOCKWAVE_RINGS
import com.woodlandweave.run.sim.SHOCKWAVE_SPEED
import kotlin.math.min

/**
 * Off-screen scene target (4x MSAA, like the web build's composer), resolved into a texture and
 * drawn through the effect shader.
 */
class PostFx {
    private var program = 0
    private var msaaFbo = 0; private var colorRb = 0; private var depthRb = 0
    private var resolveFbo = 0; private var resolveTex = 0
    private var w = 1; private var h = 1
    private val locs = HashMap<String, Int>()

    fun create() {
        program = GlUtil.program(Shaders.POST_VS, Shaders.postFs(SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED))
        locs.clear()
        msaaFbo = 0; colorRb = 0; depthRb = 0; resolveFbo = 0; resolveTex = 0
    }

    fun resize(width: Int, height: Int) {
        w = width; h = height
        if (msaaFbo != 0) {
            GL.glDeleteFramebuffers(2, intArrayOf(msaaFbo, resolveFbo), 0)
            GL.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthRb), 0)
            GL.glDeleteTextures(1, intArrayOf(resolveTex), 0)
        }
        val maxSamples = IntArray(1)
        GL.glGetIntegerv(GL.GL_MAX_SAMPLES, maxSamples, 0)
        val samples = min(4, maxSamples[0])
        val ids = IntArray(2)
        GL.glGenRenderbuffers(2, ids, 0)
        colorRb = ids[0]; depthRb = ids[1]
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, colorRb)
        GL.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, GL.GL_RGBA8, w, h)
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, depthRb)
        GL.glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, GL.GL_DEPTH_COMPONENT24, w, h)
        val fb = IntArray(2)
        GL.glGenFramebuffers(2, fb, 0)
        msaaFbo = fb[0]; resolveFbo = fb[1]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, msaaFbo)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_RENDERBUFFER, colorRb)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depthRb)
        val tex = IntArray(1)
        GL.glGenTextures(1, tex, 0)
        resolveTex = tex[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, resolveTex)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, resolveFbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, resolveTex, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, msaaFbo)
        GL.glViewport(0, 0, w, h)
    }

    private fun loc(n: String) = locs.getOrPut(n) { GL.glGetUniformLocation(program, n) }

    fun present(e: EffectState) {
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, msaaFbo)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GL.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, w, h)
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, resolveTex)
        GL.glUniform1i(loc("tDiffuse"), 0)
        GL.glUniform1f(loc("blur"), e.blur.toFloat()); GL.glUniform1f(loc("glitch"), e.glitch.toFloat())
        GL.glUniform1f(loc("frost"), e.frost.toFloat()); GL.glUniform1f(loc("lens"), e.lens.toFloat())
        GL.glUniform1f(loc("wave"), e.wave.toFloat()); GL.glUniform1f(loc("waveT"), e.waveT.toFloat())
        GL.glUniform1f(loc("warp"), e.warp.toFloat()); GL.glUniform1f(loc("flash"), e.flash.toFloat())
        GL.glUniform1f(loc("hue"), e.hue.toFloat()); GL.glUniform1f(loc("time"), e.time.toFloat())
        GL.glUniform4f(loc("hole"), e.hole[0], e.hole[1], e.hole[2], e.hole[3])
        GL.glUniform1fv(loc("reach"), e.reach.size, e.reach, 0)
        GL.glUniform2f(loc("resolution"), w.toFloat(), h.toFloat())
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glEnable(GL.GL_DEPTH_TEST)
    }
}
