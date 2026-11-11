package com.woodlandweave.run.gl

import android.content.Context
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.woodlandweave.run.CameraPose
import com.woodlandweave.run.GameEngine
import com.woodlandweave.run.sim.*
import java.nio.FloatBuffer
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure OpenGL ES 3.0 renderer. Everything the web build did with three.js and its post-processing
 * composer is drawn here: displaced ground, instanced forest with wet normal-mapped bark, fog,
 * headlamp, glow sprites, black holes, the wormhole's relics and the full-screen effect pass.
 */
class GameRenderer(private val context: Context, private val engine: GameEngine) : GLSurfaceView.Renderer {
    private companion object {
        const val CROWN_NEAR_SUBDIVISIONS = 2     // same canopy detail as the web build, close to the runner
        const val CROWN_FAR_SUBDIVISIONS = 1      // cheaper canopy where the fog is already thick
        const val CROWN_LOD_DISTANCE = 22.0
        const val MAX_PROPS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1)
        const val GROUND_STEP = 200f / 160f
        const val K_TRUNK = 0; const val K_SHADOW = 1; const val K_ROOT = 2; const val K_BRANCH = 3
        const val K_CROWN = 4; const val K_ROCK = 5; const val K_PLANT = 6; const val K_CROWN_FAR = 7
        const val KINDS = 8
        const val TREE_PARTS = 34
        const val HOLE_SLOTS = 6
        const val SWARM = 48
        const val REMNANTS = 26
        const val REMNANT_SPAN = 90.0
        const val WINDOW = 100.0
        const val SHEET_N = 11
        const val RING_N = 56
        val WORM_COUNTS = intArrayOf(260, 3, 5)
        val REM_COUNTS = intArrayOf(70, 1, 2)
        const val FOV = 72f
    }

    // ---- GL objects ----
    private var litProg = 0; private var groundProg = 0; private var spriteProg = 0
    private val locs = HashMap<String, Int>()
    private lateinit var meshes: Array<Mesh>
    private lateinit var box: Mesh; private lateinit var orb: Mesh; private lateinit var holeBody: Mesh
    private lateinit var cyl: Mesh; private lateinit var cone: Mesh; private lateinit var ico0: Mesh
    private lateinit var quad: Mesh
    private lateinit var torusA: Mesh; private lateinit var torusB: Mesh; private lateinit var torusC: Mesh
    private var groundVao = 0; private var groundIndexCount = 0
    private var barkTex = 0; private var barkN = 0; private var barkR = 0
    private var floorTex = 0; private var floorN = 0; private var floorR = 0
    private var glowTex = 0; private var sporeTex = 0; private var holeGlowTex = 0; private var ringTex = 0; private var whiteTex = 0
    private var spriteVao = 0; private var spriteVbo = 0
    private val post = PostFx()
    private val effects = EffectsModel()
    private val effectState = EffectState()

    private class Sprites(val capacity: Int, val texture: () -> Int, val additive: Boolean) {
        val data: FloatBuffer = GlUtil.floatBuffer(capacity * 9)
        var count = 0
        fun clear() { data.clear(); count = 0 }
        fun add(x: Double, y: Double, z: Double, sx: Double, sy: Double, c: FloatArray, a: Double) {
            if (count >= capacity || a <= 0.002) return
            data.put(x.toFloat()).put(y.toFloat()).put(z.toFloat()).put(sx.toFloat()).put(sy.toFloat())
                .put(c[0]).put(c[1]).put(c[2]).put(a.toFloat().coerceIn(0f, 1f))
            count++
        }
    }
    private val bGlow = Sprites(256, { glowTex }, true)
    private val bMist = Sprites(16, { glowTex }, false)
    private val bSpore = Sprites(128, { sporeTex }, true)
    private val bHole = Sprites(1600, { holeGlowTex }, true)

    private val bufs = Array(KINDS) {
        GlUtil.floatBuffer(MAX_PROPS * (if (it == K_CROWN || it == K_CROWN_FAR) 16 else if (it == K_BRANCH) 11 else if (it == K_ROOT) 5 else 2) * 16)
    }
    private val counts = IntArray(KINDS)
    private val one: FloatBuffer = GlUtil.floatBuffer(16)

    // ---- matrices ----
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val tmp = FloatArray(16); private val tmp2 = FloatArray(16); private val tg = FloatArray(16)
    private val tmpL = FloatArray(16); private val tmpM = FloatArray(16); private val parent = FloatArray(16)
    private var lastNs = 0L

    // ---- per-frame scene parameters ----
    private var fog = Palette.fog; private var sky = Palette.moon; private var groundC = Palette.fog
    private var moonCol = Palette.moon; private var hemi = .85f; private var fogNear = 7f
    private var lights = 1f; private var red = 0f
    private var dayLevel = -1.0
    private var wormBase = FloatArray(0); private var remBase = FloatArray(0); private var remSeed = -1

    // ---- props (trees and obstacles) ----
    private class Prop(val id: String, val x: Double, val z: Double, val kinds: IntArray, val m: FloatArray)
    private val cache = HashMap<String, Prop>()
    private var props: List<Prop> = emptyList()
    private var builtVersion = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litProg = GlUtil.program(Shaders.LIT_VS, Shaders.LIT_FS)
        groundProg = GlUtil.program(Shaders.GROUND_VS, Shaders.LIT_FS)
        spriteProg = GlUtil.program(Shaders.SPRITE_VS, Shaders.SPRITE_FS)
        locs.clear()
        meshes = arrayOf(
            Mesh(Geo.trunk(), MAX_PROPS), Mesh(Geo.flatQuad(), MAX_PROPS * 2), Mesh(Geo.root(), MAX_PROPS * 5),
            Mesh(Geo.branch(), MAX_PROPS * 11), Mesh(Geo.crown(CROWN_NEAR_SUBDIVISIONS), MAX_PROPS * 16),
            Mesh(Geo.jittered(2, .18f, .14f, 33), MAX_PROPS), Mesh(Geo.jittered(2, .35f, .4f, 47), MAX_PROPS),
            Mesh(Geo.crown(CROWN_FAR_SUBDIVISIONS), MAX_PROPS * 16),
        )
        box = Mesh(Geo.box(1f, 1f, 1f), 4); orb = Mesh(Geo.icosphere(1), 4); holeBody = Mesh(Geo.icosphere(3), 4)
        cyl = Mesh(Geo.cylinder(.5f, .5f, 1f, 8, 1), 4); cone = Mesh(Geo.cylinder(0f, .5f, 1f, 5, 1), 4)
        ico0 = Mesh(Geo.icosphere(0), 4); quad = Mesh(Geo.quadXY(), 4)
        torusA = Mesh(Geo.torus(2.4f, .14f, 8, 40), 4); torusB = Mesh(Geo.torus(1.5f, .08f, 6, 28), 4); torusC = Mesh(Geo.torus(.8f, .1f, 6, 24), 4)

        val (gp, gi) = Geo.groundGrid()
        val vao = IntArray(1); val vb = IntArray(2)
        GL.glGenVertexArrays(1, vao, 0); GL.glGenBuffers(2, vb, 0)
        GL.glBindVertexArray(vao[0]); groundVao = vao[0]
        val fb = GlUtil.floatBuffer(gp.size); fb.put(gp).position(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vb[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, gp.size * 4, fb, GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 8, 0)
        val ib = java.nio.ByteBuffer.allocateDirect(gi.size * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(gi).position(0); groundIndexCount = gi.size
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, vb[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, gi.size * 2, ib, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)

        barkTex = GlUtil.assetTexture(context, "wet-bark.jpg"); barkN = GlUtil.assetTexture(context, "bark-normal.jpg"); barkR = GlUtil.assetTexture(context, "bark-rough.jpg")
        floorTex = GlUtil.assetTexture(context, "wet-ground.jpg"); floorN = GlUtil.assetTexture(context, "ground-normal.jpg"); floorR = GlUtil.assetTexture(context, "ground-rough.jpg")
        glowTex = GlUtil.glowTexture(floatArrayOf(0f, .2f, 1f), intArrayOf(255, 153, 0))
        sporeTex = GlUtil.glowTexture(floatArrayOf(0f, .16f, .4f, 1f), intArrayOf(255, 235, 66, 0))
        holeGlowTex = GlUtil.glowTexture(floatArrayOf(0f, .35f, 1f), intArrayOf(255, 89, 0))
        ringTex = GlUtil.ringTexture(); whiteTex = GlUtil.whiteTexture()

        val sv = IntArray(1); val sb = IntArray(1)
        GL.glGenVertexArrays(1, sv, 0); GL.glGenBuffers(1, sb, 0)
        spriteVao = sv[0]; spriteVbo = sb[0]
        GL.glBindVertexArray(spriteVao); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, spriteVbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, 1600 * 36, null, GL.GL_STREAM_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 36, 0); GL.glVertexAttribDivisor(0, 1)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 36, 12); GL.glVertexAttribDivisor(1, 1)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 4, GL.GL_FLOAT, false, 36, 20); GL.glVertexAttribDivisor(2, 1)
        GL.glBindVertexArray(0)

        post.create()
        GL.glEnable(GL.GL_DEPTH_TEST)
        builtVersion = -1; cache.clear(); lastNs = 0L; remSeed = -1
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GL.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, FOV, w.toFloat() / max(1, h), 0.1f, 220f)
        post.resize(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1.0 / 60 else (now - lastNs) / 1e9
        lastNs = now
        engine.frame(dt)
        draw(dt)
    }

    // ---- uniform helpers ----
    private fun loc(p: Int, name: String) = locs.getOrPut("$p:$name") { GL.glGetUniformLocation(p, name) }
    private fun u3(p: Int, n: String, c: FloatArray) = GL.glUniform3f(loc(p, n), c[0], c[1], c[2])
    private fun u3(p: Int, n: String, x: Float, y: Float, z: Float) = GL.glUniform3f(loc(p, n), x, y, z)
    private fun u1(p: Int, n: String, v: Float) = GL.glUniform1f(loc(p, n), v)

    private fun bindMaps(color: Int, normal: Int, rough: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, color)
        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, normal)
        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, rough)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    private fun material(
        p: Int, color: FloatArray, emissive: FloatArray? = null, ek: Float = 0f, alpha: Float = 1f, mode: Int = 0,
        rough: Float = .9f, maps: Boolean = false, ns: Float = 1f, uvx: Float = 1f, uvy: Float = 1f, fogOn: Float = 1f,
    ) {
        u3(p, "uColor", color)
        if (emissive != null) u3(p, "uEmissive", emissive[0] * ek, emissive[1] * ek, emissive[2] * ek) else u3(p, "uEmissive", 0f, 0f, 0f)
        u1(p, "uAlpha", alpha); u1(p, "uMaps", if (maps) 1f else 0f); u1(p, "uRough", rough); u1(p, "uNormalScale", ns)
        GL.glUniform2f(loc(p, "uUvScale"), uvx, uvy); GL.glUniform1i(loc(p, "uMode"), mode); u1(p, "uFogOn", fogOn)
    }

    // ---- building props from the engine's world ----
    private fun rebuildProps() {
        val s = engine.sim
        val next = ArrayList<Prop>(s.trees.size + s.obstacles.size)
        val alive = HashSet<String>()
        for (t in s.trees) { next += cache.getOrPut(t.id) { treeProp(t) }; alive += t.id }
        for (o in s.obstacles) { next += cache.getOrPut(o.id) { obstacleProp(o) }; alive += o.id }
        cache.keys.retainAll(alive)
        props = next
    }

    private fun treeProp(tree: Tree): Prop {
        val m = FloatArray(TREE_PARTS * 16); val kinds = IntArray(TREE_PARTS)
        var n = 0
        fun put(kind: Int, px: Double, py: Double, pz: Double, rx: Double, ry: Double, rz: Double, order: Int, ssx: Double, ssy: Double, ssz: Double) {
            kinds[n] = kind; Mat.compose(m, n * 16, px, py, pz, rx, ry, rz, order, ssx, ssy, ssz); n++
        }
        val seedX = floor(tree.x).toInt(); val seedZ = floor(tree.z).toInt()
        fun h(salt: Int) = hash(seedX, seedZ, salt)
        val base = groundHeight(tree.x, tree.z) - 1.1
        val species = floor(h(200) * 4).toInt()
        val girth = doubleArrayOf(.8, .9, 1.15, .68)[min(3, species)] * tree.scale
        val leanAmt = if (species == 3) .07 else if (species == 2) .03 else .015
        val leanDir = h(201) * PI * 2
        val lx = cos(leanDir) * leanAmt; val lz = sin(leanDir) * leanAmt
        fun ox(hh: Double) = -hh * lz
        fun oz(hh: Double) = hh * lx
        put(K_TRUNK, tree.x, base, tree.z, lx, h(5) * 6, lz, Mat.XYZ, girth, 1.0, girth)
        put(K_SHADOW, tree.x, groundHeight(tree.x, tree.z) + .05, tree.z, 0.0, 0.0, 0.0, Mat.XYZ, 3.4 * tree.scale, 1.0, 3.4 * tree.scale)
        for (r in 0 until 5) {
            val ang = (r / 5.0) * PI * 2 + h(210 + r) * .9
            val rx = tree.x + cos(ang) * .2 * girth; val rz = tree.z - sin(ang) * .2 * girth
            val rl = (.7 + h(230 + r) * .7) * (if (species == 2) 1.35 else 1.0)
            put(K_ROOT, rx, groundHeight(rx, rz) + .1, rz, 0.0, ang, -.12 - h(220 + r) * .15, Mat.XYZ, rl * girth * 1.2, girth * 1.3, girth * 1.3)
        }
        for (b in 0 until 11) {
            val seed = h(20 + b)
            val upper = b < 5; val fork = species == 1 && b < 2
            var top = if (upper) base + 14.5 + h(60 + b) * 2.5 else base + 8 + h(60 + b) * 6.5
            if (fork) top = base + 7 + h(61) * 1.5
            val yaw = if (fork) h(202) * PI * 2 + b * PI else seed * PI * 2
            val tilt = if (fork) .32 + h(41 + b) * .12 else if (upper) .55 + h(40 + b) * .75 else 1.05 + h(40 + b) * .5
            val thick = if (fork) 4.2 else if (upper) 1.0 else 2.2 + h(90 + b) * 1.4
            val len = if (fork) 3.6 else if (upper) .7 + seed * .6 else 1.1 + h(95 + b) * 1.1
            put(K_BRANCH, tree.x + ox(top - base), top, tree.z + oz(top - base), tilt, yaw, if (fork) 0.0 else (h(80 + b) - .5) * .4, Mat.YXZ,
                tree.scale * thick, tree.scale * len, tree.scale * thick)
            val reach = 2.2 * tree.scale * len
            val cr = .9 + h(120 + b) * 1.1
            put(K_CROWN, tree.x + ox(top - base) + reach * sin(tilt) * sin(yaw), top + reach * cos(tilt), tree.z + oz(top - base) + reach * sin(tilt) * cos(yaw),
                (h(83 + b) - .5) * .9, seed * PI * 2, (h(77 + b) - .5) * .9, Mat.YXZ,
                cr * tree.scale, cr * (.5 + h(122 + b) * .3) * tree.scale, cr * tree.scale)
        }
        for (c in 0 until 5) {
            val spread = if (c == 0) .5 else 2.6 + h(81 + c) * 3.6
            val angle = h(74 + c) * PI * 2
            val r = (if (c == 0) 3.9 else 2.8) + h(73 + c) * 2.0
            put(K_CROWN, tree.x + ox(16.0) + cos(angle) * spread, base + 15.2 + h(71 + c * 3) * 2.0, tree.z + oz(16.0) + sin(angle) * spread,
                (h(83 + c) - .5) * .9, angle, (h(77 + c) - .5) * .9, Mat.XYZ,
                r * (.8 + h(85 + c) * .6) * tree.scale, r * (.45 + h(87 + c) * .4) * tree.scale, r * (.7 + h(79 + c) * .6) * tree.scale)
        }
        return Prop(tree.id, tree.x, tree.z, kinds, m)
    }

    private fun obstacleProp(o: Obstacle): Prop {
        val fx = floor(o.x).toInt(); val fz = floor(o.z).toInt()
        val r = if (o.big) o.radius * 1.3 else .7
        val gy = minOf(groundHeight(o.x, o.z), groundHeight(o.x + r, o.z), groundHeight(o.x - r, o.z), groundHeight(o.x, o.z + r), groundHeight(o.x, o.z - r))
        val spin = hash(fx, fz, 80) * 6
        return if (o.big) {
            val m = FloatArray(32)
            Mat.compose(m, 0, o.x, gy + o.radius * .08, o.z, 0.0, spin, 0.0, Mat.XYZ, o.radius * 1.35, o.radius * (.55 + hash(fx, fz, 82) * .35), o.radius * 1.15)
            Mat.compose(m, 16, o.x, groundHeight(o.x, o.z) + .05, o.z, 0.0, 0.0, 0.0, Mat.XYZ, o.radius * 3.2, 1.0, o.radius * 3)
            Prop(o.id, o.x, o.z, intArrayOf(K_ROCK, K_SHADOW), m)
        } else {
            val bs = .85 + hash(fx, fz, 83) * .55
            val m = FloatArray(16)
            Mat.compose(m, 0, o.x, gy - .05, o.z, 0.0, spin, 0.0, Mat.XYZ, .8 * bs, (.4 + hash(fx, fz, 81) * .35) * bs, .8 * bs)
            Prop(o.id, o.x, o.z, intArrayOf(K_PLANT), m)
        }
    }

    // ---- the frame ----
    private fun draw(dt: Double) {
        val e = engine; val s = e.sim; val cam = e.camera
        if (builtVersion != e.worldVersion) { rebuildProps(); builtVersion = e.worldVersion }

        // Day cycle: fog, moon light and hemisphere drift towards day colours, and towards blood near the pursuer.
        val hours = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60.0 }
        val target = daylightFor(e.settings.mood, hours)
        dayLevel = if (dayLevel < 0) target else dayLevel + (target - dayLevel) * min(1.0, dt * 1.5)
        val k = dayLevel.toFloat()
        red = e.redMix.toFloat(); lights = 1f - e.dieRef.toFloat()
        fog = Palette.mix(Palette.mix(Palette.fog, Palette.dayFog, k), Palette.blood, red * .75f)
        sky = Palette.mix(Palette.mix(Palette.moon, Palette.dayMoon, k), Palette.pursuer, red * .8f)
        groundC = Palette.mix(fog, Palette.pursuer, red * .55f)
        moonCol = Palette.mix(Palette.mix(Palette.moon, Palette.dayMoon, k), Palette.pursuer, red * .6f)
        hemi = .85f + k * 1.6f + red * 1.4f
        fogNear = 7f - k * 3f
        val moonI = .7f + k * 1.3f

        // Camera: lookAt, then the same local pitch / yaw / roll nudges three.js applied.
        Matrix.setLookAtM(view, 0, cam.ex.toFloat(), cam.ey.toFloat(), cam.ez.toFloat(), cam.tx.toFloat(), cam.ty.toFloat(), cam.tz.toFloat(), 0f, 1f, 0f)
        Matrix.setIdentityM(tmp, 0)
        Matrix.rotateM(tmp, 0, Math.toDegrees(-cam.rotZ).toFloat(), 0f, 0f, 1f)
        Matrix.rotateM(tmp, 0, Math.toDegrees(-cam.rotY).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(tmp, 0, Math.toDegrees(-cam.rotX).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(tmp2, 0, tmp, 0, view, 0)
        System.arraycopy(tmp2, 0, view, 0, 16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        effects.update(s, dt, effectState) { holeScreen(it, cam) }

        post.bind()
        val clear = FloatArray(3) { fog[it].toDouble().pow(1 / 2.2).toFloat() }
        GL.glClearColor(clear[0], clear[1], clear[2], 1f)
        GL.glEnable(GL.GL_DEPTH_TEST); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)

        val behindX = sin(s.heading); val behindZ = cos(s.heading)
        val gy = groundHeight(s.x, s.z)
        val lying = s.phase == Phase.FALLING || s.phase == Phase.FALLEN
        val redI = (e.redMix * (if (lying) 140.0 else 95 + s.threat * 90)).toFloat()
        val floodI = (e.redMix * (if (lying) 3.2 else 2.6 + s.threat * 2.2)).toFloat()
        var fdx = (behindX * 50).toFloat(); var fdy = 8f; var fdz = (behindZ * 50).toFloat()
        val fl = sqrt(fdx * fdx + fdy * fdy + fdz * fdz); fdx /= fl; fdy /= fl; fdz /= fl

        fun scene(p: Int) {
            GL.glUseProgram(p)
            GL.glUniformMatrix4fv(loc(p, "uVP"), 1, false, vp, 0)
            u3(p, "uCam", cam.ex.toFloat(), cam.ey.toFloat(), cam.ez.toFloat())
            u3(p, "uFog", fog); u1(p, "uFogNear", fogNear); u1(p, "uFogFar", FOG_END.toFloat())
            u3(p, "uSky", sky); u3(p, "uGroundC", groundC); u1(p, "uHemi", hemi)
            val ml = sqrt(24f * 24f + 20f * 20f)
            u3(p, "uMoonDir", 0f, 24f / ml, -20f / ml)
            u3(p, "uMoon", moonCol[0] * moonI, moonCol[1] * moonI, moonCol[2] * moonI)
            u3(p, "uLampPos", e.lampX.toFloat(), e.lampY.toFloat(), e.lampZ.toFloat()); u3(p, "uLampCol", Palette.lamp); u1(p, "uLampI", e.lampIntensity.toFloat())
            u3(p, "uRedPos", (s.x + behindX * 5).toFloat(), (gy + 3.4).toFloat(), (s.z + behindZ * 5).toFloat())
            u3(p, "uRedCol", Palette.pursuer); u1(p, "uRedI", redI)
            u3(p, "uFloodDir", fdx, fdy, fdz)
            u3(p, "uFloodCol", Palette.pursuer[0] * floodI, Palette.pursuer[1] * floodI, Palette.pursuer[2] * floodI)
            u1(p, "uLights", lights); u1(p, "uRed", red)
            GL.glUniform1i(loc(p, "uTex"), 0); GL.glUniform1i(loc(p, "uNorm"), 1); GL.glUniform1i(loc(p, "uRoughTex"), 2)
        }

        // Ground: snapped to its own vertex lattice so the displaced terrain never shimmers.
        scene(groundProg)
        val gx = Math.round(s.x / GROUND_STEP) * GROUND_STEP; val gz = Math.round(s.z / GROUND_STEP) * GROUND_STEP
        GL.glUniform2f(loc(groundProg, "uOff"), gx, gz)
        material(groundProg, Palette.ground, mode = 3, rough = .95f, maps = true, ns = .5f)
        bindMaps(floorTex, floorN, floorR)
        GL.glBindVertexArray(groundVao)
        GL.glDrawElements(GL.GL_TRIANGLES, groundIndexCount, GL.GL_UNSIGNED_SHORT, 0)
        GL.glBindVertexArray(0)

        // Forest.
        fillInstances(s, cam)
        scene(litProg)
        val g = e.ghostK.toFloat()
        bindMaps(barkTex, barkN, barkR)
        if (g > 0f) { GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA) }
        material(litProg, Palette.trunk, Palette.ghostGlow, g * .55f, 1f - g * .34f, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
        meshes[K_TRUNK].draw(bufs[K_TRUNK], counts[K_TRUNK])
        meshes[K_ROOT].draw(bufs[K_ROOT], counts[K_ROOT])
        material(litProg, Palette.trunk, Palette.ghostGlow, g * .55f, 1f - g * .34f, mode = 0, rough = .95f, maps = true, ns = .7f, uvx = 1.5f, uvy = 7f)
        meshes[K_BRANCH].draw(bufs[K_BRANCH], counts[K_BRANCH])
        GL.glDisable(GL.GL_BLEND)
        material(litProg, Palette.crown, mode = 5, rough = .9f)
        meshes[K_CROWN].draw(bufs[K_CROWN], counts[K_CROWN])
        meshes[K_CROWN_FAR].draw(bufs[K_CROWN_FAR], counts[K_CROWN_FAR])
        material(litProg, Palette.rock, rough = .9f)
        meshes[K_ROCK].draw(bufs[K_ROCK], counts[K_ROCK])
        material(litProg, Palette.plant, mode = 6, rough = .9f)
        meshes[K_PLANT].draw(bufs[K_PLANT], counts[K_PLANT])

        drawLandmarks(s, e, cam)
        drawHoles(s)
        drawRelics(s, cam)

        // Contact shadows: soft dark decals just above the ground.
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        bindMaps(glowTex, glowTex, glowTex)
        material(litProg, floatArrayOf(0f, 0f, 0f), alpha = .6f, mode = 1)
        meshes[K_SHADOW].draw(bufs[K_SHADOW], counts[K_SHADOW])
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)

        buildSprites(s, e)
        for (b in arrayOf(bMist, bGlow, bSpore, bHole)) drawSprites(b)

        post.present(effectState)
    }

    /** Where the nearest black hole sits on screen, for the lens effect. */
    private fun holeScreen(s: Simulation, cam: CameraPose): FloatArray? {
        if (s.mode != GameMode.FREEROAM || s.warp >= 0) return null
        var best: BlackHole? = null; var bestD = 90.0
        for (h in holesAround(s.x, s.z, s.universe)) {
            val d = hypot(h.x - cam.ex, h.z - cam.ez)
            if (d < bestD) { best = h; bestD = d }
        }
        val h = best ?: return null
        val cy = groundHeight(h.x, h.z) + HOLE_HEIGHT
        val dist3 = max(.5, sqrt((h.x - cam.ex).pow(2) + (cy - cam.ey).pow(2) + (h.z - cam.ez).pow(2)))
        val px = vp[0] * h.x + vp[4] * cy + vp[8] * h.z + vp[12]
        val py = vp[1] * h.x + vp[5] * cy + vp[9] * h.z + vp[13]
        val pz = vp[2] * h.x + vp[6] * cy + vp[10] * h.z + vp[14]
        val pw = vp[3] * h.x + vp[7] * cy + vp[11] * h.z + vp[15]
        if (pw <= 0f || pz / pw >= 1f) return null
        val rs = .5 * HOLE_RADIUS / dist3 / tan(Math.toRadians(FOV / 2.0))
        val strength = ((85 - dist3) / 60).coerceIn(0.0, 1.0)
        return floatArrayOf(
            (px / pw * .5 + .5).toFloat(),
            (py / pw * .5 + .5).toFloat(),
            rs.toFloat(),
            strength.toFloat()
        )
    }

    // ---- single primitives ----
    private fun uploadOne(mesh: Mesh, m: FloatArray) {
        one.position(0); one.put(m, 0, 16); one.position(0)
        mesh.draw(one, 1)
    }

    private fun prim(mesh: Mesh, par: FloatArray, lx: Double, ly: Double, lz: Double, rx: Double, ry: Double, rz: Double, sx: Double, sy: Double, sz: Double) {
        Mat.compose(tmpL, 0, lx, ly, lz, rx, ry, rz, Mat.XYZ, sx, sy, sz)
        Matrix.multiplyMM(tmpM, 0, par, 0, tmpL, 0)
        uploadOne(mesh, tmpM)
    }

    private fun place(out: FloatArray, x: Double, y: Double, z: Double, rx: Double = 0.0, ry: Double = 0.0, rz: Double = 0.0, s: Double = 1.0) =
        Mat.compose(out, 0, x, y, z, rx, ry, rz, Mat.XYZ, s, s, s)

    private fun additiveVeil(color: FloatArray, alpha: Float, tex: Int, par: FloatArray, sx: Double, sy: Double, lx: Double = 0.0, ly: Double = 0.0, lz: Double = 0.0, rz: Double = 0.0) {
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
        bindMaps(tex, tex, tex)
        material(litProg, color, alpha = alpha, mode = 1)
        prim(quad, par, lx, ly, lz, 0.0, 0.0, rz, sx, sy, 1.0)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        bindMaps(barkTex, barkN, barkR)
    }

    private fun orbColor(t: PowerUpType) = when (t) { PowerUpType.FREEZER -> Palette.freezer; PowerUpType.GHOST -> Palette.ghost; PowerUpType.SHOCKWAVE -> Palette.shock }

    /** Orbs, secrets and red gates. */
    private fun drawLandmarks(s: Simulation, e: GameEngine, cam: CameraPose) {
        val t = s.elapsed
        for ((i, o) in e.orbs.withIndex()) {
            val col = orbColor(o.p.type)
            val taken = o.taken
            val base = groundHeight(o.p.x, o.p.z) + 1.15
            val k = if (taken != null) min(1.0, (t - taken) / .25) else 0.0
            val size = if (taken != null) max(.001, .42 * (1 - k)) else .42 + sin(t * 2.1 + i) * .035
            val flow = min(1.0, k * 1.5)
            val px = if (taken != null) o.p.x + (cam.ex - o.p.x) * flow * .6 else o.p.x
            val pz = if (taken != null) o.p.z + (cam.ez - o.p.z) * flow * .6 else o.p.z
            val py = if (taken != null) base + (cam.ey - .35 - base) * flow else base + sin(t * 1.3 + i * 2) * .08
            material(litProg, col, col, 1.6f, rough = .2f)
            place(parent, px, py, pz, 0.0, t * .8, 0.0, size)
            uploadOne(orb, parent)
        }
        if (s.mode != GameMode.FREEROAM) return
        for (g in e.gates) {
            val gyG = groundHeight(g.x, g.z)
            place(parent, g.x, gyG, g.z, 0.0, g.angle, 0.0)
            material(litProg, Palette.rock, Palette.ember, .9f, rough = .4f)
            for (side in intArrayOf(-1, 1)) prim(box, parent, side * (GATE_HALF + .2), 2.1, 0.0, 0.0, 0.0, 0.0, .4, 4.6, .4)
            prim(box, parent, 0.0, 4.5, 0.0, 0.0, 0.0, 0.0, GATE_HALF * 2 + .8, .4, .4)
        }
        for ((i, g) in e.gates.withIndex()) {
            place(parent, g.x, groundHeight(g.x, g.z), g.z, 0.0, g.angle, 0.0)
            additiveVeil(Palette.pursuer, (.16 + sin(t * 1.7 + i * 2) * .06).toFloat(), whiteTex, parent, GATE_HALF * 2, 4.4, ly = 2.2)
        }
        for ((i, sec) in e.secrets.withIndex()) {
            val gyS = groundHeight(sec.x, sec.z)
            place(parent, sec.x, gyS, sec.z)
            when (sec.type) {
                SecretType.MONOLITH -> {
                    material(litProg, Palette.rock, Palette.secret, .08f, rough = .35f)
                    prim(box, parent, 0.0, 2.0, 0.0, 0.0, .4, 0.0, 1.0, 4.4, .35)
                    material(litProg, Palette.secret, mode = 2)
                    prim(box, parent, .07, 2.1, .2, 0.0, .4, 0.0, .06, 3.2, .02)
                }
                SecretType.RING -> {
                    material(litProg, Palette.green, mode = 2)
                    for (m in 0 until 11) {
                        val a = m / 11.0 * PI * 2 + t * .25
                        prim(orb, parent, cos(a) * 2.6, .2, sin(a) * 2.6, 0.0, 0.0, 0.0, .16, .1, .16)
                    }
                }
                SecretType.LANTERN -> {
                    material(litProg, Palette.trunk, rough = .9f)
                    prim(cyl, parent, 0.0, .8, 0.0, 0.0, 0.0, .08, .12, 1.6, .12)
                    material(litProg, Palette.lamp, mode = 2)
                    prim(box, parent, .06, 1.7, 0.0, 0.0, 0.0, 0.0, .24, .3, .24)
                }
            }
        }
    }

    private fun drawHoles(s: Simulation) {
        if (s.mode != GameMode.FREEROAM || s.warp >= 0) return
        val t = s.elapsed
        var i = 0
        for (h in holesAround(s.x, s.z, s.universe)) {
            if (hypot(h.x - s.x, h.z - s.z) >= HOLE_SEE) continue
            if (i >= HOLE_SLOTS) break
            val y = groundHeight(h.x, h.z) + HOLE_HEIGHT + sin(t * .6 + i) * .12
            // The body: pure black and untouched by fog, so it reads from far away.
            material(litProg, floatArrayOf(0f, 0f, 0f), mode = 2, fogOn = 0f)
            place(parent, h.x, y, h.z, 0.0, 0.0, 0.0, HOLE_RADIUS)
            uploadOne(holeBody, parent)
            // A tilted accretion veil: three faint, soft glows turning slowly.
            Mat.compose(parent, 0, h.x, y, h.z, 1.2 + sin(t * .2 + i) * .12, 0.0, .35 + sin(t * .15 + i * 2) * .1, Mat.XYZ, 1.0, 1.0, 1.0)
            val rings = arrayOf(Triple(10.0, Palette.lavender, .16f), Triple(13.0, Palette.purple, .11f), Triple(17.0, Palette.secret, .07f))
            for ((r, ring) in rings.withIndex()) {
                val spin = t * (.9 - r * .28) * (if (r % 2 == 1) -1 else 1)
                additiveVeil(ring.second, ring.third, ringTex, parent, ring.first, ring.first, rz = spin)
            }
            i++
        }
    }

    // ---- the wormhole's relics ----
    private fun relic(idx: Int, par: FloatArray) {
        val kind = idx % 6
        val hue = Palette.odd[(idx * 3 + idx / 6) % 8]
        val alt = Palette.odd[(idx * 5 + 2) % 8]
        fun basic(c: FloatArray) = material(litProg, c, mode = 2)
        fun rockMat(em: FloatArray?, ek: Float, rough: Float) = material(litProg, Palette.rock, em, ek, rough = rough)
        when (kind) {
            0 -> { rockMat(hue, .35f, .35f); prim(box, par, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.4, .35)
                basic(hue); prim(box, par, .07, .1, .2, 0.0, 0.0, 0.0, .06, 3.2, .02) }
            1 -> { basic(hue); prim(torusA, par, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                basic(alt); prim(torusB, par, 0.0, 0.0, 0.0, PI / 2, 0.0, 0.0, 1.0, 1.0, 1.0) }
            2 -> { material(litProg, Palette.rock, rough = .9f); prim(cyl, par, 0.0, -.8, 0.0, 0.0, 0.0, 0.0, .12, 1.6, .12)
                basic(hue); prim(box, par, .06, .1, 0.0, 0.0, 0.0, 0.0, .24, .3, .24) }
            3 -> {
                for (m in 0 until 12) { val a = m / 12.0 * PI * 2; basic(Palette.odd[(m + idx) % 8]); prim(orb, par, cos(a) * 2.4, 0.0, sin(a) * 2.4, 0.0, 0.0, 0.0, .26, .26, .26) }
                for (m in 0 until 6) { val a = m / 6.0 * PI * 2 + .3; basic(Palette.odd[(m * 2 + idx + 3) % 8]); prim(orb, par, cos(a) * 1.2, 0.0, sin(a) * 1.2, 0.0, 0.0, 0.0, .16, .16, .16) }
            }
            4 -> {
                rockMat(null, 0f, .5f); prim(cyl, par, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, .28, 6.0, .28)
                rockMat(hue, .5f, .4f); prim(box, par, 0.0, -2.0, 0.0, 0.0, 0.0, 0.0, .9, .7, .9)
                basic(alt); prim(orb, par, 0.0, -.7, 0.0, 0.0, 0.0, 0.0, .6, .6, .6)
                basic(hue); prim(torusC, par, 0.0, .6, 0.0, PI / 2, 0.0, 0.0, 1.0, 1.0, 1.0)
                rockMat(alt, .5f, .4f); prim(cone, par, 0.0, 1.8, 0.0, 0.0, 0.0, 0.0, 1.1, 1.2, 1.1)
                basic(hue); prim(ico0, par, 0.0, 3.3, 0.0, 0.0, 0.0, 0.0, .4, .4, .4)
            }
            else -> {
                for (side in intArrayOf(-1, 1)) { rockMat(hue, .9f, .4f); prim(box, par, side * 2.2, 0.0, 0.0, 0.0, 0.0, 0.0, .4, 4.6, .4) }
                rockMat(hue, .9f, .4f); prim(box, par, 0.0, 2.4, 0.0, 0.0, 0.0, 0.0, 5.2, .4, .4)
                additiveVeil(alt, .16f, whiteTex, par, 4.4, 4.4)
            }
        }
    }

    private fun relicGlow(idx: Int, px: Double, py: Double, pz: Double, scale: Double) {
        val kind = idx % 6
        val hue = Palette.odd[(idx * 3 + idx / 6) % 8]
        val w = if (kind == 2) 3.2 else 7.0
        val h = if (kind == 0 || kind == 4) 8.0 else if (kind == 2) 3.2 else 7.0
        bHole.add(px, py, pz, w * scale, h * scale, hue, .38)
    }

    private fun eulerApply(v: DoubleArray, ex: Double, ey: Double, ez: Double) {
        var x = v[0]; var y = v[1]; var z = v[2]
        var nx = x * cos(ez) - y * sin(ez); var ny = x * sin(ez) + y * cos(ez); x = nx; y = ny
        nx = x * cos(ey) + z * sin(ey); val nz = -x * sin(ey) + z * cos(ey); x = nx; z = nz
        ny = y * cos(ex) - z * sin(ex); val nz2 = y * sin(ex) + z * cos(ex); y = ny; z = nz2
        v[0] = x; v[1] = y; v[2] = z
    }

    private fun wrap(anchor: Double, centre: Double) = centre + ((((anchor - centre) % WINDOW) + WINDOW * 1.5) % WINDOW) - WINDOW / 2
    private fun smooth01(x: Double): Double { val k = min(1.0, max(0.0, x)); return k * k * (3 - 2 * k) }
    private fun bounce(u: Double, h: Double): Double = when {
        u < .45 -> { val k = u / .45; h * (1 - k * k) }
        u < .75 -> { val k = (u - .45) / .3; h * .35 * 4 * k * (1 - k) }
        u < .92 -> { val k = (u - .75) / .17; h * .12 * 4 * k * (1 - k) }
        else -> { val k = (u - .92) / .08; h * .04 * 4 * k * (1 - k) }
    }

    private fun paintSpores(counts: IntArray, seed: Int): FloatArray {
        val total = counts[0] + counts[1] * SHEET_N * SHEET_N + counts[2] * RING_N
        val out = FloatArray(total * 3)
        for (i in 0 until total) {
            val group = i / 40
            val mono = rnd((group + seed * 17).toDouble(), 41.0) < .45
            val ci = floor(rnd(if (mono) (group + seed * 17).toDouble() else (i + seed * 1000).toDouble(), 42.0) * 8).toInt()
            val c = Palette.odd[min(7, ci)]
            out[i * 3] = c[0]; out[i * 3 + 1] = c[1]; out[i * 3 + 2] = c[2]
        }
        return out
    }

    private fun emitSpores(counts: IntArray, base: FloatArray, s: Simulation, t: Double, w: Double, seed: Int, size: Double, reveal: (Int, Int) -> Double) {
        var n = 0
        val sz = size * tan(Math.toRadians(FOV / 2.0))
        val col = FloatArray(3)
        fun put(x: Double, y: Double, z: Double, light: Double) {
            if (n * 3 + 2 >= base.size) return
            val f = (light * w).toFloat()
            col[0] = base[n * 3] * f; col[1] = base[n * 3 + 1] * f; col[2] = base[n * 3 + 2] * f
            bHole.add(x, y, z, sz, sz, col, 1.0)
            n++
        }
        val off = seed * 1000.0
        for (i in 0 until counts[0]) {
            val period = 3.4 + rnd(i + off, 21.0) * 2.6
            val tau = t / period + rnd(i + off, 22.0) * 10
            val cycle = floor(tau); val u = tau - cycle
            val key = i * 7 + cycle * 131 + off
            val x = wrap(rnd(key, 23.0) * 4000, s.x) + sin(t * 1.3 + i) * .4
            val z = wrap(rnd(key, 24.0) * 4000, s.z) + cos(t * 1.1 + i) * .4
            val height = 5 + rnd(key, 25.0) * 11
            put(x, groundHeight(x, z) + .15 + bounce(u, height), z, min(1.0, u * 10) * min(1.0, (1 - u) * 6) * reveal(0, i))
        }
        val v = DoubleArray(3)
        for (sh in 0 until counts[1]) {
            val ax = wrap(rnd(sh + off, 31.0) * 4000, s.x); val az = wrap(rnd(sh + off, 32.0) * 4000, s.z)
            val ay = groundHeight(ax, az) + 3 + rnd(sh + off, 33.0) * 8
            val ex = rnd(sh + off, 34.0) * 6.3 + t * .05; val ey = rnd(sh + off, 35.0) * 6.3; val ez = rnd(sh + off, 36.0) * 6.3 + t * .04
            val shown = reveal(1, sh)
            for (gx in 0 until SHEET_N) for (gz in 0 until SHEET_N) {
                val lx = (gx - (SHEET_N - 1) / 2.0) * 1.1; val lz = (gz - (SHEET_N - 1) / 2.0) * 1.1
                val ripple = sin(hypot(lx, lz) * .9 - t * 2.4 + sh) * .8 + sin(lx * .6 + t * 1.1) * .3
                v[0] = lx; v[1] = ripple; v[2] = lz; eulerApply(v, ex, ey, ez)
                put(ax + v[0], ay + v[1], az + v[2], (.75 + sin(t * 2 + gx * .7 + gz * .5) * .25) * shown)
            }
        }
        for (r in 0 until counts[2]) {
            val ax = wrap(rnd(r + off, 51.0) * 4000, s.x); val az = wrap(rnd(r + off, 52.0) * 4000, s.z)
            val ay = groundHeight(ax, az) + 2 + rnd(r + off, 53.0) * 10
            val ex = rnd(r + off, 54.0) * 6.3; val ey = rnd(r + off, 55.0) * 6.3 + t * .1; val ez = rnd(r + off, 56.0) * 6.3
            val rad = 2.4 + rnd(r + off, 57.0) * 2 + sin(t * .8 + r) * .9
            val shown = reveal(2, r)
            for (k in 0 until RING_N) {
                val a = (k.toDouble() / RING_N) * PI * 2 + t * (.6 + r * .15) * (if (r % 2 == 1) -1 else 1)
                val rise = if (r % 2 == 1) (k.toDouble() / RING_N - .5) * 5 else sin(k * .9 + t * 2) * .35
                v[0] = cos(a) * rad; v[1] = rise; v[2] = sin(a) * rad; eulerApply(v, ex, ey, ez)
                put(ax + v[0], ay + v[1], az + v[2], (.8 + sin(t * 3 + k) * .2) * shown)
            }
        }
    }

    private fun drawRelics(s: Simulation, cam: CameraPose) {
        val t = s.elapsed
        val w = warpEnvelope(s)
        val fwx = cam.tx - cam.ex; val fwz = cam.tz - cam.ez
        val fl = hypot(fwx, fwz).coerceAtLeast(1e-6)
        fun visible(x: Double, z: Double): Boolean {
            val dx = x - cam.ex; val dz = z - cam.ez
            return hypot(dx, dz) < FOG_END + 12 && (dx * fwx + dz * fwz) / fl > -14
        }
        bindMaps(barkTex, barkN, barkR)
        if (w > .002) {
            for (i in 0 until SWARM) {
                val x = wrap(rnd(i.toDouble(), 1.0) * 4000, s.x); val z = wrap(rnd(i.toDouble(), 2.0) * 4000, s.z)
                val gy = groundHeight(x, z)
                val lift = if (rnd(i.toDouble(), 3.0) < .3) -1 + rnd(i.toDouble(), 4.0) * 1.5
                else if (rnd(i.toDouble(), 4.0) < .4) 1.5 + rnd(i.toDouble(), 5.0) * 4 else 5 + rnd(i.toDouble(), 6.0) * 13
                val scale = max(.001, (1 + rnd(i.toDouble(), 13.0) * 2.6) * w)
                if (visible(x, z)) {
                    Mat.compose(parent, 0, x, gy + lift, z,
                        rnd(i.toDouble(), 7.0) * 6.3 + t * (rnd(i.toDouble(), 8.0) - .5) * .5,
                        rnd(i.toDouble(), 9.0) * 6.3 + t * (rnd(i.toDouble(), 10.0) - .5) * .7,
                        rnd(i.toDouble(), 11.0) * 6.3 + t * (rnd(i.toDouble(), 12.0) - .5) * .4, Mat.XYZ, scale, scale, scale)
                    relic(i, parent)
                }
                relicGlow(i, x, gy + lift, z, scale)
            }
        }
        if (w > .002) {
            if (wormBase.isEmpty()) wormBase = paintSpores(WORM_COUNTS, 0)
            emitSpores(WORM_COUNTS, wormBase, s, t, w, 0, .85) { _, _ -> 1.0 }
        }
        // Remnants: what the wormholes leave behind in the universe they open into, arriving piece by piece.
        if (s.mode == GameMode.FREEROAM && s.universe > 0 && s.warp < 0) {
            val ut = universeTime(s); val u = s.universe
            if (remSeed != u) { remSeed = u; remBase = paintSpores(REM_COUNTS, u) }
            for (i in 0 until REMNANTS) {
                val key = (u * 101 + i).toDouble()
                val alive = rnd(key, 71.0) < min(.8, .3 + u * .1)
                val grown = if (alive) smooth01((ut - rnd(key, 72.0) * REMNANT_SPAN) / 4) else 0.0
                if (grown <= .001) continue
                val x = wrap(rnd(key, 73.0) * 4000, s.x); val z = wrap(rnd(key, 74.0) * 4000, s.z)
                val gy = groundHeight(x, z)
                val lift = if (rnd(key, 75.0) < .55) -.5 + rnd(key, 76.0) * 2 else 2 + rnd(key, 77.0) * 9
                val scale = max(.001, (.8 + rnd(key, 84.0) * 1.8) * grown)
                if (visible(x, z)) {
                    Mat.compose(parent, 0, x, gy + lift, z,
                        rnd(key, 78.0) * 6.3 + t * (rnd(key, 79.0) - .5) * .2,
                        rnd(key, 80.0) * 6.3 + t * (rnd(key, 81.0) - .5) * .3,
                        rnd(key, 82.0) * 6.3 + t * (rnd(key, 83.0) - .5) * .15, Mat.XYZ, scale, scale, scale)
                    relic(i + 7, parent)
                }
                relicGlow(i + 7, x, gy + lift, z, scale)
            }
            emitSpores(REM_COUNTS, remBase, s, t, .8, u, .6) { group, index ->
                smooth01((ut - rnd((u * 977 + group * 31 + index).toDouble(), 85.0) * REMNANT_SPAN) / 5)
            }
        }
    }

    // ---- per-frame instance assembly (culling, crown LOD, shockwave dissolve) ----
    private fun emit(kind: Int, src: FloatArray, off: Int) {
        val b = bufs[kind]
        b.position(counts[kind] * 16); b.put(src, off, 16); counts[kind]++
    }

    private fun fillInstances(s: Simulation, cam: CameraPose) {
        counts.fill(0)
        val fwx = cam.tx - cam.ex; val fwz = cam.tz - cam.ez
        val fl = hypot(fwx, fwz).coerceAtLeast(1e-6)
        val reach = FOG_END + 8
        for (p in props) {
            val dx = p.x - cam.ex; val dz = p.z - cam.ez
            val dist = hypot(dx, dz)
            if (dist > reach) continue
            if ((dx * fwx + dz * fwz) / fl < -12) continue
            val far = dist > CROWN_LOD_DISTANCE
            var dissolving = false; var k = 0.0
            if (s.cleared.contains(p.id)) {
                val age = s.elapsed - (s.clearedAt[p.id] ?: -99.0)
                if (age >= SHOCKWAVE_DISSOLVE) continue
                if (age >= 0) { dissolving = true; k = min(1.0, age / SHOCKWAVE_DISSOLVE) }
            }
            if (!dissolving) {
                for (i in p.kinds.indices) emit(if (far && p.kinds[i] == K_CROWN) K_CROWN_FAR else p.kinds[i], p.m, i * 16)
                continue
            }
            // The wave shoves it into the depth, tips it over and rips it out of the ground.
            val seed = p.id.length * 31 + p.id.last().code
            val fx = -sin(s.heading); val fz = -cos(s.heading)
            val push = k.pow(1.4) * (34 + hash(seed, 7, 404) * 16)
            val lift = sin(min(1.0, k * 2.6) * PI * .5) * (1.6 + hash(seed, 8, 405) * 2.2) * min(1.0, k * 1.8)
            val tip = min(1.0, k * 1.8) * (.7 + hash(seed, 9, 406) * .7)
            val sk = 1 - smoothRange(k, .8, 1.0)
            val bx = p.m[12].toDouble(); val by = p.m[13].toDouble(); val bz = p.m[14].toDouble()
            val lat = (bx - s.x) * cos(s.heading) - (bz - s.z) * sin(s.heading)
            val out = max(-1.0, min(1.0, lat / 9)) * push * .3
            Matrix.setIdentityM(tg, 0)
            Matrix.translateM(tg, 0, (bx + fx * push + cos(s.heading) * out).toFloat(), (by + lift).toFloat(), (bz + fz * push - sin(s.heading) * out).toFloat())
            Matrix.rotateM(tg, 0, Math.toDegrees(tip).toFloat(), fz.toFloat(), 0f, (-fx).toFloat())
            Matrix.scaleM(tg, 0, sk.toFloat(), sk.toFloat(), sk.toFloat())
            Matrix.translateM(tg, 0, (-bx).toFloat(), (-by).toFloat(), (-bz).toFloat())
            for (i in p.kinds.indices) {
                Matrix.multiplyMM(tmp2, 0, tg, 0, p.m, i * 16)
                emit(if (far && p.kinds[i] == K_CROWN) K_CROWN_FAR else p.kinds[i], tmp2, 0)
            }
        }
    }

    private fun smoothRange(x: Double, a: Double, b: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    // ---- sprites ----
    private fun buildSprites(s: Simulation, e: GameEngine) {
        for (b in arrayOf(bGlow, bMist, bSpore, bHole)) if (b !== bHole) b.clear()
        // bHole also carries the relics' glow queued earlier in this frame, so it is cleared after drawing.
        val t = s.elapsed
        val ember = surviveStage(s) == SurviveStage.RED
        // Distant forest lights: green, purple and lavender glows that stutter out one by one in survive mode.
        val cols = arrayOf(Palette.green, Palette.purple, Palette.lavender, Palette.purple, Palette.lavender)
        for (light in forestLightsFor(s.mode, s.x, s.z)) {
            val lx = light.x; val lz = light.z; val lg = groundHeight(lx, lz) + 4.2
            for (i in 0 until 5) {
                val own = min(1.0, max(0.0, e.dieRef * 1.6 - i * .15))
                val flick = if (own > 0 && own < 1) (if (sin(s.elapsed * (26 + i * 7)) > .2) 1.0 else .1) else 1.0
                val high = i > 2
                val size = (if (high) 30.0 else if (i == 0) 24.0 else 20.0) * (1 + sin(t * .4 + i) * .08)
                val px = if (high) lx + (if (i == 3) -3.5 else 3.5) + sin(t * .2 + i) * 2 else lx + (if (i == 0) -5.0 else if (i == 1) 5.0 else 0.0) + sin(t * .16 + i) * 1.5
                val py = if (high) lg + 13.5 + sin(t * .24 + i) * 1.4 else lg
                bGlow.add(px, py, lz, size, size, cols[i], (if (high) .14 else .66) * (1 - own) * flick)
            }
        }
        // Spores.
        for (i in 0 until GameEngine.SPORE_COUNT) {
            val sp = e.spores.getOrNull(i) ?: break
            val seed = i * 7.13
            val mx = sp.x + sin(t * .23 + seed * 2) * .6; val mz = sp.z + cos(t * .19 + seed) * .6
            val dist = hypot(mx - s.x, mz - s.z)
            val tw = .5 + .5 * sin(t * (.35 + hash(i, 2, 8) * .35) + seed * 3)
            val roll = hash(i, 3, 10)
            val col = if (ember) Palette.ember else if (roll < .08) Palette.purple else if (roll < .16) Palette.green else Palette.lemon
            val size = (.14 + hash(i, 4, 9) * .12) * (if (ember) 1.25 else 1.0) * 3.0
            bSpore.add(mx, groundHeight(mx, mz) + sp.h + sin(t * .5 + seed) * .25, mz, size, size, col, min(1.0, sporeOpacity(dist, tw) * (if (ember) 1.9 else 1.0)))
        }
        // Orb auras.
        for ((i, o) in e.orbs.withIndex()) {
            val col = orbColor(o.p.type)
            val taken = o.taken
            val k = if (taken != null) min(1.0, (t - taken) / .25) else 0.0
            val size = if (taken != null) max(.001, 2.8 * (1 - k)) else 2.8 + sin(t * 1.7 + i) * .28
            val a = if (taken != null) .5 * (1 - k) else .6 + sin(t * 1.7 + i) * .12
            val base = groundHeight(o.p.x, o.p.z) + 1.15
            val flow = min(1.0, k * 1.5)
            val px = if (taken != null) o.p.x + (e.camera.ex - o.p.x) * flow * .6 else o.p.x
            val pz = if (taken != null) o.p.z + (e.camera.ez - o.p.z) * flow * .6 else o.p.z
            val py = if (taken != null) base + (e.camera.ey - .35 - base) * flow else base + sin(t * 1.3 + i * 2) * .08
            bGlow.add(px, py, pz, size, size, col, a)
        }
        // Low drifting mist ahead (ordinary alpha blending, not additive).
        for (i in 0 until 4) {
            val d = 10 + i * 6
            val a = s.heading + sin(t * .05 + i * 2.1) * .5 + (if (i % 2 == 1) .35 else -.35)
            val mx = s.x - sin(a) * d; val mz = s.z - cos(a) * d
            bMist.add(mx, groundHeight(mx, mz) + .7 + i * .15, mz, 14.0 + i * 3, 3.2, Palette.moon, .07)
        }
        if (s.mode == GameMode.FREEROAM) {
            for ((i, sec) in e.secrets.withIndex()) {
                val found = s.found.contains(sec.id)
                val pulse = (if (found) 1.4 else 1.0) * (.8 + sin(t * 1.6 + i) * .2)
                val gyS = groundHeight(sec.x, sec.z)
                when (sec.type) {
                    SecretType.MONOLITH -> bGlow.add(sec.x, gyS + 2.4, sec.z, 5.0, 7.0, Palette.secret, min(1.0, .35 * pulse))
                    SecretType.RING -> for (m in 0 until 11) {
                        val a = m / 11.0 * PI * 2 + t * .25
                        bGlow.add(sec.x + cos(a) * 2.6, gyS + .3, sec.z + sin(a) * 2.6, .9, .9, Palette.green, min(1.0, .7 * pulse))
                    }
                    SecretType.LANTERN -> bGlow.add(sec.x + .06, gyS + 1.7, sec.z, 3.2, 3.2, Palette.lamp, min(1.0, .75 * pulse * (.85 + sin(t * 9 + i) * .15)))
                }
            }
            for (g in e.gates) bGlow.add(g.x, groundHeight(g.x, g.z) + 3, g.z, 16.0, 16.0, Palette.ember, .32)
            var hi = 0
            for (h in holesAround(s.x, s.z, s.universe)) {
                if (hypot(h.x - s.x, h.z - s.z) >= HOLE_SEE) continue
                if (hi >= HOLE_SLOTS || s.warp >= 0) break
                val hy = groundHeight(h.x, h.z) + HOLE_HEIGHT + sin(t * .6 + hi) * .12
                // A dim violet halo that carries through the fog, and a few orbiting sparks.
                bHole.add(h.x, hy, h.z, 22.0, 22.0, Palette.purple, .3)
                for (k in 0 until 9) {
                    val a = t * (1.4 - k * .13) + k * 1.7; val rad = 3.3 + (k % 4) * .7
                    bHole.add(h.x + cos(a) * rad, hy + sin(a * .9 + k) * .5, h.z + sin(a) * rad, .55, .55, Palette.lavender, .8)
                }
                hi++
            }
        }
    }

    private fun drawSprites(b: Sprites) {
        if (b.count > 0) {
            GL.glUseProgram(spriteProg)
            GL.glUniformMatrix4fv(loc(spriteProg, "uVP"), 1, false, vp, 0)
            // Camera right/up are the first two rows of the view rotation.
            GL.glUniform3f(loc(spriteProg, "uRight"), view[0], view[4], view[8])
            GL.glUniform3f(loc(spriteProg, "uUp"), view[1], view[5], view[9])
            GL.glUniform1i(loc(spriteProg, "uTex"), 0)
            GL.glEnable(GL.GL_BLEND)
            if (b.additive) GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE) else GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
            GL.glDepthMask(false)
            GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, b.texture())
            b.data.position(0).limit(b.count * 9)
            GL.glBindVertexArray(spriteVao); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, spriteVbo)
            GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, b.count * 36, b.data)
            GL.glDrawArraysInstanced(GL.GL_TRIANGLE_STRIP, 0, 4, b.count)
            GL.glBindVertexArray(0)
            b.data.limit(b.data.capacity())
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        if (b === bHole) b.clear()
    }
}
