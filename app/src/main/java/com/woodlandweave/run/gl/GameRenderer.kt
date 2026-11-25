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
 * headlamp, glow sprites, black holes and the full-screen effect pass.
 */
class GameRenderer(private val context: Context, private val engine: GameEngine) : GLSurfaceView.Renderer {
    private companion object {
        const val CROWN_NEAR_SUBDIVISIONS = 2     // same canopy detail as the web build, close to the runner
        const val CROWN_FAR_SUBDIVISIONS = 2      // match the Capacitor canopy mesh at every distance; avoid a visible LOD silhouette seam
        const val CROWN_LOD_DISTANCE = 0.0       // always the coarse crown (its normals are the fine crown's, so the shading stays smooth)
        // The shadow casters come from a wider lattice than the playable forest (see rebuildProps), so the light on the
        // ground depends on the real forest and not on the part of it that happens to be loaded around the runner.
        const val SHADOW_RADIUS = 22
        const val MAX_PROPS = (SHADOW_RADIUS * 2 + 1) * (SHADOW_RADIUS * 2 + 1)
        const val GROUND_STEP = 200f / 112f
        const val K_TRUNK = 0; const val K_SHADOW = 1; const val K_ROOT = 2; const val K_BRANCH = 3
        const val K_CROWN = 4; const val K_ROCK = 5; const val K_PLANT = 6; const val K_CROWN_FAR = 7
        const val K_TRUNK_1 = 8; const val K_TRUNK_2 = 9; const val K_TRUNK_RESERVED = 10
        const val K_CROWN_1 = 11; const val K_CROWN_2 = 12; const val K_CROWN_RESERVED = 13
        const val K_CROWN_FAR_1 = 14; const val K_CROWN_FAR_2 = 15; const val K_CROWN_FAR_RESERVED = 16
        // Every species has SHAPES different tree bodies (sway, roots, limbs); shape 0 is the original batch of the species,
        // the others have their own batches after the base kinds. A tree picks its shape from its position.
        const val SHAPES = 4
        const val KINDS = 17 + 3 * (SHAPES - 1)
        val TRUNK_KINDS = intArrayOf(K_TRUNK, K_TRUNK_1, K_TRUNK_2)
        /** Batch of body shape [shape] of species [species]. */
        fun bodyKind(species: Int, shape: Int) = if (shape == 0) TRUNK_KINDS[species] else 17 + species * (SHAPES - 1) + shape - 1
        val BODY_KINDS = IntArray(3 * SHAPES) { bodyKind(it / SHAPES, it % SHAPES) }
        val CROWN_KINDS = intArrayOf(K_CROWN, K_CROWN_1, K_CROWN_2)
        val CROWN_FAR_KINDS = intArrayOf(K_CROWN_FAR, K_CROWN_FAR_1, K_CROWN_FAR_2)
        const val TREE_PARTS = 3
        const val WINDOW = 100.0
        const val SHEET_N = 11
        const val RING_N = 56
        val WORM_COUNTS = intArrayOf(260, 3, 5)
        const val FOV = 72f
        /** The web build's crowns, rocks and bushes were flat-shaded polyhedra (three.js non-indexed geometry). */
        const val FLAT_FACETS = true
        /** The one resolution: the scene is drawn at half the surface's width and height and scaled up in the final pass. */
        const val RENDER_SCALE = 0.5f
        /** Adaptive: starts at the existing half-resolution target and scales down only when frame time requires it. */
        const val ADAPTIVE_SCALE = true
        // Sharp by default: start close to the device's own resolution and only give ground when 60 fps is lost.
        const val MIN_DENSITY = 1.0f
        const val START_DENSITY = 1.5f
        const val TREE_SWAY = .15f
        /**
         * The shadow map is redrawn every N frames (1 = every frame). 2 halves the shadow pass but makes the frame time alternate
         * between a heavy and a light frame, which reads as judder on a slow device; every frame is steadier.
         */
        const val SHADOW_EVERY = 1
        private val TREE_GIRTH_R = doubleArrayOf(.8, .9, 1.15)
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
    private var glowLow = 0; private var glowSmooth = 0
    private var glowTex = 0   // only the contact-shadow decals use this one now
    private var holeGlowTex = 0; private var warpSporeTex = 0; private var ringTex = 0   // the black hole's own textures
    private var wormBase = FloatArray(0)
    // Every sprite element owns its texture (see Visuals): changing one never changes another.
    private var orbHaloTex = 0; private var forestLightTex = 0; private var pollenGlowTex = 0; private var pollenCoreTex = 0; private var mistTex = 0
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
    // Local-light selection scratch (top-K by distance, no per-frame allocation).
    private val litIdx = IntArray(4); private val litD2 = DoubleArray(4); private val litDist = DoubleArray(4)
    // Wall-clock hours, refreshed once a second (the value only has minute resolution anyway).
    private var clockStamp = 0L; private var clockValue = 0.0
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
    // One batch, one texture and one block of numbers (Visuals) per element. `fog` mirrors three.js's SpriteMaterial.fog of the web build.
    private val bForestLights = Sprites(200, { forestLightTex }, true, false)
    private val bPollenGlow = Sprites(32, { pollenGlowTex }, true, false)
    private val bPollenCore = Sprites(32, { pollenCoreTex }, false, true)
    private val bMist = Sprites(8, { mistTex }, false, true)
    private val bOrbHalo = Sprites(16, { orbHaloTex }, true, true)
    private val bHoleGlow = Sprites(64, { holeGlowTex }, true, false)          // a black hole's halo and sparks
    private val bWarpSpores = Sprites(1600, { warpSporeTex }, true, false)     // the dots of the warp
    // One array for the whole run: the per-frame arrayOf() it replaces was a small allocation every frame.
    private val spriteBatches = arrayOf(bMist, bPollenCore, bForestLights, bPollenGlow, bOrbHalo, bHoleGlow, bWarpSpores)
    // The key-light shadow map is re-rendered every SHADOW_EVERY-th frame (the light is fixed and the map snaps to whole texels, so a
    // one-frame-old map is still exactly consistent with itself); the first frame after it (re)appears is always rendered.
    private var shadowTick = 0
    private var shadowValid = false

    private val bufs = Array(KINDS) {
        GlUtil.floatBuffer(MAX_PROPS * 2 * 16)
    }
    private val counts = IntArray(KINDS)
    private val visCounts = IntArray(KINDS)
    // Instances drawn into the shadow map: the visible ones plus trees beyond the view whose long shadows reach into it.
    private val shadowCounts = IntArray(KINDS)
    // Shadow casters shrink to nothing between these distances from the runner, only at the very rim of the shadow lattice
    // (SHADOW_RADIUS * CELL = 132 m, about 120 m is inside it in every direction): thinning trees at the rim, not popping trees.
    // Beyond SHADOW_FADE_END they are not drawn into the shadow map at all. The rim must lie beyond what the runner can see
    // (fog, about 45 m) PLUS the longest shadow (a crown 22 m up at 23 degrees throws it about 50 m), so that every visible
    // patch of ground has all the trees that can shade it. Then a pool of light is a fact of the forest, not of the loaded part.
    private val SHADOW_FADE_START = 100f
    private var shadowTreeBodies: Array<Mesh?> = arrayOfNulls(3 * SHAPES)
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
            Mesh(Geo.treeBody(false, variant = 0), MAX_PROPS), Mesh(Geo.flatQuad(), MAX_PROPS * 2),
            Mesh(Geo.flatQuad(), 1), Mesh(Geo.flatQuad(), 1),
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, variant = 0), MAX_PROPS),
            Mesh(Geo.icosphere(0), 1), Mesh(Geo.jittered(1, .35f, .4f, 47, FLAT_FACETS), MAX_PROPS),
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, CROWN_FAR_SUBDIVISIONS, 0), MAX_PROPS),
            Mesh(Geo.treeBody(false, variant = 1), MAX_PROPS), Mesh(Geo.treeBody(false, variant = 2), MAX_PROPS),
            Mesh(Geo.flatQuad(), 1), // reserved mesh slot
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, variant = 1), MAX_PROPS),
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, variant = 2), MAX_PROPS),
            Mesh(Geo.flatQuad(), 1), // reserved mesh slot
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, CROWN_FAR_SUBDIVISIONS, 1), MAX_PROPS),
            Mesh(Geo.treeCanopy(CROWN_NEAR_SUBDIVISIONS, CROWN_FAR_SUBDIVISIONS, 2), MAX_PROPS),
            Mesh(Geo.flatQuad(), 1), // reserved mesh slot
        ) + Array(3 * (SHAPES - 1)) { Mesh(Geo.treeBody(false, variant = it / (SHAPES - 1), shape = it % (SHAPES - 1) + 1), MAX_PROPS) }
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

        // One compact three-species atlas: the same three texture units and one material batch as before.
        // Each species occupies a padded 256x512 tile; no per-tree texture binds or extra draw calls.
        barkTex = GlUtil.assetTexture(context, "tree-bark-atlas.jpg", srgb = true)
        barkN = GlUtil.assetTexture(context, "tree-bark-normal-atlas.jpg")
        barkR = GlUtil.assetTexture(context, "tree-bark-rough-atlas.jpg")
        floorTex = GlUtil.assetTexture(context, "wet-ground.jpg", srgb = true); floorN = GlUtil.assetTexture(context, "ground-normal.jpg"); floorR = GlUtil.assetTexture(context, "ground-rough.jpg")
        glowLow = GlUtil.glowTexture(floatArrayOf(0f, .2f, 1f), intArrayOf(255, 153, 0))
        // Medium and High: the same glows with smooth profiles (no visible kink where the gradient changes slope).
        glowSmooth = GlUtil.smoothGlowTexture { r ->
            val s = r * r * (3f - 2f * r)
            .62f * (1f - s).toDouble().pow(.9).toFloat() + .38f * kotlin.math.exp(-(r / .12f) * (r / .12f))
        }
        glowTex = glowLow
        orbHaloTex = 0; forestLightTex = 0; pollenGlowTex = 0; pollenCoreTex = 0; mistTex = 0   // the old context's ids are gone
        rebuildSpriteTextures(false)
        holeGlowTex = GlUtil.glowTexture(floatArrayOf(0f, .35f, 1f), intArrayOf(255, 89, 0))
        warpSporeTex = GlUtil.glowTexture(floatArrayOf(0f, .35f, 1f), intArrayOf(255, 89, 0))
        ringTex = GlUtil.ringTexture()

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
        builtVersion = -1; cache.clear(); lastNs = 0L
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
        for (t in treesAround(cx, cz, levelOf(s.difficulty).treeGap, s.universe, SHADOW_RADIUS, skipInner = RADIUS)) {
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
            val species = min(2, floor(hash(floor(t.x).toInt(), floor(t.z).toInt(), 200) * 3).toInt())
            val girth = TREE_GIRTH_R[species] * t.scale
            treeX[i] = t.x.toFloat(); treeZ[i] = t.z.toFloat(); treeR[i] = (.42 * girth + .08).toFloat()
        }
    }

    private fun treeProp(tree: Tree): Prop {
        // One body instance (trunk + roots + branches), one canopy instance and one contact-shadow instance.
        // Three deterministic species batches share geometry; only these three transforms are streamed per tree.
        val m = FloatArray(TREE_PARTS * 16)
        val seedX = floor(tree.x).toInt(); val seedZ = floor(tree.z).toInt()
        fun h(salt: Int) = hash(seedX, seedZ, salt)
        // The unified body keeps the original -1.1 m trunk anchor and unscaled height;
        // its local root flare starts just above grade and sinks naturally into the soil.
        // Keep the original species-independent trunk height: tree.scale changes girth and crown size only.
        // The unified body's root flare starts at local Y~1.2, so the original -1.1 ground anchor keeps it seated.
        // The ground under the tree is sampled once (this used to be done twice). The slope there decides how far the whole
        // tree sinks: the root fins stay at one height while the soil falls away on the downhill side, so their tips used
        // to hang in the air as sharp spikes. Sinking by slope x root reach seats the downhill tips in the soil; the uphill
        // side just buries a little more of the flare. Two forward differences, once per tree (the props are cached).
        val gy = groundHeight(tree.x, tree.z)
        val slopeX = (groundHeight(tree.x + .5, tree.z) - gy) / .5
        val slopeZ = (groundHeight(tree.x, tree.z + .5) - gy) / .5
        val sink = min(.5, hypot(slopeX, slopeZ) * 1.5 * tree.scale)
        val base = gy - 1.1 - sink
        val species = floor(h(200) * 3).toInt().coerceIn(0, 2)
        val shape = floor(h(300) * SHAPES).toInt().coerceIn(0, SHAPES - 1)
        val girth = TREE_GIRTH_R[species] * tree.scale
        val leanAmt = if (species == 2) .03 else .015
        val leanDir = h(201) * PI * 2
        val lx = cos(leanDir) * leanAmt
        val lz = sin(leanDir) * leanAmt
        val yaw = h(5) * 6
        // No two trees are scaled alike: the trunk and roots are oval (random width and depth, turned by the yaw), a little taller or shorter.
        Mat.compose(m, 0, tree.x, base, tree.z, lx, yaw, lz, Mat.XYZ,
            girth * (.86 + h(310) * .30), .94 + h(312) * .14, girth * (.86 + h(311) * .30))
        Mat.compose(m, 16, tree.x, gy + .05, tree.z,
            0.0, 0.0, 0.0, Mat.XYZ, 3.4 * tree.scale, 1.0, 3.4 * tree.scale)
        val bodyXZ = tree.scale * (.88 + h(313) * .26)
        Mat.compose(m, 32, tree.x, base, tree.z, lx, yaw, lz, Mat.XYZ,
            bodyXZ, tree.scale * (.86 + h(314) * .30), bodyXZ)
        return Prop(tree.id, tree.x, tree.z, intArrayOf(bodyKind(species, shape), K_SHADOW, CROWN_KINDS[species]), m)
    }

    private fun obstacleProp(o: Obstacle): Prop {
        // Stone obstacles have been removed from world generation. Keep this guard for old or
        // externally supplied simulation snapshots so they cannot allocate or draw rock geometry.
        if (o.big) return Prop(o.id, o.x, o.z, intArrayOf(), FloatArray(0))
        val fx = floor(o.x).toInt(); val fz = floor(o.z).toInt()
        val r = .7
        val gy = minOf(groundHeight(o.x, o.z), groundHeight(o.x + r, o.z), groundHeight(o.x - r, o.z), groundHeight(o.x, o.z + r), groundHeight(o.x, o.z - r))
        val spin = hash(fx, fz, 80) * 6
        val bs = .85 + hash(fx, fz, 83) * .55
        val m = FloatArray(16)
        Mat.compose(m, 0, o.x, gy - .05, o.z, 0.0, spin, 0.0, Mat.XYZ, .8 * bs, (.4 + hash(fx, fz, 81) * .35) * bs, .8 * bs)
        return Prop(o.id, o.x, o.z, intArrayOf(K_PLANT), m)
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
        val hours = clockHours()
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
            } else {
                // A tier without a shadow map still gets the world-fixed canopy light (pools, dim under the foliage): the
                // shader's own gate is uShadowOn. High with its shadows slider at zero keeps everything off, as before.
                u1(p, "uShadowOn", if (feat.shadows) 0f else 1f); u1(p, "uShadowK", min(1f, pp.tune.shadows))
            }
            u3(p, "uFog", fog); u1(p, "uFogNear", fogNear); u1(p, "uFogFar", fogFar)
            // Trees are culled beyond FOG_END + 8: they thin out over the last 14 m before that, inside the fully fogged band.
            GL.glUniform2f(loc(p, "uTreeFade"), (FOG_END + 8 - 14).toFloat(), (FOG_END + 8).toFloat())
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
        if (!shadowActive) shadowValid = false
        val shadowDue = shadowActive && (!shadowValid || (++shadowTick % SHADOW_EVERY) == 0)
        fillInstances(s, cam, shadowDue)
        val smap = shadow
        if (smap != null && shadowActive) {
            if (shadowDue) {
                shadowPass(smap, cam)
                shadowValid = true
                post.bind()
                GL.glEnable(GL.GL_DEPTH_TEST)
            }
            System.arraycopy(smap.vp[0], 0, pp.lightVp0, 0, 16)
            pp.shadowTex0 = smap.tex[0]
        } else pp.shadowTex0 = 0
        scene(litProg)
        val g = e.ghostK.toFloat()
        bindMaps(barkTex, barkN, barkR)
        if (feat.wind) setSway(litProg, TREE_SWAY, 0f, 0f)
        // The merged trunk/roots/limbs are closed outward-facing tubes. Cull their back faces in
        // both normal and ghost rendering so translucent power-ups cannot reveal hidden limb backs.
        GL.glEnable(GL.GL_CULL_FACE)
        GL.glCullFace(GL.GL_BACK)
        if (g > 0f) {
            // Pass 1: establish the nearest merged tree-body surface before the ghost fade.
            GL.glColorMask(false, false, false, false)
            GL.glDepthMask(true)
            GL.glDisable(GL.GL_BLEND)
            material(litProg, Palette.trunk, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            BODY_KINDS.forEach { kind -> meshes[kind].draw(bufs[kind], counts[kind]) }
            GL.glColorMask(true, true, true, true)

            // Pass 2: shade only the nearest surfaces. Keep depth testing enabled but
            // freeze the prepass depth so transparent batches cannot mask one another.
            GL.glEnable(GL.GL_BLEND)
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
            GL.glDepthFunc(GL.GL_LEQUAL)
            GL.glDepthMask(false)
            material(litProg, Palette.trunk, Palette.ghostGlow, g * .55f, 1f - g * .34f, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            BODY_KINDS.forEach { kind -> meshes[kind].draw(bufs[kind], counts[kind]) }
            GL.glDepthMask(true)
            GL.glDepthFunc(GL.GL_LESS)
            GL.glDisable(GL.GL_BLEND)
        } else {
            material(litProg, Palette.trunk, mode = 4, rough = .8f, maps = true, ns = .75f, uvx = 1.5f, uvy = 7f)
            BODY_KINDS.forEach { kind -> meshes[kind].draw(bufs[kind], counts[kind]) }
        }
        // Joined canopy lobes are closed surfaces; back-face culling avoids drawing their hidden sides.
        GL.glEnable(GL.GL_CULL_FACE); GL.glCullFace(GL.GL_BACK)
        material(litProg, Palette.crown, mode = 5, rough = .9f)
        CROWN_KINDS.forEach { kind -> meshes[kind].draw(bufs[kind], counts[kind]) }
        CROWN_FAR_KINDS.forEach { kind -> meshes[kind].draw(bufs[kind], counts[kind]) }
        if (feat.wind) setSway(litProg, 0f, 0f, 0f)    // rocks and bushes stay put
        material(litProg, Palette.plant, mode = 6, rough = .9f)
        meshes[K_PLANT].draw(bufs[K_PLANT], counts[K_PLANT])
        GL.glDisable(GL.GL_CULL_FACE)

        drawLandmarks(s, e)
        drawBlackHoles(s)
        drawWarpSpores(s)

        // Contact shadows: soft dark decals just above the ground (High has real shadows instead).
        if (!feat.shadows) {
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            bindMaps(glowTex, glowTex, glowTex)
            material(litProg, floatArrayOf(0f, 0f, 0f), alpha = .6f, mode = 1)
            meshes[K_SHADOW].draw(bufs[K_SHADOW], counts[K_SHADOW])
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }

        buildSprites(s, e)
        for (b in spriteBatches) drawSprites(b)

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
        // Nearest `lightCount` orbs within 23 m, ascending by squared distance (ties keep list order, as the old stable sort did).
        var picked = 0
        val orbList = e.orbs
        for (oi in 0 until orbList.size) {
            if (orbList[oi].taken != null) continue   // a picked-up power-up gives no light
            val op = orbList[oi].p
            val dx = op.x - cam.ex
            val dz = op.z - cam.ez
            val d2 = dx * dx + dz * dz
            val dist = sqrt(dx * dx + dz * dz)
            if (dist >= 23.0) continue
            if (picked == lightCount && d2 >= litD2[picked - 1]) continue
            var k = if (picked < lightCount) picked++ else picked - 1
            while (k > 0 && litD2[k - 1] > d2) { litD2[k] = litD2[k - 1]; litIdx[k] = litIdx[k - 1]; litDist[k] = litDist[k - 1]; k-- }
            litD2[k] = d2; litIdx[k] = oi; litDist[k] = dist
        }
        for (index in 0 until picked) {
            val orbState = orbList[litIdx[index]]
            val px = orbState.p.x
            val pz = orbState.p.z
            val py = groundHeight(px, pz) + 1.15
            val distanceFade = (1.0 - (litDist[index] / 23.0)).coerceIn(0.0, 1.0).toFloat()
            val offset = index * 4
            localLightPositions[offset] = px.toFloat()
            localLightPositions[offset + 1] = py.toFloat()
            localLightPositions[offset + 2] = pz.toFloat()
            localLightPositions[offset + 3] = baseIntensity * distanceFade   // steady: no pulse, no fade-in
            val tint = orbColor(orbState.p.type)
            localLightColors[offset] = tint[0]; localLightColors[offset + 1] = tint[1]; localLightColors[offset + 2] = tint[2]
            localLightColors[offset + 3] = lightRadius
            pickOccluders(index, px, pz, cam.ex, cam.ez)
        }
        pickOccluders(4, e.lampX, e.lampZ, cam.ex, cam.ez)
    }

    private fun clockHours(): Double {
        val now = System.currentTimeMillis()
        if (now - clockStamp >= 1000L || now < clockStamp) {
            clockStamp = now
            clockValue = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60.0 }
        }
        return clockValue
    }

    private fun farFade(x: Double, z: Double, s: Simulation, a: Double, b: Double) = 1.0 - smoothRange(hypot(x - s.x, z - s.z), a, b)

    // ---- graphics tiers ----
    private fun setSway(p: Int, a: Float, b: Float, c: Float) = GL.glUniform3f(loc(p, "uSway"), a, b, c)

    /** Switches tier: effect chain, shaders, shadow map, round shapes. Anything that fails drops one tier instead of crashing. */
    /** A fresh glow texture of the tier's profile. Each sprite element gets its own copy so they can be changed independently. */
    private fun makeGlowTexture(smooth: Boolean): Int =
        if (smooth) GlUtil.smoothGlowTexture { r ->
            val s = r * r * (3f - 2f * r)
            .62f * (1f - s).toDouble().pow(.9).toFloat() + .38f * kotlin.math.exp(-(r / .12f) * (r / .12f))
        } else GlUtil.glowTexture(floatArrayOf(0f, .2f, 1f), intArrayOf(255, 153, 0))

    private fun rebuildSpriteTextures(smooth: Boolean) {
        val old = intArrayOf(orbHaloTex, forestLightTex, pollenGlowTex, pollenCoreTex, mistTex).filter { it != 0 }.toIntArray()
        if (old.isNotEmpty()) GL.glDeleteTextures(old.size, old, 0)
        orbHaloTex = makeGlowTexture(smooth); forestLightTex = makeGlowTexture(smooth)
        pollenGlowTex = makeGlowTexture(smooth); pollenCoreTex = makeGlowTexture(smooth); mistTex = makeGlowTexture(smooth)
    }

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
                shadowValid = false   // a fresh (empty) shadow map must be drawn before it is used
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
        rebuildSpriteTextures(feat.smoothMeshes)
        appliedQuality = q
        // A tier the device could not reach must not be retried every frame.
        if (feat.quality.ordinal < q.ordinal) qualityCap = feat.quality
    }

    /** Low keeps the faceted web look; Medium and up get smooth, finely tessellated round shapes and shapely trees. */
    private fun rebuildTierMeshes(f: Features) {
        val flat = !f.smoothMeshes
        val old = (BODY_KINDS + CROWN_KINDS + CROWN_FAR_KINDS + intArrayOf(K_PLANT, K_ROOT, K_BRANCH)).distinct().map { meshes[it] }
        val nearDetail = if (flat) CROWN_NEAR_SUBDIVISIONS else f.crownNearDetail
        val farDetail = if (flat) CROWN_FAR_SUBDIVISIONS else f.crownFarDetail
        for (variant in 0..2) {
            for (shape in 0 until SHAPES) meshes[bodyKind(variant, shape)] = Mesh(Geo.treeBody(f.lifelikeTrees, variant = variant, shape = shape), MAX_PROPS)
            meshes[CROWN_KINDS[variant]] = Mesh(Geo.treeCanopy(nearDetail, variant = variant), MAX_PROPS)
            meshes[CROWN_FAR_KINDS[variant]] = Mesh(Geo.treeCanopy(nearDetail, farDetail, variant), MAX_PROPS)
        }
        meshes[K_PLANT] = Mesh(Geo.jittered(1, .35f, .4f, 47, flat), MAX_PROPS)
        meshes[K_ROOT] = Mesh(Geo.flatQuad(), 1)
        meshes[K_BRANCH] = Mesh(Geo.flatQuad(), 1)
        shadowTreeBodies.forEach { it?.release() }
        shadowTreeBodies = Array(3 * SHAPES) { i -> if (f.shadows) Mesh(Geo.treeBody(true, shadow = true, variant = i / SHAPES, shape = i % SHAPES), MAX_PROPS) else null }
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
        for (i in BODY_KINDS.indices) {
            val kind = BODY_KINDS[i]
            shadowTreeBodies[i]?.draw(bufs[kind], shadowCounts[kind])
        }
        draw(K_PLANT, 0f)
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

    /**
     * The power-up glass spheres (still, translucent, nothing inside them) and the gate ring. Everything about them is in Visuals.
     * Secrets (monolith, ring, lantern) and the gate frames are not drawn for now.
     */
    private fun drawLandmarks(s: Simulation, e: GameEngine) {
        if (Visuals.OrbSphere.ON) for (o in e.orbs) {
            if (o.taken != null) continue
            val col = orbColor(o.p.type)
            val py = groundHeight(o.p.x, o.p.z) + Visuals.OrbSphere.HEIGHT
            // A translucent glass-like sphere: alpha blending, no depth write, front faces only.
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            GL.glEnable(GL.GL_CULL_FACE); GL.glCullFace(GL.GL_BACK)
            material(litProg, col, col, Visuals.OrbSphere.EMISSIVE, alpha = Visuals.OrbSphere.ALPHA, rough = Visuals.OrbSphere.ROUGH)
            place(parent, o.p.x, py, o.p.z, 0.0, 0.0, 0.0, Visuals.OrbSphere.SIZE)
            uploadOne(orb, parent)
            GL.glDisable(GL.GL_CULL_FACE); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        // The gates exist (and work) in Freeroam; each one is shown as a plain ring.
        if (!Visuals.GateRing.ON || s.mode != GameMode.FREEROAM) return
        material(litProg, Palette.ember, mode = 2)
        for (g in e.gates) {
            place(parent, g.x, groundHeight(g.x, g.z), g.z, 0.0, g.angle, 0.0)
            prim(torusA, parent, 0.0, GATE_HALF, 0.0, PI / 2, 0.0, 0.0, 1.0, 1.0, 1.0)   // torusA's radius is GATE_HALF
        }
    }

    /** The black holes: a plain black body and a tilted accretion veil (three faint glows turning slowly), exactly as before. */
    private fun drawBlackHoles(s: Simulation) {
        if (s.mode != GameMode.FREEROAM || s.warp >= 0) return
        val t = s.elapsed
        var i = 0
        for (h in holesAround(s.x, s.z, s.universe)) {
            if (hypot(h.x - s.x, h.z - s.z) >= HOLE_SEE) continue
            if (i >= Visuals.BlackHole.MAX_VISIBLE) break
            val y = groundHeight(h.x, h.z) + HOLE_HEIGHT + sin(t * .6 + i) * Visuals.BlackHole.BOB
            if (Visuals.BlackHole.BODY) {
                // The body: pure black and untouched by fog, so it reads from far away.
                material(litProg, floatArrayOf(0f, 0f, 0f), mode = 2, fogOn = 0f)
                place(parent, h.x, y, h.z, 0.0, 0.0, 0.0, HOLE_RADIUS)
                uploadOne(holeBody, parent)
            }
            if (Visuals.BlackHole.VEILS) {
                // A tilted accretion veil: three faint, soft glows turning slowly.
                Mat.compose(parent, 0, h.x, y, h.z, 1.2 + sin(t * .2 + i) * .12, 0.0, .35 + sin(t * .15 + i * 2) * .1, Mat.XYZ, 1.0, 1.0, 1.0)
                val rings = arrayOf(Triple(10.0, Palette.lavender, .16f), Triple(13.0, Palette.purple, .11f), Triple(17.0, Palette.secret, .07f))
                for ((r, ring) in rings.withIndex()) {
                    val spin = t * (.9 - r * .28) * (if (r % 2 == 1) -1 else 1)
                    additiveVeil(ring.second, ring.third, ringTex, parent, ring.first, ring.first, rz = spin, fogOn = 0f)
                }
            }
            i++
        }
    }

    /** The warp's dots (the relics of the wormhole are gone; the dots stay, as before). */
    private fun drawWarpSpores(s: Simulation) {
        if (!Visuals.BlackHole.WARP_SPORES) return
        val w = warpEnvelope(s)
        if (w <= .002) return
        if (wormBase.isEmpty()) wormBase = paintSpores(WORM_COUNTS, 0)
        emitSpores(WORM_COUNTS, wormBase, s, s.elapsed, w, 0, .85) { _, _ -> 1.0 }
    }

    /** Euler rotation (z, then y, then x) with the sines and cosines supplied, so a whole sheet or ring computes them once. */
    private fun eulerApplyCs(v: DoubleArray, cx: Double, sx: Double, cy: Double, sy: Double, cz: Double, sz: Double) {
        var x = v[0]; var y = v[1]; var z = v[2]
        var nx = x * cz - y * sz; var ny = x * sz + y * cz; x = nx; y = ny
        nx = x * cy + z * sy; val nz = -x * sy + z * cy; x = nx; z = nz
        ny = y * cx - z * sx; val nz2 = y * sx + z * cx; y = ny; z = nz2
        v[0] = x; v[1] = y; v[2] = z
    }

    private fun wrap(anchor: Double, centre: Double) = centre + ((((anchor - centre) % WINDOW) + WINDOW * 1.5) % WINDOW) - WINDOW / 2
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
            bWarpSpores.add(x, y, z, sz, sz, col, 1.0)
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
            val cx = cos(ex); val sx = sin(ex); val cy = cos(ey); val sy = sin(ey); val cz = cos(ez); val sz = sin(ez)
            for (gx in 0 until SHEET_N) for (gz in 0 until SHEET_N) {
                val lx = (gx - (SHEET_N - 1) / 2.0) * 1.1; val lz = (gz - (SHEET_N - 1) / 2.0) * 1.1
                val ripple = sin(hypot(lx, lz) * .9 - t * 2.4 + sh) * .8 + sin(lx * .6 + t * 1.1) * .3
                v[0] = lx; v[1] = ripple; v[2] = lz; eulerApplyCs(v, cx, sx, cy, sy, cz, sz)
                put(ax + v[0], ay + v[1], az + v[2], (.75 + sin(t * 2 + gx * .7 + gz * .5) * .25) * shown)
            }
        }
        for (r in 0 until counts[2]) {
            val ax = wrap(rnd(r + off, 51.0) * 4000, s.x); val az = wrap(rnd(r + off, 52.0) * 4000, s.z)
            val ay = groundHeight(ax, az) + 2 + rnd(r + off, 53.0) * 10
            val ex = rnd(r + off, 54.0) * 6.3; val ey = rnd(r + off, 55.0) * 6.3 + t * .1; val ez = rnd(r + off, 56.0) * 6.3
            val rad = 2.4 + rnd(r + off, 57.0) * 2 + sin(t * .8 + r) * .9
            val shown = reveal(2, r)
            val cx = cos(ex); val sx = sin(ex); val cy = cos(ey); val sy = sin(ey); val cz = cos(ez); val sz = sin(ez)
            for (k in 0 until RING_N) {
                val a = (k.toDouble() / RING_N) * PI * 2 + t * (.6 + r * .15) * (if (r % 2 == 1) -1 else 1)
                val rise = if (r % 2 == 1) (k.toDouble() / RING_N - .5) * 5 else sin(k * .9 + t * 2) * .35
                v[0] = cos(a) * rad; v[1] = rise; v[2] = sin(a) * rad; eulerApplyCs(v, cx, sx, cy, sy, cz, sz)
                put(ax + v[0], ay + v[1], az + v[2], (.8 + sin(t * 3 + k) * .2) * shown)
            }
        }
    }

    // ---- per-frame instance assembly (culling, crown LOD, shockwave dissolve) ----
    private fun isCrownKind(kind: Int): Boolean = kind in CROWN_KINDS || kind in CROWN_FAR_KINDS
    private fun farCrownKind(kind: Int): Int {
        val variant = CROWN_KINDS.indexOf(kind)
        return if (variant >= 0) CROWN_FAR_KINDS[variant] else kind
    }

    private fun emit(kind: Int, src: FloatArray, off: Int) {
        val b = bufs[kind]
        b.position(counts[kind] * 16); b.put(src, off, 16); counts[kind]++
    }

    private fun fillInstances(s: Simulation, cam: CameraPose, shadowDue: Boolean) {
        counts.fill(0)
        val fwx = cam.tx - cam.ex; val fwz = cam.tz - cam.ez
        val fl = hypot(fwx, fwz).coerceAtLeast(1e-6)
        val reach = FOG_END + 8
        for (p in props) {
            val dx = p.x - cam.ex; val dz = p.z - cam.ez
            val dist = sqrt(dx * dx + dz * dz)
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
                for (i in p.kinds.indices) {
                    val kind = p.kinds[i]
                    emit(if (far) farCrownKind(kind) else kind, p.m, i * 16)
                }
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
                emit(if (far) farCrownKind(p.kinds[i]) else p.kinds[i], tmp2, 0)
            }
        }
        // Shadow casters beyond the view. The light shines from ahead, so a tree 60 m away still throws its shadow (and a
        // dark gap in the beams) over the ground near the runner. Culling such trees with the visible set made that shadow
        // pop into the beams the moment the tree came into view. They are added here for the shadow map only.
        System.arraycopy(counts, 0, visCounts, 0, KINDS)
        val vis = visCounts
        val lx = moonDirV[0].toDouble(); val lz = moonDirV[2].toDouble()
        val ll = hypot(lx, lz).coerceAtLeast(1e-6)
        val extraReach = SHADOW_FADE_END + 4.0   // beyond this the casters have faded away completely
        val sideMax = feat.shadowExtent + 8.0   // beyond this sideways (crown radius included) a tree cannot touch the shadow map
        if (!shadowDue) return   // the shadow map is not redrawn this frame: skip collecting its casters
        for (pass in 0..1) {
            // Pass 0: the playable window (minus what is already in the visible set). Pass 1: the shadow ring, never drawn otherwise.
            for (p in (if (pass == 0) props else shadowRing)) {
                val dx = p.x - cam.ex; val dz = p.z - cam.ez
                val dist = sqrt(dx * dx + dz * dz)
                if (pass == 0 && dist <= reach && (dx * fwx + dz * fwz) / fl >= -12) continue   // already in the visible set
                if (dist > extraReach || (pass == 0 && s.cleared.contains(p.id))) continue
                if ((dx * lx + dz * lz) / (ll * dist.coerceAtLeast(1e-6)) < .2) continue   // only trees on the light's side matter
                if (abs(-dx * lz + dz * lx) / ll > sideMax) continue
                for (i in p.kinds.indices) {
                    val kd = p.kinds[i]
                    if (kd == K_SHADOW || kd == K_PLANT || kd == K_ROCK || isCrownKind(kd)) continue   // foliage: see the world-fixed sun-gap field
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
        bForestLights.clear(); bPollenGlow.clear(); bPollenCore.clear(); bMist.clear(); bOrbHalo.clear(); bHoleGlow.clear()
        val t = s.elapsed
        if (Visuals.ForestLights.ON) buildForestLights(s, e, t)
        if (Visuals.Pollen.ON) buildPollen(s, e, t)
        if (Visuals.OrbHalo.ON) buildOrbHalos(e)
        if (Visuals.Mist.ON) buildMist(s, t)
        if (Visuals.BlackHole.GLOW) buildBlackHoleGlow(s, t)
    }

    /** Distant forest lights: green, purple and lavender glows that stutter out one by one in survive mode. */
    private fun buildForestLights(s: Simulation, e: GameEngine, t: Double) {
        val v = Visuals.ForestLights
        val haloDay = 1.0 - dayLevel * v.DAY_DIM     // the lights' halos stay visible by day
        val cols = arrayOf(Palette.green, Palette.purple, Palette.lavender, Palette.purple, Palette.lavender)
        for (light in forestLightsFor(s.mode, s.x, s.z)) {
            val lx = light.x; val lz = light.z; val lg = groundHeight(lx, lz) + v.HEIGHT
            for (i in 0 until 5) {
                val own = min(1.0, max(0.0, e.dieRef * 1.6 - i * .15))
                // The stutter-out keeps its rhythm, but each beat eases on and off instead of cutting.
                val flick = if (own > 0 && own < 1) .1 + .9 * smoothRange(sin(s.elapsed * (26 + i * 7)), -.1, .45) else 1.0
                val high = i > 2
                val size = (if (high) v.SIZE_HIGH else if (i == 0) v.SIZE_FIRST else v.SIZE_SIDE) * (1 + sin(t * .4 + i) * .08)
                val px = if (high) lx + (if (i == 3) -3.5 else 3.5) + sin(t * .2 + i) * 2 else lx + (if (i == 0) -5.0 else if (i == 1) 5.0 else 0.0) + sin(t * .16 + i) * 1.5
                val py = if (high) lg + 13.5 + sin(t * .24 + i) * 1.4 else lg
                val far = if (s.mode == GameMode.FREEROAM) farFade(lx, lz, s, v.FAR_FADE_START, v.FAR_FADE_END) else 1.0
                bForestLights.add(px, py, lz, size, size, cols[i], (if (high) v.ALPHA_HIGH else v.ALPHA_LOW) * (1 - own) * flick * haloDay * far, py - groundHeight(px, lz))
            }
        }
    }

    /** Sparse forest pollen. Ground height is cached at spawn time. */
    private fun buildPollen(s: Simulation, e: GameEngine, t: Double) {
        val v = Visuals.Pollen
        val ember = surviveStage(s) == SurviveStage.RED
        val rangeSq = v.RANGE * v.RANGE
        for (si in 0 until e.spores.size) {
            val sp = e.spores[si]
            val dx = sp.x - s.x
            val dz = sp.z - s.z
            val distSq = dx * dx + dz * dz
            if (distSq > rangeSq) continue
            val nearFade = min(1.0, distSq / (v.NEAR_FADE_DIST * v.NEAR_FADE_DIST))
            val farFade = max(0.0, 1.0 - distSq / rangeSq)
            val flutter = sin(t * v.FLUTTER_SPEED + sp.phase)
            val bob = flutter * v.BOB
            val pulse = 1.0 - v.PULSE + v.PULSE * flutter
            val color = if (ember) Palette.ember
                else if (sp.strength > v.WARM_STRENGTH) Palette.pollenWarm else Palette.pollen
            val fade = sp.strength * pulse * nearFade * farFade
            val py = sp.groundY + sp.h + bob
            // Night: a clearly glowing mote. The additive halo is large and bright, and it
            // only dims slightly by day because additive light washes out on a bright sky.
            val size = v.GLOW_SIZE_BASE + sp.strength * v.GLOW_SIZE_GAIN
            bPollenGlow.add(sp.x, py, sp.z, size, size, color, v.GLOW_ALPHA * fade * (1.0 - dayLevel * v.GLOW_DAY_DIM), sp.h + bob)
            // Day: additive light barely shows on a bright background, so each mote also gets a
            // small alpha-blended amber core that is strongest by day and stays faintly lit at night.
            val coreSize = v.CORE_SIZE_BASE + sp.strength * v.CORE_SIZE_GAIN
            val coreColor = if (ember) Palette.ember else Palette.pollenDay
            bPollenCore.add(sp.x, py, sp.z, coreSize, coreSize, coreColor, fade * (v.CORE_ALPHA_NIGHT + v.CORE_ALPHA_DAY * dayLevel), sp.h + bob)
        }
    }

    /** Power-up halos: a steady soft glow around each sphere (picked-up ones are gone). */
    private fun buildOrbHalos(e: GameEngine) {
        val v = Visuals.OrbHalo
        for (o in e.orbs) {
            if (o.taken != null) continue
            val py = groundHeight(o.p.x, o.p.z) + v.HEIGHT
            bOrbHalo.add(o.p.x, py, o.p.z, v.SIZE, v.SIZE, orbColor(o.p.type), v.ALPHA * (1.0 - dayLevel * v.DAY_DIM), v.HEIGHT)
        }
    }

    /** A dim violet halo around each black hole that carries through the fog, and a few orbiting sparks. */
    private fun buildBlackHoleGlow(s: Simulation, t: Double) {
        if (s.mode != GameMode.FREEROAM) return
        var hi = 0
        for (h in holesAround(s.x, s.z, s.universe)) {
            if (hypot(h.x - s.x, h.z - s.z) >= HOLE_SEE) continue
            if (hi >= Visuals.BlackHole.MAX_VISIBLE || s.warp >= 0) break
            val hy = groundHeight(h.x, h.z) + HOLE_HEIGHT + sin(t * .6 + hi) * Visuals.BlackHole.BOB
            bHoleGlow.add(h.x, hy, h.z, 22.0, 22.0, Palette.purple, .3)
            for (k in 0 until 9) {
                val a = t * (1.4 - k * .13) + k * 1.7; val rad = 3.3 + (k % 4) * .7
                bHoleGlow.add(h.x + cos(a) * rad, hy + sin(a * .9 + k) * .5, h.z + sin(a) * rad, .55, .55, Palette.lavender, .8)
            }
            hi++
        }
    }

    /** Low drifting mist ahead (ordinary alpha blending, not additive). */
    private fun buildMist(s: Simulation, t: Double) {
        val v = Visuals.Mist
        for (i in 0 until v.COUNT) {
            val d = v.DISTANCE_FIRST + i * v.DISTANCE_STEP
            val a = s.heading + sin(t * .05 + i * 2.1) * .5 + (if (i % 2 == 1) .35 else -.35)
            val mx = s.x - sin(a) * d; val mz = s.z - cos(a) * d
            bMist.add(mx, groundHeight(mx, mz) + v.LIFT + i * v.LIFT_STEP, mz, v.WIDTH_BASE + i * v.WIDTH_STEP, v.HEIGHT, Palette.moon, v.ALPHA)
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
        if (b === bWarpSpores) b.clear()   // filled while drawing the world, before the sprites
    }

    /**
     * Dynamic resolution: starts at the existing half-resolution target and can step down to one-third
     * of surface resolution on sustained frame-time pressure. Only the effect target is resized.
     */
    private inner class Scaler {
        var density = minOf(START_DENSITY, surfaceDensity)
        private val minDensity = minOf(MIN_DENSITY, surfaceDensity)
        private var avg = 1.0 / 60
        private var slowFor = 0.0
        private var fastFor = 0.0
        private var cooldown = 6.0
        private val maxDensity = minOf(START_DENSITY, surfaceDensity)
        private var ceiling = maxDensity
        private var ceilingHold = 0.0

        /** @return true when [density] changed. */
        fun tick(dt: Double): Boolean {
            val d = dt.coerceIn(0.002, 0.25)
            avg += (d - avg) * 0.05
            cooldown = max(0.0, cooldown - d)
            if (ceilingHold > 0) { ceilingHold -= d; if (ceilingHold <= 0) ceiling = maxDensity }
            val budget = 1.0 / 60
            if (avg > budget * 1.38) { slowFor += d; fastFor = 0.0 }
            else if (avg < budget * 1.12) { fastFor += d; slowFor = 0.0 }
            else { slowFor = 0.0; fastFor = 0.0 }
            if (slowFor > 1.5 && density > minDensity + 0.01f) {
                ceiling = max(minDensity, density - 0.01f); ceilingHold = 90.0
                // Far from 60 fps: a bigger step, so fewer of the (hitching) render-target rebuilds are needed.
                density = max(minDensity, density - (if (avg > budget * 1.9) 0.25f else 0.125f))
                slowFor = 0.0; cooldown = 10.0; avg = budget
                return true
            }
            // Already at the lowest resolution and still too slow: take the mildest effects away, one level at a time
            // (shorter beam march, then no depth of field / motion blur). A device that holds 60 fps never gets here.
            if (slowFor > 3.0 && density <= minDensity + 0.01f && pp.lite < 2) {
                pp.lite++
                slowFor = 0.0; avg = budget
                return false
            }
            val cap = minOf(maxDensity, ceiling)
            if (fastFor > 5.0 && cooldown <= 0.0 && density < cap - 0.01f) {
                density = minOf(cap, density + 0.125f)
                fastFor = 0.0; cooldown = 3.0
                return true
            }
            return false
        }
    }
}
