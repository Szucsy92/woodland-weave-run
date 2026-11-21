package com.woodlandweave.run.gl

import android.content.Context
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.woodlandweave.run.CameraPose
import com.woodlandweave.run.GameEngine
import com.woodlandweave.run.Orb
import com.woodlandweave.run.sim.*
import java.nio.FloatBuffer
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
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
        const val CROWN_FAR_SUBDIVISIONS = 2      // match the Capacitor canopy mesh at every distance; avoid a visible LOD silhouette seam
        const val CROWN_LOD_DISTANCE = 22.0
        // The shadow casters come from a wider lattice than the playable forest (see rebuildProps), so the light on the
        // ground depends on the real forest and not on the part of it that happens to be loaded around the runner.
        const val SHADOW_RADIUS = 22
        const val MAX_PROPS = (SHADOW_RADIUS * 2 + 1) * (SHADOW_RADIUS * 2 + 1)
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
        /** The web build's crowns, rocks and bushes were flat-shaded polyhedra (three.js non-indexed geometry). */
        const val FLAT_FACETS = true
        /** Power-up orbs: smooth spheres, see-through (1 = opaque). */
        const val ORB_ALPHA = .5f
        /** The one resolution: the scene is drawn at half the surface's width and height and scaled up in the final pass. */
        const val RENDER_SCALE = 0.5f
        /** Off: the draw resolution stays at RENDER_SCALE (the old frame-rate driven scaler is kept below, unused). */
        const val ADAPTIVE_SCALE = false
        // Sharp by default: start close to the device's own resolution and only give ground when 60 fps is lost.
        const val MIN_DENSITY = 1.5f
        const val START_DENSITY = 2.25f
        const val TREE_SWAY = .15f
    }

    // ---- GL objects ----
    private var litProg = 0; private var groundProg = 0; private var spriteProg = 0
    private val locs = android.util.SparseArray<HashMap<String, Int>>()
    private lateinit var meshes: Array<Mesh>
    private lateinit var box: Mesh; private lateinit var orb: Mesh; private lateinit var holeBody: Mesh
    private lateinit var cyl: Mesh; private lateinit var cone: Mesh; private lateinit var ico0: Mesh
    private lateinit var quad: Mesh
    private lateinit var torusA: Mesh; private lateinit var torusB: Mesh; private lateinit var torusC: Mesh
    private var groundVao = 0; private var groundIndexCount = 0
    private var barkTex = 0; private var barkN = 0; private var barkR = 0
    private var floorTex = 0; private var floorN = 0; private var floorR = 0
    private var glowLow = 0; private var sporeLow = 0; private var glowSmooth = 0; private var sporeSmooth = 0
    private var glowTex = 0; private var sporeTex = 0; private var holeGlowTex = 0; private var ringTex = 0; private var whiteTex = 0
    private var spriteVao = 0; private var spriteVbo = 0
    private val post = PostFx()
    private val surfaceDensity = minOf(context.resources.displayMetrics.density, GameView.MAX_DENSITY)
    private val scaler = Scaler()

    // ---- graphics tiers ----
    private var feat = Features(Quality.LOW)
    private var appliedQuality: Quality? = null
    private var qualityCap = Quality.HIGH
    private var shadowActive = false
    private var shadow: ShadowMap? = null
    private var shadowProg = 0
    private val pp = PostParams()
    private var animT = 0.0
    private var aspectRatio = 16f / 9f
    private var lastCamX = 0.0; private var lastCamZ = 0.0
    /** Unit vector towards the key light (the sun between sunrise and sunset, the moon otherwise). Updated every frame. */
    private val moonDirV = run { val ml = sqrt(24f * 24f + 20f * 20f); floatArrayOf(0f, 24f / ml, -20f / ml) }
    private val effects = EffectsModel()
    private val effectState = EffectState()
    // Reused upload buffers for four GLES 3.0 local-light slots.
    private val localLightPositions = FloatArray(16)
    private val localLightColors = FloatArray(16)
    // Trunks that can block a light (30 vec4: six per light, slots 0..3 pickups and 4 the lamp).
    private val occ = FloatArray(30 * 4)
    private var treeN = 0
    private var treeX = FloatArray(0); private var treeZ = FloatArray(0); private var treeR = FloatArray(0)
    private val pickD = DoubleArray(6); private val pickI = IntArray(6)
    private var fogFar = FOG_END.toFloat()

    private class Sprites(val capacity: Int, val texture: () -> Int, val additive: Boolean, val fog: Boolean) {
        val data: FloatBuffer = GlUtil.floatBuffer(capacity * 10)
        var count = 0
        fun clear() { data.clear(); count = 0 }
        /** [ground] is how far the sprite's centre is above the ground; > 0 makes it fade out softly below ground level. */
        fun add(x: Double, y: Double, z: Double, sx: Double, sy: Double, c: FloatArray, a: Double, ground: Double = 0.0) {
            if (count >= capacity || a <= 0.002) return
            data.put(x.toFloat()).put(y.toFloat()).put(z.toFloat()).put(sx.toFloat()).put(sy.toFloat())
                .put(c[0]).put(c[1]).put(c[2]).put(a.toFloat().coerceIn(0f, 1f)).put(ground.toFloat())
            count++
        }
    }
    // `fog` mirrors three.js's SpriteMaterial.fog of the web build: forest lights, spores and black-hole glow
    // opt out, mist, orb auras and secret glows do not.
    private val bGlow = Sprites(256, { glowTex }, true, false)
    private val bGlowFog = Sprites(64, { glowTex }, true, true)
    private val bMist = Sprites(16, { glowTex }, false, true)
    private val bSpore = Sprites(128, { sporeTex }, true, false)
    private val bHole = Sprites(1600, { holeGlowTex }, true, false)

    private val bufs = Array(KINDS) {
        GlUtil.floatBuffer(MAX_PROPS * (if (it == K_CROWN || it == K_CROWN_FAR) 16 else if (it == K_BRANCH) 11 else if (it == K_ROOT) 5 else 2) * 16)
    }
    private val counts = IntArray(KINDS)
    // Instances drawn into the shadow map: the visible ones plus trees beyond the view whose long shadows reach into it.
    private val shadowCounts = IntArray(KINDS)
    // Shadow casters shrink to nothing between these distances from the runner, only at the very rim of the shadow lattice
    // (SHADOW_RADIUS * CELL = 132 m, about 120 m is inside it in every direction): thinning trees at the rim, not popping trees.
    // Beyond SHADOW_FADE_END they are not drawn into the shadow map at all. The rim must lie beyond what the runner can see
    // (fog, about 45 m) PLUS the longest shadow (a crown 22 m up at 23 degrees throws it about 50 m), so that every visible
    // patch of ground has all the trees that can shade it. Then a pool of light is a fact of the forest, not of the loaded part.
    private val SHADOW_FADE_START = 100f
    private var shadowTrunk: Mesh? = null; private var shadowRoot: Mesh? = null; private var shadowBranch: Mesh? = null
    private val SHADOW_TRUNK_ROWS = listOf(22f, 18f, 14f, 10f, 6.5f, 4.2f, 3.2f, 2.4f, 1.6f, 0.9f, 0.4f, 0f)
    private val SHADOW_FADE_END = 118f
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
    private var encode = 1f
    private var camFwdX = 0f; private var camFwdY = 0f; private var camFwdZ = -1f
    private var dayLevel = -1.0
    private var cyc = -1.0   // eased position in the day cycle (0 night, .25 dawn, .5 day, .75 dusk)
    private var lampDay = 1f
    private var wormBase = FloatArray(0); private var remBase = FloatArray(0); private var remSeed = -1

    // ---- props (trees and obstacles) ----
    private class Prop(val id: String, val x: Double, val z: Double, val kinds: IntArray, val m: FloatArray)
    private val cache = HashMap<String, Prop>()
    private var props: List<Prop> = emptyList()
    /** Trees outside the playable window: they exist only to cast shadows (the same trees the forest has there). */
    private var shadowRing: List<Prop> = emptyList()
    private var builtVersion = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The lit shader needs the tier defines (LOCAL_LIGHT_COUNT, Q_ENV, Q_SHADOW); start on the LOW tier.
        feat = Features(Quality.LOW); appliedQuality = null
        val startDefs = feat.litDefines()
        litProg = GlUtil.program(Shaders.LIT_VS, Shaders.litFs(startDefs))
        groundProg = GlUtil.program(Shaders.GROUND_VS, Shaders.litFs(startDefs))
        shadow = null; shadowProg = 0
        spriteProg = GlUtil.program(Shaders.SPRITE_VS, Shaders.SPRITE_FS)
        locs.clear()
        meshes = arrayOf(
            Mesh(Geo.trunk(), MAX_PROPS), Mesh(Geo.flatQuad(), MAX_PROPS * 2), Mesh(Geo.root(), MAX_PROPS * 5),
            Mesh(Geo.branch(), MAX_PROPS * 11), Mesh(Geo.crown(CROWN_NEAR_SUBDIVISIONS, false), MAX_PROPS * 16),
            Mesh(Geo.jittered(2, .18f, .14f, 33, FLAT_FACETS), MAX_PROPS), Mesh(Geo.jittered(2, .35f, .4f, 47, FLAT_FACETS), MAX_PROPS),
            Mesh(Geo.crown(CROWN_FAR_SUBDIVISIONS, false), MAX_PROPS * 16),
        )
        box = Mesh(Geo.box(1f, 1f, 1f), 4); orb = Mesh(Geo.icosphere(3), 4); holeBody = Mesh(Geo.icosphere(3), 4)
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

        barkTex = GlUtil.assetTexture(context, "wet-bark.jpg", srgb = true); barkN = GlUtil.assetTexture(context, "bark-normal.jpg"); barkR = GlUtil.assetTexture(context, "bark-rough.jpg")
        floorTex = GlUtil.assetTexture(context, "wet-ground.jpg", srgb = true); floorN = GlUtil.assetTexture(context, "ground-normal.jpg"); floorR = GlUtil.assetTexture(context, "ground-rough.jpg")
        glowLow = GlUtil.glowTexture(floatArrayOf(0f, .2f, 1f), intArrayOf(255, 153, 0))
        // Spores: a soft, peaked mote (no flat plateau, so no visible disc edge), identical on every tier.
        sporeLow = GlUtil.smoothGlowTexture { r -> sporeProfile(r) }
        // Medium and High: the same glows with smooth profiles (no visible kink where the gradient changes slope).
        glowSmooth = GlUtil.smoothGlowTexture { r ->
            val s = r * r * (3f - 2f * r)
            .62f * (1f - s).toDouble().pow(.9).toFloat() + .38f * kotlin.math.exp(-(r / .12f) * (r / .12f))
        }
        sporeSmooth = GlUtil.smoothGlowTexture { r -> sporeProfile(r) }
        glowTex = glowLow; sporeTex = sporeLow
        holeGlowTex = GlUtil.glowTexture(floatArrayOf(0f, .35f, 1f), intArrayOf(255, 89, 0))
        ringTex = GlUtil.ringTexture(); whiteTex = GlUtil.whiteTexture()

        val sv = IntArray(1); val sb = IntArray(1)
        GL.glGenVertexArrays(1, sv, 0); GL.glGenBuffers(1, sb, 0)
        spriteVao = sv[0]; spriteVbo = sb[0]
        GL.glBindVertexArray(spriteVao); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, spriteVbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, 1600 * 40, null, GL.GL_STREAM_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 40, 0); GL.glVertexAttribDivisor(0, 1)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 40, 12); GL.glVertexAttribDivisor(1, 1)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 4, GL.GL_FLOAT, false, 40, 20); GL.glVertexAttribDivisor(2, 1)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 1, GL.GL_FLOAT, false, 40, 36); GL.glVertexAttribDivisor(3, 1)
        GL.glBindVertexArray(0)

        post.create()
        GL.glEnable(GL.GL_DEPTH_TEST)
        builtVersion = -1; cache.clear(); lastNs = 0L; remSeed = -1
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GL.glViewport(0, 0, w, h)
        aspectRatio = w.toFloat() / max(1, h)
        Matrix.perspectiveM(proj, 0, FOV, w.toFloat() / max(1, h), 0.1f, 220f)
        post.resize(w, h, if (ADAPTIVE_SCALE) scaler.density / surfaceDensity else RENDER_SCALE)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1.0 / 60 else (now - lastNs) / 1e9
        lastNs = now
        engine.frame(dt)
        draw(dt)
    }

    // ---- uniform helpers ----
    private fun loc(p: Int, name: String): Int {
        val m = locs.get(p) ?: HashMap<String, Int>().also { locs.put(p, it) }
        return m.getOrPut(name) { GL.glGetUniformLocation(p, name) }   // no per-call string building (it ran hundreds of times a frame)
    }
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
        metal: Float = 0f,
    ) {
        u3(p, "uColor", color)
        if (emissive != null) u3(p, "uEmissive", emissive[0] * ek, emissive[1] * ek, emissive[2] * ek) else u3(p, "uEmissive", 0f, 0f, 0f)
        u1(p, "uAlpha", alpha); u1(p, "uMaps", if (maps) 1f else 0f); u1(p, "uRough", rough); u1(p, "uNormalScale", ns)
        GL.glUniform2f(loc(p, "uUvScale"), uvx, uvy); GL.glUniform1i(loc(p, "uMode"), mode); u1(p, "uFogOn", fogOn); u1(p, "uMetal", metal)
    }

    // ---- building props from the engine's world ----
    private fun rebuildProps() {
        val s = engine.sim
        val next = ArrayList<Prop>(s.trees.size + s.obstacles.size)
        val alive = HashSet<String>()
        for (t in s.trees) { next += cache.getOrPut(t.id) { treeProp(t) }; alive += t.id }
        for (o in s.obstacles) { next += cache.getOrPut(o.id) { obstacleProp(o) }; alive += o.id }
        // The shadow ring: the very same deterministic trees the forest has beyond the playable window, around the window's own
        // lattice centre. In Freeroam the engine clears trees around secrets, gates and black holes; the ring gets the same
        // clearing, so no shadow is cast by a tree that is not there.
        val ring = ArrayList<Prop>()
        val cx = engine.worldCx; val cz = engine.worldCz
        val free = s.mode == GameMode.FREEROAM
        val holes = if (free) holesAround(s.x, s.z, s.universe) else emptyList()
        fun open(x: Double, z: Double) = !free ||
            (engine.secrets.all { hypot(x - it.x, z - it.z) > SECRET_CLEARING } &&
                engine.gates.all { hypot(x - it.x, z - it.z) > GATE_CLEARING } &&
                holes.all { hypot(x - it.x, z - it.z) > HOLE_CLEARING })
        for (t in treesAround(cx, cz, levelOf(s.difficulty).treeGap, s.universe, SHADOW_RADIUS)) {
            if (abs(floor(t.x / CELL).toInt() - cx) <= RADIUS && abs(floor(t.z / CELL).toInt() - cz) <= RADIUS) continue   // inside the window: already a real tree (or removed on purpose)
            if (!open(t.x, t.z)) continue
            ring += cache.getOrPut(t.id) { treeProp(t) }; alive += t.id
        }
        cache.keys.retainAll(alive)
        props = next
        shadowRing = ring
        treeN = s.trees.size
        if (treeX.size < treeN) { treeX = FloatArray(treeN + 64); treeZ = FloatArray(treeN + 64); treeR = FloatArray(treeN + 64) }
        for ((i, t) in s.trees.withIndex()) {
            val species = min(3, floor(hash(floor(t.x).toInt(), floor(t.z).toInt(), 200) * 4).toInt())
            val girth = doubleArrayOf(.8, .9, 1.15, .68)[species] * t.scale
            treeX[i] = t.x.toFloat(); treeZ[i] = t.z.toFloat(); treeR[i] = (.42 * girth + .08).toFloat()
        }
    }

    /** Gaussian core with a smooth outer fade: peaks softly in the middle and reaches exactly 0 at the sprite edge. */
    private fun sporeProfile(r: Float): Float {
        val core = kotlin.math.exp(-(r / .33f) * (r / .33f))
        val t = ((r - .6f) / .4f).coerceIn(0f, 1f)
        return .75f * core * (1f - t * t * (3f - 2f * t))
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
        animT += dt
        val tune = e.settings.fx
        pp.tune = tune
        pp.sharpen = feat.sharpen * tune.sharp
        fogFar = (FOG_END * feat.fogFarScale).toFloat()
        shadowActive = shadow != null && feat.shadows && tune.shadows > 0.01f
        if (post.depthFailed && qualityCap.ordinal > Quality.MEDIUM.ordinal) qualityCap = Quality.MEDIUM
        val wantQ = Quality.values()[minOf(e.settings.quality.ordinal, qualityCap.ordinal)]
        if (appliedQuality != wantQ) applyQuality(wantQ)

        // Day cycle: fog, moon light and hemisphere drift towards day colours, and towards blood near the pursuer.
        val hours = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60.0 }
        val target = timeOfDayFor(e.settings.mood, e.settings.timeOfDay.toDouble(), hours)
        if (cyc < 0) cyc = target
        else {
            // Ease towards the target the short way round the cycle: a slider drag or a mode switch glides, Auto just follows the clock.
            var d = target - cyc; d -= Math.rint(d)
            cyc = (((cyc + d * min(1.0, dt * 14.0)) % 1.0) + 1.0) % 1.0
        }
        dayLevel = .5 - .5 * cos(2.0 * PI * cyc)   // 0 at night, .5 at dawn and dusk, 1 at day
        val k = dayLevel.toFloat()
        // Twilight weight: 0 at full night / full day, 1 half way (dawn or dusk).
        val dusk = (1f - kotlin.math.abs(2f * k - 1f)).coerceIn(0f, 1f)
        val tw = ((dusk - .2f) / .6f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val warmSky = floatArrayOf(1.00f, 0.30f, 0.075f)
        val warmFog = floatArrayOf(0.48f, 0.12f, 0.045f)
        lampDay = if (feat.rank >= 1) 1f - .2f * k else 1f
        // Keep exposure nearly constant across the cycle: the eye should read the changing light, not an artificial brightness pump.
        pp.exposureScale = .96f + .035f * k + .012f * dusk
        // Bloom is reserved for genuinely bright sources; foliage and fog should retain texture.
        pp.bloomScale = .78f - .08f * k + .025f * dusk
        // Volumetric shafts remain visible, but never overpower the forest itself.
        pp.shaftScale = 1.22f - .18f * k + .16f * tw
        red = e.redMix.toFloat(); lights = 1f - e.dieRef.toFloat()

        // TWO lights, each with its own arc and its own on/off window (see SkyLight.kt):
        //  - the SUN shines from sunrise (cycle .25) to sunset (.75) and is OFF at night,
        //  - the MOON shines from moonrise (just before the sun has set) to moonset (just after the sun has risen) and is OFF by day.
        // Each one rises in the EAST (right), climbs to its own highest point straight ahead (NORTH) and sets in the WEST (left),
        // low -> high -> low, always in front of the runner. Nothing slides between the two: one switches off as the other switches on,
        // so no single light has to swing from one side to the other. Heights and intensities are set per light in SkyLights.
        // Only one directional light feeds the shadow map and the beams, the one that is stronger right now; the overlap is so short
        // and both are faded to almost nothing at the horizon, so the hand-over is not visible.
        // No sway, drift or pulsing: the angle depends on the time of day only (the Napszak slider / the clock), so while running
        // the beams and light pools stay exactly where they are (the canopy openings, SUN_GAP_FN, are fixed in the world).
        val sunTh = SkyLights.SUN.progress(cyc); val moonTh = SkyLights.MOON.progress(cyc)
        val sunE = if (sunTh >= 0) SkyLights.SUN.intensity(sunTh) * SkyLights.SUN.weight(sunTh) else 0f
        val moonE = if (moonTh >= 0) SkyLights.MOON.intensity(moonTh) * SkyLights.MOON.weight(moonTh) else 0f
        val sunKey = (sunTh >= 0 && sunE >= moonE) || moonTh < 0
        val keyTh = if (sunKey) sunTh else moonTh
        val keyLight = if (sunKey) SkyLights.SUN else SkyLights.MOON
        if (keyTh >= 0) keyLight.direction(keyTh, moonDirV)
        moonCol = if (sunKey) {
            // Warm gold / rose near the horizon, white-warm at the highest point.
            val horizon = 1.0 - sin(PI * keyTh)
            val warm = horizon.let { x -> val c = ((x - .5) / .5).coerceIn(0.0, 1.0); c * c * (3 - 2 * c) }.toFloat() * .85f
            Palette.mix(Palette.sunDay, if (keyTh < .5) Palette.sunDawn else Palette.sunDusk, warm)
        } else Palette.moon
        val moonI = maxOf(sunE, moonE)   // 0 only if both lights are off (never happens: the windows overlap)

        // The day's base colours are dark and greyish: the sun itself does the lighting.
        // Past dawn the day brightens further (dl = 0 at dawn/dusk, so the twilight look is untouched).
        val dl0 = ((k - .5f) * 2f).coerceIn(0f, 1f)
        val dl = dl0 * dl0 * (3f - 2f * dl0)
        fog = Palette.mix(Palette.mix(Palette.mix(Palette.fog, Palette.dayFog, k), warmFog, tw * .22f), Palette.dayFogLight, dl)
        sky = Palette.mix(Palette.mix(Palette.mix(Palette.moon, Palette.daySky, k), warmSky, tw * .30f), Palette.daySkyLight, dl)
        // Richer atmospheric hues, while reducing the ambient veil that used to flatten contrast.
        fun saturate(c: FloatArray, amount: Float): FloatArray {
            val l = .2126f * c[0] + .7152f * c[1] + .0722f * c[2]
            return FloatArray(3) { l + (c[it] - l) * amount }
        }
        fog = saturate(fog, 1.20f)
        sky = saturate(sky, 1.22f)
        // Deepen the atmospheric veil: richer hues, lower luminance, less milky daylight.
        fog = floatArrayOf(fog[0] * .84f, fog[1] * .82f, fog[2] * .83f)
        sky = floatArrayOf(sky[0] * .90f, sky[1] * .88f, sky[2] * .91f)
        groundC = Palette.mix(fog, Palette.pursuer, red * .55f)
        // Ambient fill prevents crushed shadows without flattening the directional sunlight.
        hemi = .48f + k * .19f + tw * .045f + dl * .08f + red * 1.4f
        // Fog starts later by day, so the distant lights stay readable.
        fogNear = 8f + k * 3.5f

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

        if (ADAPTIVE_SCALE && scaler.tick(dt)) post.setScale(scaler.density / surfaceDensity)
        encode = if (post.linear) 0f else 1f
        camFwdX = -view[2]; camFwdY = -view[6]; camFwdZ = -view[10]
        System.arraycopy(vp, 0, pp.vp, 0, 16); System.arraycopy(proj, 0, pp.proj, 0, 16)
        Matrix.invertM(pp.invVp, 0, vp, 0)
        pp.camX = cam.ex.toFloat(); pp.camY = cam.ey.toFloat(); pp.camZ = cam.ez.toFloat()
        // Bloom picks up lights, not the day sky: the threshold rises with the brightness of the fog.
        pp.bloomThreshold = max(feat.bloomThreshold, (.2126f * fog[0] + .7152f * fog[1] + .0722f * fog[2]) * 1.45f + .18f)
        pp.near = .1f; pp.far = 220f; pp.aspect = aspectRatio
        pp.tanHalf = tan(Math.toRadians(FOV / 2.0)).toFloat()
        System.arraycopy(moonDirV, 0, pp.moonDir, 0, 3)
        val shaftK = .95f - .10f * k
        pp.moonCol[0] = moonCol[0] * moonI * shaftK; pp.moonCol[1] = moonCol[1] * moonI * shaftK; pp.moonCol[2] = moonCol[2] * moonI * shaftK
        val camSpeed = if (dt > 0) hypot(cam.ex - lastCamX, cam.ez - lastCamZ) / dt else 0.0
        lastCamX = cam.ex; lastCamZ = cam.ez
        // Motion blur grows smoothly with the runner's speed (nothing when walking, a light radial smear at a sprint).
        val sp01 = ((camSpeed - 3.0) / 15.0).coerceIn(0.0, 1.0)
        pp.motion = (sp01 * sp01 * feat.motionBlurMax * pp.tune.motion).toFloat()

        updateLocalLights(s, e, cam)
        post.bind()
        // Re-establish the full-frame state before clearing. Shadow/depth-only passes
        // and device/driver state carry-over must never leave a clipped or masked strip
        // at the top of the next frame.
        GL.glDisable(GL.GL_SCISSOR_TEST)
        GL.glColorMask(true, true, true, true)
        GL.glDepthMask(true)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_BLEND)
        val clear = if (post.linear) fog else Palette.encodeSrgb(fog)
        GL.glClearColor(clear[0], clear[1], clear[2], 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)

        val behindX = sin(s.heading); val behindZ = cos(s.heading)
        val gy = groundHeight(s.x, s.z)
        val lying = s.phase == Phase.FALLING || s.phase == Phase.FALLEN
        val redI = (e.redMix * (if (lying) 140.0 else 95 + s.threat * 90)).toFloat()
        val floodI = (e.redMix * (if (lying) 3.2 else 2.6 + s.threat * 2.2)).toFloat()
        var fdx = (behindX * 50).toFloat(); var fdy = 8f; var fdz = (behindZ * 50).toFloat()
        val fl = sqrt(fdx * fdx + fdy * fdy + fdz * fdz); fdx /= fl; fdy /= fl; fdz /= fl

        fun scene(p: Int) {
            // The runner and opaque world meshes share this material/light uniform set:
            // local orb lights, sky/ground bounce, directional shadows and the day/night palette
            // therefore affect the character as well as nearby scenery.
            GL.glUseProgram(p)
            GL.glUniformMatrix4fv(loc(p, "uVP"), 1, false, vp, 0)
            u3(p, "uCam", cam.ex.toFloat(), cam.ey.toFloat(), cam.ez.toFloat())
            u3(p, "uFwd", camFwdX, camFwdY, camFwdZ); u1(p, "uEncode", encode)
            u1(p, "uTime", animT.toFloat())
            val sm = shadow
            // A sampler2DShadow left on its default unit 0 shares it with the sampler2D uTex: GLES then raises
            // INVALID_OPERATION and drops every draw. Always point it at its own unit, shadows on or off.
            GL.glUniform1i(loc(p, "uShadow0"), 5)
            if (sm != null && shadowActive) {
                GL.glActiveTexture(GL.GL_TEXTURE5); GL.glBindTexture(GL.GL_TEXTURE_2D, sm.tex[0])
                GL.glActiveTexture(GL.GL_TEXTURE0)
                GL.glUniformMatrix4fv(loc(p, "uLightVP0"), 1, false, sm.vp[0], 0)
                GL.glUniform1f(loc(p, "uShadowTexel"), 1f / sm.res)
                u1(p, "uShadowOn", 1f); u1(p, "uShadowK", min(1f, pp.tune.shadows))
            } else u1(p, "uShadowOn", 0f)
            u3(p, "uFog", fog); u1(p, "uFogNear", fogNear); u1(p, "uFogFar", fogFar)
            u3(p, "uSky", sky); u3(p, "uGroundC", groundC); u1(p, "uHemi", hemi)
            u3(p, "uMoonDir", moonDirV[0], moonDirV[1], moonDirV[2])
            u3(p, "uMoon", moonCol[0] * moonI, moonCol[1] * moonI, moonCol[2] * moonI)
            u3(p, "uLampPos", e.lampX.toFloat(), e.lampY.toFloat(), e.lampZ.toFloat()); u3(p, "uLampCol", Palette.lamp); u1(p, "uLampI", e.lampIntensity.toFloat() * lampDay); u1(p, "uLampSoft", feat.lampSoft)
            // Local lights (the nearest pickups) and the trunks that can block them were prepared once for this frame.
            GL.glUniform4fv(loc(p, "uLocalPos[0]"), 4, localLightPositions, 0)
            GL.glUniform4fv(loc(p, "uLocalColor[0]"), 4, localLightColors, 0)
            GL.glUniform4fv(loc(p, "uOcc[0]"), 30, occ, 0)
            u1(p, "uLodBias", feat.lodBias)
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
        val smap = shadow
        if (smap != null && shadowActive) {
            shadowPass(smap, cam)
            post.bind()
            GL.glEnable(GL.GL_DEPTH_TEST)
            System.arraycopy(smap.vp[0], 0, pp.lightVp0, 0, 16)
            pp.shadowTex0 = smap.tex[0]
        } else pp.shadowTex0 = 0
        scene(litProg)
        val g = e.ghostK.toFloat()
        bindMaps(barkTex, barkN, barkR)
        if (feat.wind) setSway(litProg, TREE_SWAY, 0f, 0f)
        if (g > 0f) {
            // Pass 1: establish the nearest visible tree surface without writing colour.
            // The native renderer batches trunks, roots and branches separately; blending
            // those batches directly can expose their submission order as horizontal bands.
            // A depth-only prepass makes the following ghost pass independent of batch order.
            GL.glColorMask(false, false, false, false)
            GL.glDepthMask(true)
            GL.glDisable(GL.GL_BLEND)
            material(litProg, Palette.trunk, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            meshes[K_TRUNK].draw(bufs[K_TRUNK], counts[K_TRUNK])
            meshes[K_ROOT].draw(bufs[K_ROOT], counts[K_ROOT])
            material(litProg, Palette.trunk, mode = 0, rough = .95f, maps = true, ns = .7f, uvx = 1.5f, uvy = 7f)
            meshes[K_BRANCH].draw(bufs[K_BRANCH], counts[K_BRANCH])
            GL.glColorMask(true, true, true, true)

            // Pass 2: shade only the nearest surfaces. Keep depth testing enabled but
            // freeze the prepass depth so transparent batches cannot mask one another.
            GL.glEnable(GL.GL_BLEND)
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
            GL.glDepthFunc(GL.GL_LEQUAL)
            GL.glDepthMask(false)
            material(litProg, Palette.trunk, Palette.ghostGlow, g * .55f, 1f - g * .34f, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            meshes[K_TRUNK].draw(bufs[K_TRUNK], counts[K_TRUNK])
            meshes[K_ROOT].draw(bufs[K_ROOT], counts[K_ROOT])
            material(litProg, Palette.trunk, Palette.ghostGlow, g * .55f, 1f - g * .34f, mode = 0, rough = .95f, maps = true, ns = .7f, uvx = 1.5f, uvy = 7f)
            meshes[K_BRANCH].draw(bufs[K_BRANCH], counts[K_BRANCH])
            GL.glDepthMask(true)
            GL.glDepthFunc(GL.GL_LESS)
            GL.glDisable(GL.GL_BLEND)
        } else {
            material(litProg, Palette.trunk, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            meshes[K_TRUNK].draw(bufs[K_TRUNK], counts[K_TRUNK])
            meshes[K_ROOT].draw(bufs[K_ROOT], counts[K_ROOT])
            material(litProg, Palette.trunk, mode = 0, rough = .95f, maps = true, ns = .7f, uvx = 1.5f, uvy = 7f)
            meshes[K_BRANCH].draw(bufs[K_BRANCH], counts[K_BRANCH])
        }
        // Closed round blobs: three.js culled their back faces (FrontSide); doing the same halves the canopy's overdraw.
        GL.glEnable(GL.GL_CULL_FACE); GL.glCullFace(GL.GL_BACK)
        material(litProg, Palette.crown, mode = 5, rough = .9f)
        meshes[K_CROWN].draw(bufs[K_CROWN], counts[K_CROWN])
        meshes[K_CROWN_FAR].draw(bufs[K_CROWN_FAR], counts[K_CROWN_FAR])
        if (feat.wind) setSway(litProg, 0f, 0f, 0f)    // rocks and bushes stay put
        material(litProg, Palette.rock, rough = .9f)
        meshes[K_ROCK].draw(bufs[K_ROCK], counts[K_ROCK])
        material(litProg, Palette.plant, mode = 6, rough = .9f)
        meshes[K_PLANT].draw(bufs[K_PLANT], counts[K_PLANT])
        GL.glDisable(GL.GL_CULL_FACE)

        drawLandmarks(s, e, cam)
        drawHoles(s)
        drawRelics(s, cam)

        // Contact shadows: soft dark decals just above the ground (High has real shadows instead).
        if (!feat.shadows) {
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            bindMaps(glowTex, glowTex, glowTex)
            material(litProg, floatArrayOf(0f, 0f, 0f), alpha = .6f, mode = 1)
            meshes[K_SHADOW].draw(bufs[K_SHADOW], counts[K_SHADOW])
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }

        buildSprites(s, e)
        for (b in arrayOf(bMist, bGlow, bGlowFog, bSpore, bHole)) drawSprites(b)

        post.present(effectState, e.glass, pp)
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


    // ---- local lights and the trunks that block them ----

    /** Writes up to six trunks closest to the segment a-b (within 11 m) into occluder slot [slot]. */
    private fun pickOccluders(slot: Int, ax: Double, az: Double, bx: Double, bz: Double) {
        java.util.Arrays.fill(pickD, 1e9); java.util.Arrays.fill(pickI, -1)
        val dx = bx - ax; val dz = bz - az
        val len2 = max(dx * dx + dz * dz, 1e-4)
        for (i in 0 until treeN) {
            val tx = treeX[i] - ax; val tz = treeZ[i] - az
            val h = ((tx * dx + tz * dz) / len2).coerceIn(0.0, 1.0)
            val ex = tx - dx * h; val ez = tz - dz * h
            val d = sqrt(ex * ex + ez * ez)
            if (d >= pickD[5] || d > 11.0) continue
            var k = 5
            while (k > 0 && pickD[k - 1] > d) { pickD[k] = pickD[k - 1]; pickI[k] = pickI[k - 1]; k-- }
            pickD[k] = d; pickI[k] = i
        }
        val base = slot * 6 * 4
        for (j in 0 until 6) {
            val i = pickI[j]
            occ[base + j * 4] = if (i >= 0) treeX[i] else 0f
            occ[base + j * 4 + 1] = if (i >= 0) treeZ[i] else 0f
            occ[base + j * 4 + 2] = if (i >= 0) treeR[i] else 0f
            occ[base + j * 4 + 3] = 0f
        }
    }

    /**
     * The nearest pickups light their surroundings (a taken orb keeps its light while it flies in and fades), and the
     * lamp and each of those lights get the list of trunks that can block them.
     */
    private fun updateLocalLights(s: Simulation, e: GameEngine, cam: CameraPose) {
        java.util.Arrays.fill(localLightPositions, 0f)
        java.util.Arrays.fill(localLightColors, 0f)
        java.util.Arrays.fill(occ, 0f)
        val lightCount = if (feat.rank >= 2) 4 else if (feat.rank >= 1) 2 else 1
        val baseIntensity = if (feat.rank >= 2) 19f else if (feat.rank >= 1) 13f else 8f
        val lightRadius = if (feat.rank >= 2) 15.5f else if (feat.rank >= 1) 14f else 12.5f
        val t = s.elapsed
        e.orbs.asSequence()
            .map { orbState ->
                val dx = orbState.p.x - cam.ex
                val dz = orbState.p.z - cam.ez
                Triple(orbState, dx * dx + dz * dz, hypot(dx, dz))
            }
            .filter { it.third < 23.0 }
            .sortedBy { it.second }
            .take(lightCount)
            .forEachIndexed { index, entry ->
                val orbState = entry.first
                val taken = orbState.taken
                val kk = if (taken != null) min(1.0, (t - taken) / .25) else 0.0
                val flow = min(1.0, kk * 1.5)
                val base = groundHeight(orbState.p.x, orbState.p.z) + 1.15
                val px = if (taken != null) orbState.p.x + (cam.ex - orbState.p.x) * flow * .6 else orbState.p.x
                val pz = if (taken != null) orbState.p.z + (cam.ez - orbState.p.z) * flow * .6 else orbState.p.z
                val py = if (taken != null) base + (cam.ey - .35 - base) * flow else base
                val distanceFade = (1.0 - (entry.third / 23.0)).coerceIn(0.0, 1.0).toFloat()
                val pulse = 0.92f + 0.08f * sin(animT.toFloat() * (2.0f + index * 0.17f) + index * 1.31f)
                val offset = index * 4
                localLightPositions[offset] = px.toFloat()
                localLightPositions[offset + 1] = py.toFloat()
                localLightPositions[offset + 2] = pz.toFloat()
                localLightPositions[offset + 3] = baseIntensity * distanceFade * pulse * (1f - kk.toFloat()) * orbIn(orbState, t).toFloat()
                val tint = orbColor(orbState.p.type)
                localLightColors[offset] = tint[0]; localLightColors[offset + 1] = tint[1]; localLightColors[offset + 2] = tint[2]
                localLightColors[offset + 3] = lightRadius
                pickOccluders(index, px, pz, cam.ex, cam.ez)
            }
        pickOccluders(4, e.lampX, e.lampZ, cam.ex, cam.ez)
    }

    /** 1 near the player, easing to 0 between [a] and [b] metres: far lights appear and leave gradually. */
    private val orbSeen = HashMap<String, Double>()
    /** 0 -> 1 over 1.4 s from the moment an orb is first seen, so a new orb grows in instead of popping. */
    private fun orbIn(o: Orb, t: Double): Double = smoothRange(t - orbSeen.getOrPut(o.p.id) { t }, 0.0, 1.4)

    private fun farFade(x: Double, z: Double, s: Simulation, a: Double, b: Double) = 1.0 - smoothRange(hypot(x - s.x, z - s.z), a, b)

    // ---- graphics tiers ----
    private fun setSway(p: Int, a: Float, b: Float, c: Float) = GL.glUniform3f(loc(p, "uSway"), a, b, c)

    /** Switches tier: effect chain, shaders, shadow map, round shapes. Anything that fails drops one tier instead of crashing. */
    private fun applyQuality(q: Quality) {
        var f = post.configure(Features(q))
        while (true) {
            val defs = f.litDefines()
            val lp = GlUtil.programOrNull(Shaders.LIT_VS, Shaders.litFs(defs))
            val gp = if (lp != 0) GlUtil.programOrNull(Shaders.GROUND_VS, Shaders.litFs(defs)) else 0
            var sm: ShadowMap? = null; var sp = 0
            var ok = lp != 0 && gp != 0
            if (ok && f.shadows) {
                sm = ShadowMap(f.shadowRes, 1).also { it.create() }
                sp = GlUtil.programOrNull(Shaders.SHADOW_VS, Shaders.SHADOW_FS)
                if (!sm.ok || sp == 0) { sm.release(); sm = null; if (sp != 0) GL.glDeleteProgram(sp); ok = false }
            }
            if (ok) {
                GL.glDeleteProgram(litProg); GL.glDeleteProgram(groundProg)
                if (shadowProg != 0) GL.glDeleteProgram(shadowProg)
                shadow?.release()
                litProg = lp; groundProg = gp; shadowProg = sp; shadow = sm; feat = f
                break
            }
            if (lp != 0) GL.glDeleteProgram(lp)
            if (gp != 0) GL.glDeleteProgram(gp)
            if (f.quality == Quality.LOW) { feat = f; break }
            f = post.configure(Features(Quality.values()[f.quality.ordinal - 1]))
        }
        locs.clear()
        rebuildTierMeshes(feat)
        glowTex = if (feat.smoothMeshes) glowSmooth else glowLow
        // Spores always use the smooth, broad-core profile, even on Low; mesh quality is unrelated to sprite filtering.
        sporeTex = sporeSmooth
        appliedQuality = q
        // A tier the device could not reach must not be retried every frame.
        if (feat.quality.ordinal < q.ordinal) qualityCap = feat.quality
    }

    /** Low keeps the faceted web look; Medium and up get smooth, finely tessellated round shapes and shapely trees. */
    private fun rebuildTierMeshes(f: Features) {
        val flat = !f.smoothMeshes
        val old = arrayListOf(meshes[K_CROWN], meshes[K_ROCK], meshes[K_PLANT], meshes[K_CROWN_FAR], meshes[K_TRUNK], meshes[K_ROOT], meshes[K_BRANCH])
        meshes[K_CROWN] = Mesh(Geo.crown(if (flat) CROWN_NEAR_SUBDIVISIONS else f.crownNearDetail, false), MAX_PROPS * 16)
        meshes[K_ROCK] = Mesh(if (flat) Geo.jittered(2, .18f, .14f, 33, true) else Geo.jittered(f.rockDetail, .18f, .14f, 33, false), MAX_PROPS)
        meshes[K_PLANT] = Mesh(if (flat) Geo.jittered(2, .35f, .4f, 47, true) else Geo.jittered(f.rockDetail, .35f, .4f, 47, false), MAX_PROPS)
        // Beyond CROWN_LOD_DISTANCE (most trees, and mostly inside the fog) the crown has a quarter of the triangles but keeps
        // the near crown's vertices AND normals at the vertices they share: same lumps, same shading, no band at the LOD distance.
        meshes[K_CROWN_FAR] = Mesh(if (flat) Geo.crown(CROWN_FAR_SUBDIVISIONS, false) else Geo.crownLod(f.crownNearDetail, f.crownFarDetail), MAX_PROPS * 16)
        meshes[K_TRUNK] = Mesh(if (f.lifelikeTrees) Geo.trunkLifelike() else Geo.trunk(), MAX_PROPS)
        meshes[K_ROOT] = Mesh(if (f.lifelikeTrees) Geo.rootLifelike() else Geo.root(), MAX_PROPS * 5)
        meshes[K_BRANCH] = Mesh(if (f.lifelikeTrees) Geo.branchRound() else Geo.branch(), MAX_PROPS * 11)
        // The shadow map only needs silhouettes: coarse copies of the trunk (20 sides, 12 rows, the flare kept fine), the roots
        // (6 x 6) and the branches (6 x 4) that use the very same instance matrices as the visible ones: about 1,300 triangles
        // per tree instead of 6,900, with the same shadow shapes.
        shadowTrunk?.release(); shadowRoot?.release(); shadowBranch?.release()
        shadowTrunk = Mesh(Geo.trunkLifelike(20, SHADOW_TRUNK_ROWS), MAX_PROPS)
        shadowRoot = Mesh(Geo.rootLifelike(6, 6), MAX_PROPS * 5)
        shadowBranch = Mesh(Geo.branchRound(6, 4), MAX_PROPS * 11)
        for (m in old) m.release()
        // Rings and posts: round on Medium and up.
        val oldRound = arrayListOf(cyl, torusA, torusB, torusC)
        if (f.smoothMeshes) {
            cyl = Mesh(Geo.cylinder(.5f, .5f, 1f, 20, 1), 4)
            torusA = Mesh(Geo.torus(2.4f, .14f, 16, 80), 4); torusB = Mesh(Geo.torus(1.5f, .08f, 12, 64), 4); torusC = Mesh(Geo.torus(.8f, .1f, 12, 48), 4)
        } else {
            cyl = Mesh(Geo.cylinder(.5f, .5f, 1f, 8, 1), 4)
            torusA = Mesh(Geo.torus(2.4f, .14f, 8, 40), 4); torusB = Mesh(Geo.torus(1.5f, .08f, 6, 28), 4); torusC = Mesh(Geo.torus(.8f, .1f, 6, 24), 4)
        }
        for (m in oldRound) m.release()
    }

    /** Key-light shadow (solid parts only) centred on the runner. */
    private fun shadowPass(sm: ShadowMap, cam: CameraPose) {
        GL.glUseProgram(shadowProg)
        GL.glUniform1f(loc(shadowProg, "uTime"), animT.toFloat())
        GL.glUniform2f(loc(shadowProg, "uCamXZ"), cam.ex.toFloat(), cam.ez.toFloat())
        GL.glUniform2f(loc(shadowProg, "uFade"), SHADOW_FADE_START, SHADOW_FADE_END)
        val ext = feat.shadowExtent
        // Centred on the runner, never ahead along the heading: the map's coverage does not change when he turns.
        sm.update(0, cam.ex.toFloat(), 1f, cam.ez.toFloat(), moonDirV, ext)
        sm.begin(0)
        GL.glUniformMatrix4fv(loc(shadowProg, "uLightVP"), 1, false, sm.vp[0], 0)
        // Only whole-tree sway moves things, so the shadows follow the trees; rocks and bushes stay put.
        fun draw(kind: Int, sway: Float) {
            GL.glUniform3f(loc(shadowProg, "uSway"), 0f, 0f, 0f)   // shadows (and so the light beams) do not follow the wind: the light stays fixed
            meshes[kind].draw(bufs[kind], shadowCounts[kind])
        }
        // Crowns are NOT drawn into the shadow map: how much light the foliage lets through is the world-fixed sun-gap field
        // (SUN_GAP_FN in Shaders.kt), the same for ground, trunks, bushes and the beams. The map only holds the solid parts.
        GL.glUniform3f(loc(shadowProg, "uSway"), 0f, 0f, 0f)
        shadowTrunk?.draw(bufs[K_TRUNK], shadowCounts[K_TRUNK])
        shadowRoot?.draw(bufs[K_ROOT], shadowCounts[K_ROOT])
        shadowBranch?.draw(bufs[K_BRANCH], shadowCounts[K_BRANCH])
        draw(K_ROCK, 0f); draw(K_PLANT, 0f)
        sm.end()
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

    private fun additiveVeil(color: FloatArray, alpha: Float, tex: Int, par: FloatArray, sx: Double, sy: Double, lx: Double = 0.0, ly: Double = 0.0, lz: Double = 0.0, rz: Double = 0.0, fogOn: Float = 1f) {
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
        bindMaps(tex, tex, tex)
        material(litProg, color, alpha = alpha, mode = 1, fogOn = fogOn)
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
            val vis = orbIn(o, t) * (1.0 - k)          // grows in when it appears, shrinks away when taken
            val vf = vis.toFloat(); val rs = .35 + .65 * vis
            val size = (if (taken != null) max(.001, .42 * (1 - k)) else .42 + sin(t * 2.1 + i) * .035) * (.4 + .6 * orbIn(o, t))
            val flow = min(1.0, k * 1.5)
            val px = if (taken != null) o.p.x + (cam.ex - o.p.x) * flow * .6 else o.p.x
            val pz = if (taken != null) o.p.z + (cam.ez - o.p.z) * flow * .6 else o.p.z
            val py = if (taken != null) base + (cam.ey - .35 - base) * flow else base + sin(t * 1.3 + i * 2) * .08
            // A translucent glass-like sphere: alpha blending, no depth write, front faces only.
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            GL.glEnable(GL.GL_CULL_FACE); GL.glCullFace(GL.GL_BACK)
            material(litProg, col, col, 1.6f, alpha = ORB_ALPHA * (1f - k.toFloat() * .6f) * orbIn(o, t).toFloat(), rough = .2f)
            place(parent, px, py, pz, 0.0, t * .8, 0.0, size)
            uploadOne(orb, parent)
            GL.glDisable(GL.GL_CULL_FACE); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)

            // Unified energy orb: animated plasma bands remain inside the translucent glass sphere.
            val pulse = 1.0 + sin(t * 3.1 + i * 1.7) * .055
            place(parent, px, py, pz, 0.0, t * .22 + i, t * .11, 1.0)
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE)
            GL.glDepthMask(false); GL.glDisable(GL.GL_CULL_FACE)
            material(litProg, floatArrayOf(.08f, .72f, 1f), mode = 2, alpha = .58f * vf)
            prim(torusA, parent, 0.0, 0.0, 0.0, .52 + sin(t * 1.35 + i) * .16, t * .72 + i, .22, .125 * pulse * rs, .125 * pulse * rs, .125 * pulse * rs)
            material(litProg, floatArrayOf(.20f, .38f, 1f), mode = 2, alpha = .44f * vf)
            prim(torusB, parent, 0.0, 0.0, 0.0, 1.08, -t * .91 + i, .82 + sin(t * 1.1 + i) * .18, .18 * pulse * rs, .18 * pulse * rs, .18 * pulse * rs)
            material(litProg, floatArrayOf(.62f, .20f, 1f), mode = 2, alpha = .36f * vf)
            prim(torusC, parent, 0.0, 0.0, 0.0, 1.72, t * .58 + i, -.58, .27 * pulse * rs, .27 * pulse * rs, .27 * pulse * rs)
            // Fine filaments add a second, offset layer to the flowing plasma.
            material(litProg, floatArrayOf(.12f, .88f, 1f), mode = 2, alpha = .30f * vf)
            prim(torusB, parent, 0.0, 0.0, 0.0, .34, t * 1.18 + i * 2, 1.35, .14 * pulse * rs, .14 * pulse * rs, .14 * pulse * rs)
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        if (s.mode != GameMode.FREEROAM) return
        for (g in e.gates) {
            val gyG = groundHeight(g.x, g.z)
            place(parent, g.x, gyG, g.z, 0.0, g.angle, 0.0)
            material(litProg, Palette.rock, Palette.ember, .9f, rough = .4f, metal = .2f)
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
                    material(litProg, Palette.rock, Palette.secret, .08f, rough = .35f, metal = .2f)
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
                additiveVeil(ring.second, ring.third, ringTex, parent, ring.first, ring.first, rz = spin, fogOn = 0f)
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
        fun rockMat(em: FloatArray?, ek: Float, rough: Float, metal: Float = 0f) = material(litProg, Palette.rock, em, ek, rough = rough, metal = metal)
        when (kind) {
            0 -> { rockMat(hue, .35f, .35f, .2f); prim(box, par, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.4, .35)
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
                rockMat(null, 0f, .5f, .2f); prim(cyl, par, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, .28, 6.0, .28)
                rockMat(hue, .5f, .4f); prim(box, par, 0.0, -2.0, 0.0, 0.0, 0.0, 0.0, .9, .7, .9)
                basic(alt); prim(orb, par, 0.0, -.7, 0.0, 0.0, 0.0, 0.0, .6, .6, .6)
                basic(hue); prim(torusC, par, 0.0, .6, 0.0, PI / 2, 0.0, 0.0, 1.0, 1.0, 1.0)
                rockMat(alt, .5f, .4f); prim(cone, par, 0.0, 1.8, 0.0, 0.0, 0.0, 0.0, 1.1, 1.2, 1.1)
                basic(hue); prim(ico0, par, 0.0, 3.3, 0.0, 0.0, 0.0, 0.0, .4, .4, .4)
            }
            else -> {
                for (side in intArrayOf(-1, 1)) { rockMat(hue, .9f, .4f, .2f); prim(box, par, side * 2.2, 0.0, 0.0, 0.0, 0.0, 0.0, .4, 4.6, .4) }
                rockMat(hue, .9f, .4f, .2f); prim(box, par, 0.0, 2.4, 0.0, 0.0, 0.0, 0.0, 5.2, .4, .4)
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
        // Shadow casters beyond the view. The light shines from ahead, so a tree 60 m away still throws its shadow (and a
        // dark gap in the beams) over the ground near the runner. Culling such trees with the visible set made that shadow
        // pop into the beams the moment the tree came into view. They are added here for the shadow map only.
        val vis = counts.copyOf()
        val lx = moonDirV[0].toDouble(); val lz = moonDirV[2].toDouble()
        val ll = hypot(lx, lz).coerceAtLeast(1e-6)
        val extraReach = SHADOW_FADE_END + 4.0   // beyond this the casters have faded away completely
        val sideMax = feat.shadowExtent + 8.0   // beyond this sideways (crown radius included) a tree cannot touch the shadow map
        for (pass in 0..1) {
            // Pass 0: the playable window (minus what is already in the visible set). Pass 1: the shadow ring, never drawn otherwise.
            for (p in (if (pass == 0) props else shadowRing)) {
                val dx = p.x - cam.ex; val dz = p.z - cam.ez
                val dist = hypot(dx, dz)
                if (pass == 0 && dist <= reach && (dx * fwx + dz * fwz) / fl >= -12) continue   // already in the visible set
                if (dist > extraReach || (pass == 0 && s.cleared.contains(p.id))) continue
                if ((dx * lx + dz * lz) / (ll * dist.coerceAtLeast(1e-6)) < .2) continue   // only trees on the light's side matter
                if (abs(-dx * lz + dz * lx) / ll > sideMax) continue
                for (i in p.kinds.indices) {
                    val kd = p.kinds[i]
                    if (kd == K_SHADOW || kd == K_PLANT || kd == K_ROCK || kd == K_CROWN) continue   // crowns: see the sun-gap field
                    emit(kd, p.m, i * 16)
                }
            }
        }
        System.arraycopy(counts, 0, shadowCounts, 0, KINDS)
        System.arraycopy(vis, 0, counts, 0, KINDS)
    }

    private fun smoothRange(x: Double, a: Double, b: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    // ---- sprites ----
    private fun buildSprites(s: Simulation, e: GameEngine) {
        for (b in arrayOf(bGlow, bGlowFog, bMist, bSpore, bHole)) if (b !== bHole) b.clear()
        // bHole also carries the relics' glow queued earlier in this frame, so it is cleared after drawing.
        val t = s.elapsed
        val ember = surviveStage(s) == SurviveStage.RED
        val haloDay = 1.0 - dayLevel * .25     // the lights' halos stay visible by day
        // Distant forest lights: green, purple and lavender glows that stutter out one by one in survive mode.
        val cols = arrayOf(Palette.green, Palette.purple, Palette.lavender, Palette.purple, Palette.lavender)
        for (light in forestLightsFor(s.mode, s.x, s.z)) {
            val lx = light.x; val lz = light.z; val lg = groundHeight(lx, lz) + 4.2
            for (i in 0 until 5) {
                val own = min(1.0, max(0.0, e.dieRef * 1.6 - i * .15))
                // The stutter-out keeps its rhythm, but each beat eases on and off instead of cutting.
                val flick = if (own > 0 && own < 1) .1 + .9 * smoothRange(sin(s.elapsed * (26 + i * 7)), -.1, .45) else 1.0
                val high = i > 2
                val size = (if (high) 30.0 else if (i == 0) 24.0 else 20.0) * (1 + sin(t * .4 + i) * .08)
                val px = if (high) lx + (if (i == 3) -3.5 else 3.5) + sin(t * .2 + i) * 2 else lx + (if (i == 0) -5.0 else if (i == 1) 5.0 else 0.0) + sin(t * .16 + i) * 1.5
                val py = if (high) lg + 13.5 + sin(t * .24 + i) * 1.4 else lg
                val far = if (s.mode == GameMode.FREEROAM) farFade(lx, lz, s, 60.0, 100.0) else 1.0
                bGlow.add(px, py, lz, size, size, cols[i], (if (high) .14 else .66) * (1 - own) * flick * haloDay * far, py - groundHeight(px, lz))
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
            // Only a slight growth with distance; the soft profile already keeps far motes visible.
            val distanceScale = 1.0 + min(dist, 60.0) * .008
            // Motes right at the camera would turn into large discs: let them fade out.
            val nearFade = smoothRange(dist, 1.5, 5.0)
            val size = (.14 + hash(i, 4, 9) * .12) * (if (ember) 1.25 else 1.0) * 3.0 * distanceScale
            val atmosphericAlpha = sporeOpacity(dist, tw) * (if (ember) 1.25 else 1.0) * .42
            bSpore.add(mx, groundHeight(mx, mz) + sp.h + sin(t * .5 + seed) * .25, mz, size, size, col, min(.42, atmosphericAlpha) * nearFade * (1.0 - dayLevel * .2))
        }
        if (orbSeen.size > 24) orbSeen.keys.retainAll(e.orbs.map { it.p.id }.toSet())
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
            bGlowFog.add(px, py, pz, size, size, col, a * (1.0 - dayLevel * .12) * orbIn(o, t), py - groundHeight(px, pz))
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
                    SecretType.MONOLITH -> bGlowFog.add(sec.x, gyS + 2.4, sec.z, 5.0, 7.0, Palette.secret, min(1.0, .35 * pulse) * (1.0 - dayLevel * .12) * farFade(sec.x, sec.z, s, 45.0, 75.0), 2.4)
                    SecretType.RING -> for (m in 0 until 11) {
                        val a = m / 11.0 * PI * 2 + t * .25
                        bGlowFog.add(sec.x + cos(a) * 2.6, gyS + .3, sec.z + sin(a) * 2.6, .9, .9, Palette.green, min(1.0, .7 * pulse) * (1.0 - dayLevel * .12) * farFade(sec.x, sec.z, s, 45.0, 75.0), .3)
                    }
                    SecretType.LANTERN -> bGlowFog.add(sec.x + .06, gyS + 1.7, sec.z, 3.2, 3.2, Palette.lamp, min(1.0, .75 * pulse * (.85 + sin(t * 9 + i) * .15)) * (1.0 - dayLevel * .12) * farFade(sec.x, sec.z, s, 45.0, 75.0), 1.7)
                }
            }
            for (g in e.gates) bGlow.add(g.x, groundHeight(g.x, g.z) + 3, g.z, 16.0, 16.0, Palette.ember, .32 * haloDay * farFade(g.x, g.z, s, 50.0, 90.0), 3.0)
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
        val e = engine
        if (b.count > 0) {
            GL.glUseProgram(spriteProg)
            GL.glUniformMatrix4fv(loc(spriteProg, "uVP"), 1, false, vp, 0)
            // Camera right/up are the first two rows of the view rotation.
            GL.glUniform3f(loc(spriteProg, "uRight"), view[0], view[4], view[8])
            GL.glUniform3f(loc(spriteProg, "uUp"), view[1], view[5], view[9])
            GL.glUniform1i(loc(spriteProg, "uTex"), 0)
            GL.glUniform3f(loc(spriteProg, "uCam"), e.camera.ex.toFloat(), e.camera.ey.toFloat(), e.camera.ez.toFloat())
            GL.glUniform3f(loc(spriteProg, "uFwd"), camFwdX, camFwdY, camFwdZ)
            GL.glUniform3f(loc(spriteProg, "uFog"), fog[0], fog[1], fog[2])
            GL.glUniform1f(loc(spriteProg, "uFogNear"), fogNear); GL.glUniform1f(loc(spriteProg, "uFogFar"), fogFar)
            GL.glUniform1f(loc(spriteProg, "uFogOn"), if (b.fog) 1f else 0f); GL.glUniform1f(loc(spriteProg, "uEncode"), encode)
            GL.glEnable(GL.GL_BLEND)
            if (b.additive) GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE) else GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
            GL.glDepthMask(false)
            GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, b.texture())
            b.data.position(0).limit(b.count * 10)
            GL.glBindVertexArray(spriteVao); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, spriteVbo)
            GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, b.count * 40, b.data)
            GL.glDrawArraysInstanced(GL.GL_TRIANGLE_STRIP, 0, 4, b.count)
            GL.glBindVertexArray(0)
            b.data.limit(b.data.capacity())
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        if (b === bHole) b.clear()
    }

    /**
     * Dynamic resolution: starts at the web build's 1.5x density and climbs towards the surface's own density while
     * the device keeps 60 fps with room to spare; drops again (and remembers the failing level for a while) when it
     * cannot. Only the effect pass's target is resized, never the surface.
     */
    private inner class Scaler {
        var density = minOf(START_DENSITY, surfaceDensity)
        private val minDensity = minOf(MIN_DENSITY, surfaceDensity)
        private var avg = 1.0 / 60
        private var slowFor = 0.0
        private var fastFor = 0.0
        private var cooldown = 6.0
        private var ceiling = surfaceDensity
        private var ceilingHold = 0.0

        /** @return true when [density] changed. */
        fun tick(dt: Double): Boolean {
            val d = dt.coerceIn(0.002, 0.25)
            avg += (d - avg) * 0.05
            cooldown = max(0.0, cooldown - d)
            if (ceilingHold > 0) { ceilingHold -= d; if (ceilingHold <= 0) ceiling = surfaceDensity }
            val budget = 1.0 / 60
            if (avg > budget * 1.38) { slowFor += d; fastFor = 0.0 }
            else if (avg < budget * 1.12) { fastFor += d; slowFor = 0.0 }
            else { slowFor = 0.0; fastFor = 0.0 }
            if (slowFor > 1.5 && density > minDensity + 0.01f) {
                ceiling = max(minDensity, density - 0.01f); ceilingHold = 90.0
                density = max(minDensity, density - 0.125f)
                slowFor = 0.0; cooldown = 10.0; avg = budget
                return true
            }
            val cap = minOf(surfaceDensity, ceiling)
            if (fastFor > 5.0 && cooldown <= 0.0 && density < cap - 0.01f) {
                density = minOf(cap, density + 0.125f)
                fastFor = 0.0; cooldown = 3.0
                return true
            }
            return false
        }
    }
}
