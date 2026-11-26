package com.woodlandweave.run.gl

/**
 * Graphics tiers. Only HIGH is selectable now (shown as "Normal", drawn at half resolution, see GameRenderer.RENDER_SCALE); LOW and
 * MEDIUM stay in the enum only as the automatic fallback when a device cannot compile or allocate the full effect set.
 *
 * Earlier description: LOW is the web-parity look of the earlier builds; the other two build on it.
 *
 *  MEDIUM  bloom, smooth round shapes (canopy, rocks, bushes, rings and posts are no longer faceted polygons),
 *          and a more lifelike trunk and roots. Nothing else changes.
 *  HIGH    + everything MEDIUM has, plus filmic tone mapping, key-light shadows, SSAO, volumetric light shafts,
 *          wet sheen, a light tree sway, film grain, depth of field and a speed-proportional motion blur.
 */
enum class Quality(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High");
}

/**
 * The player's fine-tuning of each effect, as a multiplier of the tier's designed strength (1 = as designed, 0 = off).
 * An effect the tier does not have is unaffected.
 */
data class FxTuning(
    val bloom: Float = FX_NEUTRAL,
    val tone: Float = FX_NEUTRAL,
    val shadows: Float = FX_NEUTRAL,
    val ao: Float = 0f,
    val shafts: Float = FX_NEUTRAL,
    val dof: Float = FX_NEUTRAL,
    val motion: Float = FX_NEUTRAL,
    val sharp: Float = 0f,
)

/** The neutral (0) position of the zero-centred fine-tune sliders: the look the renderer is tuned for. -100 turns a group off, +100 doubles it. */
const val FX_NEUTRAL = 1f

/** Everything a tier switches on. All tunables live here. */
class Features(val quality: Quality, val depthOk: Boolean = true) {
    private val r = quality.ordinal
    val rank get() = r

    // Shapes
    val smoothMeshes = r >= 1
    val crownNearDetail = 2
    /** Crowns beyond CROWN_LOD_DISTANCE: the near crown's vertices and normals on a quarter of the triangles, mostly inside the fog. */
    val crownFarDetail = 1
    val rockDetail = 3
    val lifelikeTrees = r >= 1

    /** Contrast-adaptive sharpening of the final picture; counters the softness of upscaling, MSAA and bloom. */
    val sharpen = 0f
    /** Negative texture LOD bias: bark and ground stay crisper at a distance. */
    val lodBias = if (r >= 1) -0.45f else -0.30f
    /** Medium and High see further before the fog closes in. */
    val fogFarScale = if (r >= 1) 1.12f else 1f

    /** Softening of the lamp's near-field falloff (0 keeps the web look exactly). */
    val lampSoft = if (r >= 2) 2.0f else if (r >= 1) 1.5f else 0f

    // Image
    val bloom = r >= 1
    val bloomLevels = if (r >= 2) 5 else 4
    /** Base threshold in linear light; the renderer raises it on bright (day) scenes so the sky never blooms. */
    val bloomThreshold = 0.48f
    val bloomStrength = if (r >= 2) 0.20f else 0.23f
    val tonemap = r >= 2
    val exposure = 0.92f
    val grain = if (r >= 2) 0.006f else 0f
    val chroma = if (r >= 2) 0.0015f else 0f

    // Lighting and atmosphere (High)
    val env = r >= 2
    val wind = r >= 2
    val shadows = r >= 2
    val shadowRes = 1024
    // Half-width of the shadow map in metres, centred ON the runner (not ahead of him): it covers everything the fog lets
    // you see in every direction, so turning never moves the edge of the map and the light never changes with the heading.
    val shadowExtent = 46f
    val ssao = false
    val ssaoSamples = 8
    val ssaoRadius = 0.50f
    val ssaoStrength = 0.78f
    // Every tier has the beams (when the device can resolve depth). High marches 28 steps through the shadow map; Low and
    // Medium march a few steps and take the beam shape from the world-fixed canopy openings alone (Q_SHAFTS_LITE).
    val shafts = depthOk
    // The beams are marched at a quarter of the pixels with a different start offset every frame and averaged over frames
    // (see PostFx.shaftLowPass), so 20 steps (a sample every 1.7 m) look finer than the old 28 fixed ones.
    val shaftSteps = if (r >= 2) 20 else 9
    val shaftMax = 34f
    val fogDensity = 0.015f
    val dof = r >= 2
    val dofStrength = 0.62f
    val focus = 18f
    val motionBlur = r >= 2
    /** Motion blur at full speed, as a fraction of the screen (it scales with speed below that). */
    val motionBlurMax = 0.012f

    val needsDepth get() = ssao || shafts || dof || motionBlur

    /** `#define`s for the lit shader. */
    fun litDefines(): String = buildString {
        if (env) append("#define Q_ENV\n")
        if (shadows) append("#define Q_SHADOW\n")
        // Low keeps one local light, Medium uses two, High uses four.
        append("#define LOCAL_LIGHT_COUNT ${if (r >= 2) 4 else if (r >= 1) 2 else 1}\n")
    }

    /** `#define`s for the effect shader. */
    fun postDefines(): String = buildString {
        if (r >= 1) append("#define Q_FULL\n")
        if (needsDepth) append("#define Q_DEPTH\n")
        // Beams with the shadow map (High) or from the canopy openings only (Low, Medium).
        if (shafts) append(if (shadows) "#define Q_SHAFTS\n" else "#define Q_SHAFTS_LITE\n")
    }
}
