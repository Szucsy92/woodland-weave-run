package com.woodlandweave.run.gl

import android.opengl.GLES30 as GL

/**
 * The second render target: the other world, as the day gate's hole shows it. It holds one colour texture (the same format and colour
 * encoding as the scene target, so the ground shader can copy a pixel of it straight into the frame) and a depth renderbuffer.
 */
class PortalTarget {
    var fbo = 0; private set
    var tex = 0; private set
    private var depthRb = 0
    var w = 0; private set
    var h = 0; private set
    private var hdr = false
    /** True when the target is complete and can be drawn into. */
    var ok = false; private set

    /** Makes the target [width] x [height] (and [hdr] = RGBA16F, otherwise RGBA8). Does nothing when it already is. */
    fun resize(width: Int, height: Int, hdr: Boolean) {
        if (fbo != 0 && width == w && height == h && hdr == this.hdr) return
        release()
        w = width.coerceAtLeast(1); h = height.coerceAtLeast(1); this.hdr = hdr
        while (GL.glGetError() != GL.GL_NO_ERROR) { /* drop stale errors */ }
        val t = IntArray(1); GL.glGenTextures(1, t, 0); tex = t[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, if (hdr) GL.GL_RGBA16F else GL.GL_RGBA8, w, h)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        val rb = IntArray(1); GL.glGenRenderbuffers(1, rb, 0); depthRb = rb[0]
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, depthRb)
        GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, w, h)
        val f = IntArray(1); GL.glGenFramebuffers(1, f, 0); fbo = f[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex, 0)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depthRb)
        ok = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE && GL.glGetError() == GL.GL_NO_ERROR
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    /** Frees the GL objects (also called when the surface is lost: the objects are gone with it, the ids just have to be forgotten). */
    fun release() {
        if (fbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (tex != 0) GL.glDeleteTextures(1, intArrayOf(tex), 0)
        if (depthRb != 0) GL.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        fbo = 0; tex = 0; depthRb = 0; ok = false
    }
}
