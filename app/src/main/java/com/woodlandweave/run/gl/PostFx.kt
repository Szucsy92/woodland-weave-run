package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL
import com.woodlandweave.run.sim.SHOCKWAVE_GAP
import com.woodlandweave.run.sim.SHOCKWAVE_RINGS
import com.woodlandweave.run.sim.SHOCKWAVE_SPEED
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** What the renderer hands the effect pass each frame (matrices, lights, the shadow map). */
class PostParams {
    val vp = FloatArray(16)
    val invVp = FloatArray(16)
    val proj = FloatArray(16)
    var camX = 0f; var camY = 0f; var camZ = 0f
    var tanHalf = 1f; var aspect = 1f
    var near = 0.1f; var far = 220f
    val moonDir = FloatArray(3)
    val moonCol = FloatArray(3)
    var shadowTex0 = 0
    val lightVp0 = FloatArray(16)
    var motion = 0f
    var tune = FxTuning()
    var bloomThreshold = 0.3f
    var sharpen = 0.3f
    /** Day/night adjustments, 1 = night. */
    var exposureScale = 1f; var bloomScale = 1f; var shaftScale = 1f
    /** 0 = every effect as designed. 1 = a shorter beam march. 2 = also no depth of field or motion blur. Raised by the adaptive scaler only on a device that cannot hold 60 fps at the lowest resolution. */
    var lite = 0
}

/**
 * Off-screen scene target (4x MSAA, like the web build's composer), resolved into a texture and drawn through the
 * effect shader. Linear RGBA16F like the web composer's half-float target; RGBA8 (gamma encoded) fallback.
 *
 * On higher tiers the same pass chain also resolves the depth buffer and runs bloom, SSAO, light shafts,
 * depth of field and film tone mapping. If a feature's shader fails to compile on a device, the tier silently
 * drops ([effective]) instead of crashing.
 */
class PostFx {
    /** True when the scene target is linear RGBA16F. Valid after [resize]. */
    var linear = false; private set
    /** The tier this device really runs (may be lower than requested). */
    var effective = Features(Quality.LOW); private set
    var depthSupported = true; private set

    private var program = 0
    private var prepassProg = 0
    private var prepassFbo = 0; private var prepassTex = 0
    // The beams are marched and filtered at a quarter of the scene's pixels: [0] = the march, [1] = its depth-weighted 4x4 average.
    private var shaftProg = 0; private var shaftBlurProg = 0
    private var shaftFbo = IntArray(2); private var shaftTex = IntArray(2)
    private var shaftZTex = 0   // second attachment of the march target: the depth each beam texel was marched to
    private var shaftW = 2; private var shaftH = 2
    // Temporal accumulation: two history pairs (beams, and the depth they belong to), written alternately; [histCur] holds the latest.
    private var histFbo = IntArray(2); private var histTex = IntArray(2); private var histZ = IntArray(2)
    private var histCur = 0; private var histValid = false; private var jitterSeq = 0
    private val prevVp = FloatArray(16)
    private var shaftLowBroken = false   // a GL error in the small passes: the beams are marched at full size again, as before
    private var ssaoProg = 0; private var aoBlurProg = 0; private var bloomDownProg = 0; private var bloomUpProg = 0
    private var msaaFbo = 0; private var colorRb = 0; private var depthRb = 0
    private var resolveFbo = 0; private var resolveTex = 0; private var depthTex = 0
    private var aoFbo = IntArray(2); private var aoTex = IntArray(2)
    private var bloomFbo = IntArray(0); private var bloomTex = IntArray(0)
    private var bloomW = IntArray(0); private var bloomH = IntArray(0)
    private var sw = 1; private var sh = 1
    private var w = 1; private var h = 1
    private var scale = 1f
    private var hdrAllowed = true
    private var requested = Features(Quality.LOW)
    private var blitChecks = 3
    private val locs = android.util.SparseArray<HashMap<String, Int>>()
    private val glassRect = FloatArray(8)
    private val glassParam = FloatArray(8)

    val targetWidth get() = w
    val targetHeight get() = h

    fun create() {
        locs.clear()
        program = 0; prepassProg = 0; prepassFbo = 0; prepassTex = 0
        shaftProg = 0; shaftBlurProg = 0; shaftFbo = IntArray(2); shaftTex = IntArray(2); shaftZTex = 0; shaftLowBroken = false
        histFbo = IntArray(2); histTex = IntArray(2); histZ = IntArray(2); histCur = 0; histValid = false
        ssaoProg = 0; aoBlurProg = 0; bloomDownProg = 0; bloomUpProg = 0
        msaaFbo = 0; colorRb = 0; depthRb = 0; resolveFbo = 0; resolveTex = 0; depthTex = 0
        aoFbo = IntArray(2); aoTex = IntArray(2); bloomFbo = IntArray(0); bloomTex = IntArray(0)
        val ext = GL.glGetString(GL.GL_EXTENSIONS).orEmpty()
        val ver = GL.glGetString(GL.GL_VERSION).orEmpty()
        hdrAllowed = ext.contains("GL_EXT_color_buffer_float") || ver.contains("OpenGL ES 3.2")
        buildPrograms(requested)
    }

    /** Selects a tier. Returns the tier actually in effect. */
    fun configure(f: Features): Features {
        requested = f
        buildPrograms(f)
        if (sw > 2 || sh > 2) rebuild()
        return effective
    }

    private fun clampForDevice(f: Features): Features {
        var q = f.quality
        if (!hdrAllowed && q != Quality.LOW) q = Quality.LOW        // the extras need a linear float target
        if (!depthSupported && q.ordinal > Quality.MEDIUM.ordinal) q = Quality.MEDIUM
        // Beams need the resolved depth and a linear float target on every tier; without them the tier simply has no beams.
        val depthOk = depthSupported && hdrAllowed
        return if (q == f.quality && depthOk == f.depthOk) f else Features(q, depthOk)
    }

    private fun buildPrograms(f: Features) {
        var feat = clampForDevice(f)
        locs.clear()
        var p = 0
        while (true) {
            p = GlUtil.programOrNull(Shaders.POST_VS, Shaders.postFs(SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED, feat.postDefines()))
            if (p != 0 || feat.quality == Quality.LOW) break
            feat = Features(Quality.values()[feat.quality.ordinal - 1], feat.depthOk)
        }
        if (p == 0) throw IllegalStateException("Effect shader failed to compile")
        program = p
        if (prepassProg != 0) GL.glDeleteProgram(prepassProg)
        prepassProg = if (feat.needsDepth) GlUtil.programOrNull(Shaders.POST_VS, Shaders.postFs(
            SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED, feat.postDefines() + "#define Q_PREPASS\n"
        )) else 0
        if (shaftProg != 0) GL.glDeleteProgram(shaftProg)
        if (shaftBlurProg != 0) GL.glDeleteProgram(shaftBlurProg)
        shaftProg = 0; shaftBlurProg = 0
        if (feat.needsDepth && feat.shafts && !shaftLowBroken) {
            val base = feat.postDefines()
            shaftProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.postFs(SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED, base + "#define Q_SHAFTMARCH\n"))
            shaftBlurProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.postFs(SHOCKWAVE_RINGS, SHOCKWAVE_GAP, SHOCKWAVE_SPEED, base + "#define Q_SHAFTBLUR\n"))
            if (shaftProg == 0 || shaftBlurProg == 0) {
                if (shaftProg != 0) GL.glDeleteProgram(shaftProg)
                if (shaftBlurProg != 0) GL.glDeleteProgram(shaftBlurProg)
                shaftProg = 0; shaftBlurProg = 0
            }
        }
        ssaoProg = 0; aoBlurProg = 0; bloomDownProg = 0; bloomUpProg = 0
        if (feat.bloom) {
            bloomDownProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.BLOOM_DOWN_FS)
            bloomUpProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.BLOOM_UP_FS)
            if (bloomDownProg == 0 || bloomUpProg == 0) { bloomDownProg = 0; bloomUpProg = 0 }
        }
        if (feat.ssao) {
            ssaoProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.SSAO_FS.replace("#version 300 es\n", "#version 300 es\n#define SSAO_N ${feat.ssaoSamples}\n"))
            aoBlurProg = GlUtil.programOrNull(Shaders.POST_VS, Shaders.AO_BLUR_FS)
            if (ssaoProg == 0 || aoBlurProg == 0) { ssaoProg = 0; aoBlurProg = 0 }
        }
        effective = feat
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
        var attempts = 0
        while (true) {
            destroyTargets()
            val wantDepth = effective.needsDepth && depthSupported
            var ok = hdrAllowed && build(true, wantDepth)
            if (ok) linear = true
            if (!ok && wantDepth) {
                destroyTargets()
                ok = hdrAllowed && build(true, false)
                if (ok) { linear = true; depthSupported = false }
            }
            if (!ok) {
                destroyTargets()
                hdrAllowed = false
                if (wantDepth) depthSupported = false
                ok = build(false, false); linear = false
            }
            if (!ok) throw IllegalStateException("Scene framebuffer is incomplete")
            // If the device cannot do the requested tier (no float target, no depth resolve), drop to what it can.
            val want = clampForDevice(requested)
            if ((want.quality == effective.quality && want.needsDepth == effective.needsDepth) || attempts++ >= 2) break
            buildPrograms(requested)
        }
        buildExtraTargets()
    }

    private fun destroyTargets() {
        if (msaaFbo != 0) {
            GL.glDeleteFramebuffers(2, intArrayOf(msaaFbo, resolveFbo), 0)
            GL.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthRb), 0)
            GL.glDeleteTextures(1, intArrayOf(resolveTex), 0)
            if (depthTex != 0) GL.glDeleteTextures(1, intArrayOf(depthTex), 0)
        }
        if (prepassTex != 0) {
            GL.glDeleteFramebuffers(1, intArrayOf(prepassFbo), 0)
            GL.glDeleteTextures(1, intArrayOf(prepassTex), 0)
        }
        destroyShaftTargets()
        if (aoTex[0] != 0) { GL.glDeleteFramebuffers(2, aoFbo, 0); GL.glDeleteTextures(2, aoTex, 0) }
        if (bloomTex.isNotEmpty() && bloomTex[0] != 0) { GL.glDeleteFramebuffers(bloomFbo.size, bloomFbo, 0); GL.glDeleteTextures(bloomTex.size, bloomTex, 0) }
        msaaFbo = 0; colorRb = 0; depthRb = 0; resolveFbo = 0; resolveTex = 0; depthTex = 0
        prepassFbo = 0; prepassTex = 0
        aoFbo = IntArray(2); aoTex = IntArray(2); bloomFbo = IntArray(0); bloomTex = IntArray(0)
    }

    private fun build(hdr: Boolean, withDepthTex: Boolean): Boolean {
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* drop stale errors */ }
        val maxSamples = IntArray(1)
        GL.glGetIntegerv(GL.GL_MAX_SAMPLES, maxSamples, 0)
        // 2x MSAA retains clean silhouettes while halving the multisample colour/depth
        // traffic versus 4x. Resolution scaling handles the remaining low-end pressure.
        val samples = min(2, maxSamples[0]).coerceAtLeast(1)
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
        if (withDepthTex) {
            GL.glGenTextures(1, tex, 0)
            depthTex = tex[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, depthTex)
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_DEPTH_COMPONENT24, w, h)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, depthTex, 0)
        }
        val resolveOk = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        return msaaOk && resolveOk && GL.glGetError() == GL.GL_NO_ERROR
    }

    private fun smallTex(fmt: Int, tw: Int, th: Int, fbo: IntArray, tex: IntArray, i: Int, levels: Int = 1): Boolean {
        val t = IntArray(1); val f = IntArray(1)
        GL.glGenTextures(1, t, 0); GL.glGenFramebuffers(1, f, 0)
        tex[i] = t[0]; fbo[i] = f[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, t[0])
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, fmt, tw, th)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, f[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, t[0], 0)
        return GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
    }

    private fun destroyShaftTargets() {
        if (shaftTex[0] != 0 || shaftTex[1] != 0) { GL.glDeleteFramebuffers(2, shaftFbo, 0); GL.glDeleteTextures(2, shaftTex, 0) }
        if (histTex[0] != 0 || histTex[1] != 0) {
            GL.glDeleteFramebuffers(2, histFbo, 0); GL.glDeleteTextures(2, histTex, 0); GL.glDeleteTextures(2, histZ, 0)
        }
        if (shaftZTex != 0) GL.glDeleteTextures(1, intArrayOf(shaftZTex), 0)
        shaftFbo = IntArray(2); shaftTex = IntArray(2); shaftZTex = 0
        histFbo = IntArray(2); histTex = IntArray(2); histZ = IntArray(2)
        histCur = 0; histValid = false
    }

    /** The march target's second attachment (see [shaftZTex]); the march writes the beams and their depth in one pass. */
    private fun shaftZTarget(): Boolean {
        val t = IntArray(1)
        GL.glGenTextures(1, t, 0)
        shaftZTex = t[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, t[0])
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA16F, shaftW, shaftH)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, shaftFbo[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT1, GL.GL_TEXTURE_2D, t[0], 0)
        GL.glDrawBuffers(2, intArrayOf(GL.GL_COLOR_ATTACHMENT0, GL.GL_COLOR_ATTACHMENT1), 0)
        return GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
    }

    /** One history pair: the beams and the depth they belong to, as two colour attachments of one framebuffer. */
    private fun histTarget(i: Int): Boolean {
        val t = IntArray(2); val f = IntArray(1)
        GL.glGenTextures(2, t, 0); GL.glGenFramebuffers(1, f, 0)
        histTex[i] = t[0]; histZ[i] = t[1]; histFbo[i] = f[0]
        for (k in 0..1) {
            GL.glBindTexture(GL.GL_TEXTURE_2D, t[k])
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA16F, shaftW, shaftH)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, f[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, t[0], 0)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT1, GL.GL_TEXTURE_2D, t[1], 0)
        GL.glDrawBuffers(2, intArrayOf(GL.GL_COLOR_ATTACHMENT0, GL.GL_COLOR_ATTACHMENT1), 0)
        return GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
    }

    /** Bloom chain and AO targets for the current tier. */
    private fun buildExtraTargets() {
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* drop stale errors */ }
        val f = effective
        if (f.bloom && bloomDownProg != 0 && linear) {
            val n = f.bloomLevels
            bloomFbo = IntArray(n); bloomTex = IntArray(n); bloomW = IntArray(n); bloomH = IntArray(n)
            var ok = true
            for (i in 0 until n) {
                bloomW[i] = max(2, w shr (i + 1)); bloomH[i] = max(2, h shr (i + 1))
                if (!smallTex(GL.GL_RGBA16F, bloomW[i], bloomH[i], bloomFbo, bloomTex, i)) ok = false
            }
            if (!ok) { bloomDownProg = 0; bloomUpProg = 0 }
        }
        if (prepassProg != 0 && linear && depthTex != 0) {
            val pf = IntArray(1); val pt = IntArray(1)
            if (smallTex(GL.GL_RGBA16F, w, h, pf, pt, 0)) {
                prepassFbo = pf[0]; prepassTex = pt[0]
            } else {
                if (pf[0] != 0) GL.glDeleteFramebuffers(1, pf, 0)
                if (pt[0] != 0) GL.glDeleteTextures(1, pt, 0)
                prepassProg = 0
            }
        }
        if (prepassProg != 0 && shaftProg != 0 && shaftBlurProg != 0 && linear && depthTex != 0) {
            shaftW = max(2, w / 2); shaftH = max(2, h / 2)
            val ok = smallTex(GL.GL_RGBA16F, shaftW, shaftH, shaftFbo, shaftTex, 0) && smallTex(GL.GL_RGBA16F, shaftW, shaftH, shaftFbo, shaftTex, 1) &&
                shaftZTarget() && histTarget(0) && histTarget(1)
            if (!ok) {
                destroyShaftTargets()
                GL.glDeleteProgram(shaftProg); GL.glDeleteProgram(shaftBlurProg)
                shaftProg = 0; shaftBlurProg = 0
            }
        }
        if (f.ssao && ssaoProg != 0 && depthTex != 0) {
            val aw = max(2, w / 2); val ah = max(2, h / 2)
            var ok = true
            for (i in 0 until 2) if (!smallTex(GL.GL_R8, aw, ah, aoFbo, aoTex, i)) ok = false
            if (!ok) { ssaoProg = 0; aoBlurProg = 0 }
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, msaaFbo)
        GL.glViewport(0, 0, w, h)
    }

    private fun loc(p: Int, n: String): Int {
        val m = locs.get(p) ?: HashMap<String, Int>().also { locs.put(p, it) }
        return m.getOrPut(n) { GL.glGetUniformLocation(p, n) }   // no per-call string building
    }
    private fun loc(n: String) = loc(program, n)

    private fun tex(unit: Int, id: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id)
    }

    private fun bloomPass(threshold: Float) {
        val n = bloomTex.size
        GL.glDisable(GL.GL_BLEND)
        GL.glUseProgram(bloomDownProg)
        GL.glUniform1i(loc(bloomDownProg, "uSrc"), 0)
        GL.glUniform1f(loc(bloomDownProg, "uThreshold"), threshold)
        for (i in 0 until n) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[i])
            GL.glViewport(0, 0, bloomW[i], bloomH[i])
            val srcW = if (i == 0) w else bloomW[i - 1]; val srcH = if (i == 0) h else bloomH[i - 1]
            tex(0, if (i == 0) resolveTex else bloomTex[i - 1])
            GL.glUniform2f(loc(bloomDownProg, "uTexel"), 1f / srcW, 1f / srcH)
            GL.glUniform1f(loc(bloomDownProg, "uPre"), if (i == 0) 1f else 0f)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }
        GL.glUseProgram(bloomUpProg)
        GL.glUniform1i(loc(bloomUpProg, "uSrc"), 0)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE)
        for (i in n - 2 downTo 0) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[i])
            GL.glViewport(0, 0, bloomW[i], bloomH[i])
            tex(0, bloomTex[i + 1])
            GL.glUniform2f(loc(bloomUpProg, "uTexel"), 1f / bloomW[i + 1], 1f / bloomH[i + 1])
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }
        GL.glDisable(GL.GL_BLEND)
    }

    private fun ssaoPass(pp: PostParams) {
        val aw = max(2, w / 2); val ah = max(2, h / 2)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, aoFbo[0])
        GL.glViewport(0, 0, aw, ah)
        GL.glUseProgram(ssaoProg)
        tex(0, depthTex)
        GL.glUniform1i(loc(ssaoProg, "uDepth"), 0)
        GL.glUniformMatrix4fv(loc(ssaoProg, "uProj"), 1, false, pp.proj, 0)
        GL.glUniform2f(loc(ssaoProg, "uNF"), pp.near, pp.far)
        GL.glUniform2f(loc(ssaoProg, "uTanAspect"), pp.tanHalf * pp.aspect, pp.tanHalf)
        GL.glUniform2f(loc(ssaoProg, "uTexel"), 1f / w, 1f / h)
        GL.glUniform1f(loc(ssaoProg, "uRadius"), effective.ssaoRadius)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, aoFbo[1])
        GL.glUseProgram(aoBlurProg)
        tex(0, aoTex[0]); tex(1, depthTex)
        GL.glUniform1i(loc(aoBlurProg, "uAoIn"), 0); GL.glUniform1i(loc(aoBlurProg, "uDepth"), 1)
        GL.glUniform2f(loc(aoBlurProg, "uTexel"), 1f / aw, 1f / ah)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * The light beams, at a quarter of the scene's pixels: march (the same world-anchored steps and Bayer offsets as before), then a
     * depth-weighted 4x4 average that cancels the Bayer lattice exactly. The prepass upsamples the result with depth weights.
     * Returns false (and the prepass marches the beams itself, as before) if the small targets are missing or a GL call fails.
     */
    private fun shaftLowPass(pp: PostParams, f: Features, steps: Float): Boolean {
        if (shaftLowBroken || shaftProg == 0 || shaftBlurProg == 0 || shaftTex[0] == 0 || shaftTex[1] == 0 || shaftZTex == 0 || histTex[0] == 0 || histTex[1] == 0 || depthTex == 0) return false
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* isolate the small passes' errors */ }
        GL.glViewport(0, 0, shaftW, shaftH)
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        // 1. March.
        var p = shaftProg
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, shaftFbo[0])
        GL.glUseProgram(p)
        tex(1, depthTex); GL.glUniform1i(loc(p, "uDepth"), 1)
        GL.glUniform2f(loc(p, "uNF"), pp.near, pp.far)
        GL.glUniform2f(loc(p, "uTanAspect"), pp.tanHalf * pp.aspect, pp.tanHalf)
        GL.glUniformMatrix4fv(loc(p, "uInvVP"), 1, false, pp.invVp, 0)
        GL.glUniform3f(loc(p, "uCamPos"), pp.camX, pp.camY, pp.camZ)
        GL.glUniform3f(loc(p, "uMoonDir"), pp.moonDir[0], pp.moonDir[1], pp.moonDir[2])
        GL.glUniform3f(loc(p, "uMoonCol"), pp.moonCol[0], pp.moonCol[1], pp.moonCol[2])
        GL.glUniform2f(loc(p, "uShaftTexel"), 1f / shaftW, 1f / shaftH)
        // A different start offset every frame (golden-ratio sequence); the temporal blend averages them out.
        val jit = ((jitterSeq and 0xFFFFF) * 0.6180339887498949 % 1.0).toFloat()
        jitterSeq++
        GL.glUniform1f(loc(p, "uShaftTemporal"), 1f)
        GL.glUniform1f(loc(p, "uShaftJit"), jit)
        GL.glUniform1f(loc(p, "uShaftSteps"), steps)
        GL.glUniform1f(loc(p, "uShaftMax"), f.shaftMax)
        GL.glUniform1f(loc(p, "uFogDens"), f.fogDensity * pp.tune.shafts * pp.shaftScale)
        GL.glUniform1i(loc(p, "uShadowC0"), 4)
        if (f.shadows) {
            tex(4, pp.shadowTex0)
            GL.glUniformMatrix4fv(loc(p, "uLightVP0"), 1, false, pp.lightVp0, 0)
        }
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        // 2. Depth-weighted 4x4 average.
        p = shaftBlurProg
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, shaftFbo[1])
        GL.glUseProgram(p)
        tex(1, depthTex); GL.glUniform1i(loc(p, "uDepth"), 1)
        tex(6, shaftTex[0]); GL.glUniform1i(loc(p, "uShaftTex"), 6)
        tex(9, shaftZTex); GL.glUniform1i(loc(p, "uShaftZ"), 9)
        GL.glUniform2f(loc(p, "uNF"), pp.near, pp.far)
        GL.glUniform2f(loc(p, "uShaftTexel"), 1f / shaftW, 1f / shaftH)
        GL.glUniform1i(loc(p, "uShadowC0"), 4)   // the shadow sampler must not share unit 0 with a sampler2D, even though it is not read here
        GL.glUniform1f(loc(p, "uShaftMode"), 0f)
        GL.glUniform1f(loc(p, "uShaftMax"), f.shaftMax)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        // 3. Blend with last frame's beams, into the other history pair.
        val src = histCur; val dst = 1 - histCur
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, histFbo[dst])
        tex(6, shaftTex[1]); GL.glUniform1i(loc(p, "uShaftTex"), 6)
        tex(7, histTex[src]); GL.glUniform1i(loc(p, "uShaftHist"), 7)
        tex(8, histZ[src]); GL.glUniform1i(loc(p, "uShaftHistZ"), 8)
        GL.glUniformMatrix4fv(loc(p, "uInvVP"), 1, false, pp.invVp, 0)
        GL.glUniformMatrix4fv(loc(p, "uPrevVP"), 1, false, prevVp, 0)
        GL.glUniform1f(loc(p, "uShaftHistOn"), if (histValid) 1f else 0f)
        GL.glUniform1f(loc(p, "uShaftMode"), 1f)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        if (GL.glGetError() != GL.GL_NO_ERROR) { shaftLowBroken = true; histValid = false; return false }
        histCur = dst; histValid = true
        System.arraycopy(pp.vp, 0, prevVp, 0, 16)
        return true
    }

    /** DOF and volumetric shafts are evaluated once at the scene target's resolution, then bilinearly upscaled. */
    private fun depthPrepass(pp: PostParams): Boolean {
        if (prepassProg == 0 || prepassFbo == 0 || prepassTex == 0 || depthTex == 0 || !linear) return false
        val f = effective
        // Nothing to do when both depth of field and the beams are off: skip the whole scene-resolution pass. The effect
        // pass then reads the resolved scene directly (same rule as for a failed prepass) and its own DOF/beam uniforms are 0.
        val dofK = if (f.dof && pp.lite < 2) f.dofStrength * pp.tune.dof else 0f
        val shaftsOn = f.shafts && (!f.shadows || pp.shadowTex0 != 0) && pp.tune.shafts > 0.01f
        if (!shaftsOn) histValid = false   // the beams were skipped: the history is stale
        if (dofK <= 0.001f && !shaftsOn) return false
        val shaftSteps = if (shaftsOn) (if (pp.lite >= 1) kotlin.math.floor(f.shaftSteps * 0.6f) else f.shaftSteps.toFloat()) else 0f
        val low = shaftsOn && shaftLowPass(pp, f, shaftSteps)
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* isolate prepass errors */ }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, prepassFbo)
        GL.glViewport(0, 0, w, h)
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        GL.glUseProgram(prepassProg)
        tex(0, resolveTex); tex(1, depthTex)
        GL.glUniform1i(loc(prepassProg, "tDiffuse"), 0)
        GL.glUniform1i(loc(prepassProg, "uDepth"), 1)
        GL.glUniform1f(loc(prepassProg, "uSrcSrgb"), 0f)
        GL.glUniform2f(loc(prepassProg, "resolution"), w.toFloat(), h.toFloat())
        GL.glUniform1f(loc(prepassProg, "blur"), 0f); GL.glUniform1f(loc(prepassProg, "glitch"), 0f)
        GL.glUniform1f(loc(prepassProg, "lens"), 0f); GL.glUniform1f(loc(prepassProg, "warp"), 0f)
        GL.glUniform1f(loc(prepassProg, "wave"), 0f); GL.glUniform1f(loc(prepassProg, "flash"), 0f)
        GL.glUniform1f(loc(prepassProg, "uSharpen"), 0f); GL.glUniform1f(loc(prepassProg, "uChroma"), 0f)
        GL.glUniform4f(loc(prepassProg, "hole"), 0f, 0f, 0f, 0f)
        GL.glUniform2f(loc(prepassProg, "uNF"), pp.near, pp.far)
        GL.glUniform2f(loc(prepassProg, "uTanAspect"), pp.tanHalf * pp.aspect, pp.tanHalf)
        GL.glUniformMatrix4fv(loc(prepassProg, "uInvVP"), 1, false, pp.invVp, 0)
        GL.glUniformMatrix4fv(loc(prepassProg, "uVP"), 1, false, pp.vp, 0)
        GL.glUniform3f(loc(prepassProg, "uCamPos"), pp.camX, pp.camY, pp.camZ)
        GL.glUniform3f(loc(prepassProg, "uMoonDir"), pp.moonDir[0], pp.moonDir[1], pp.moonDir[2])
        GL.glUniform3f(loc(prepassProg, "uMoonCol"), pp.moonCol[0], pp.moonCol[1], pp.moonCol[2])
        GL.glUniform1f(loc(prepassProg, "uMotion"), 0f)
        GL.glUniform1f(loc(prepassProg, "uDofK"), dofK)
        GL.glUniform1f(loc(prepassProg, "uFocus"), f.focus)
        // The shadow sampler must never sit on unit 0 beside a sampler2D, even when the beams are not marched in this pass.
        GL.glUniform1i(loc(prepassProg, "uShadowC0"), 4)
        GL.glUniform1f(loc(prepassProg, "uShaftSteps"), if (low) 0f else shaftSteps)
        GL.glUniform1f(loc(prepassProg, "uShaftLow"), if (low) 1f else 0f)
        if (low) {
            tex(6, histTex[histCur]); GL.glUniform1i(loc(prepassProg, "uShaftTex"), 6)
            tex(9, histZ[histCur]); GL.glUniform1i(loc(prepassProg, "uShaftZ"), 9)
            GL.glUniform2f(loc(prepassProg, "uShaftTexel"), 1f / shaftW, 1f / shaftH)
        }
        GL.glUniform1f(loc(prepassProg, "uShaftMax"), f.shaftMax)
        GL.glUniform1f(loc(prepassProg, "uFogDens"), f.fogDensity * pp.tune.shafts * pp.shaftScale)
        if (shaftsOn && !low && f.shadows) {
            tex(4, pp.shadowTex0)
            GL.glUniformMatrix4fv(loc(prepassProg, "uLightVP0"), 1, false, pp.lightVp0, 0)
        }
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        return GL.glGetError() == GL.GL_NO_ERROR
    }

    /** True when the depth resolve failed on this device: the renderer should drop to MEDIUM. */
    var depthFailed = false; private set

    fun present(e: EffectState, glass: GlassState?, pp: PostParams) {
        val f = effective
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, msaaFbo)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, resolveFbo)
        if (blitChecks > 0) { while (GL.glGetError() != GL.GL_NO_ERROR) { /* attribute errors to the blit only */ } }
        val mask = GL.GL_COLOR_BUFFER_BIT or (if (depthTex != 0) GL.GL_DEPTH_BUFFER_BIT else 0)
        GL.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, mask, GL.GL_NEAREST)
        if (blitChecks > 0) { blitChecks--; if (depthTex != 0 && GL.glGetError() != GL.GL_NO_ERROR) depthFailed = true }
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)

        val t = pp.tune
        if (bloomDownProg != 0 && bloomTex.isNotEmpty() && t.bloom > 0.01f) bloomPass(pp.bloomThreshold)
        val useAo = ssaoProg != 0 && aoTex[0] != 0 && depthTex != 0 && t.ao > 0.01f
        if (useAo) ssaoPass(pp)
        val prepassed = depthPrepass(pp)

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, sw, sh)

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

        tex(0, if (prepassed) prepassTex else resolveTex)
        tex(5, resolveTex)
        if (glassCount > 0) {
            GL.glActiveTexture(GL.GL_TEXTURE5)
            GL.glBindTexture(GL.GL_TEXTURE_2D, resolveTex)
            GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glUseProgram(program)
        GL.glUniform1i(loc("tDiffuse"), 0)
        GL.glUniform1i(loc("uBackdrop"), 5)
        // Same rule as in the lit shader: the shadow sampler must never sit on unit 0 beside a sampler2D.
        GL.glUniform1i(loc("uShadowC0"), 4)
        val full = f.rank >= 1
        if (full && bloomTex.isNotEmpty()) { tex(3, bloomTex[0]); GL.glUniform1i(loc("uBloom"), 3) }
        val depthFx = f.needsDepth && depthTex != 0
        if (depthFx) {
            tex(1, depthTex); GL.glUniform1i(loc("uDepth"), 1)
            tex(2, if (useAo) aoTex[1] else resolveTex); GL.glUniform1i(loc("uAO"), 2)
        }
        if (depthFx && f.shadows && pp.shadowTex0 != 0) {
            tex(4, pp.shadowTex0); GL.glUniform1i(loc("uShadowC0"), 4)
            GL.glUniformMatrix4fv(loc("uLightVP0"), 1, false, pp.lightVp0, 0)
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glUniform1f(loc("uSrcSrgb"), if (prepassed || linear) 0f else 1f)
        GL.glUniform1f(loc("uSharpen"), pp.sharpen)
        GL.glUniform1f(loc("blur"), e.blur.toFloat()); GL.glUniform1f(loc("glitch"), e.glitch.toFloat())
        GL.glUniform1f(loc("frost"), e.frost.toFloat()); GL.glUniform1f(loc("lens"), e.lens.toFloat())
        GL.glUniform1f(loc("wave"), e.wave.toFloat()); GL.glUniform1f(loc("waveT"), e.waveT.toFloat())
        GL.glUniform1f(loc("warp"), e.warp.toFloat()); GL.glUniform1f(loc("flash"), e.flash.toFloat())
        GL.glUniform1f(loc("hue"), e.hue.toFloat()); GL.glUniform1f(loc("time"), e.time.toFloat())
        GL.glUniform4f(loc("hole"), e.hole[0], e.hole[1], e.hole[2], e.hole[3])
        GL.glUniform4f(loc("gate"), e.gate[0], e.gate[1], e.gate[2], e.gate[3]); GL.glUniform1f(loc("gateSx"), e.gateSx)
        GL.glUniform1fv(loc("reach"), e.reach.size, e.reach, 0)
        GL.glUniform2f(loc("resolution"), w.toFloat(), h.toFloat())
        GL.glUniform2f(loc("uRootDp"), rootW, rootH)
        GL.glUniform1f(loc("uGlassCount"), glassCount.toFloat())
        GL.glUniform4fv(loc("uGlassRect"), 2, glassRect, 0)
        GL.glUniform4fv(loc("uGlassParam"), 2, glassParam, 0)
        if (full) {
            GL.glUniform1f(loc("uTone"), if (f.tonemap) t.tone.coerceIn(0f, 1f) else 0f)
            GL.glUniform1f(loc("uExposure"), f.exposure * pp.exposureScale)
            GL.glUniform1f(loc("uBloomK"), if (bloomTex.isNotEmpty() && t.bloom > 0.01f) f.bloomStrength * t.bloom * pp.bloomScale else 0f)
            GL.glUniform1f(loc("uGrain"), f.grain)
            GL.glUniform1f(loc("uChroma"), f.chroma)
        }
        if (depthFx) {
            GL.glUniform2f(loc("uNF"), pp.near, pp.far)
            GL.glUniform2f(loc("uTanAspect"), pp.tanHalf * pp.aspect, pp.tanHalf)
            GL.glUniformMatrix4fv(loc("uInvVP"), 1, false, pp.invVp, 0)
            GL.glUniformMatrix4fv(loc("uVP"), 1, false, pp.vp, 0)
            GL.glUniform3f(loc("uCamPos"), pp.camX, pp.camY, pp.camZ)
            GL.glUniform3f(loc("uMoonDir"), pp.moonDir[0], pp.moonDir[1], pp.moonDir[2])
            GL.glUniform3f(loc("uMoonCol"), pp.moonCol[0], pp.moonCol[1], pp.moonCol[2])
            GL.glUniform1f(loc("uAoK"), if (useAo) (f.ssaoStrength * t.ao).coerceAtMost(1f) else 0f)
            GL.glUniform1f(loc("uShaftSteps"), if (!prepassed && f.shafts && (!f.shadows || pp.shadowTex0 != 0) && t.shafts > 0.01f) minOf(f.shaftSteps, 20).toFloat() else 0f)
            GL.glUniform1f(loc("uShaftMax"), f.shaftMax)
            GL.glUniform1f(loc("uFogDens"), f.fogDensity * t.shafts * pp.shaftScale)
            GL.glUniform1f(loc("uDofK"), if (!prepassed && f.dof && pp.lite < 2) f.dofStrength * t.dof else 0f)
            GL.glUniform1f(loc("uFocus"), f.focus)
            GL.glUniform1f(loc("uMotion"), if (f.motionBlur && pp.lite < 2) pp.motion else 0f)
        }
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glEnable(GL.GL_DEPTH_TEST)
    }
}
