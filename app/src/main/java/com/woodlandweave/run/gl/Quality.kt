package com.woodlandweave.run.gl

/**
 * Graphics tiers. LOW is the web-parity look of the earlier builds; the other two build on it.
 *
 *  MEDIUM  bloom, smooth round shapes (canopy, rocks, bushes, rings and posts are no longer faceted polygons),
 *          and a more lifelike trunk and roots. Nothing else changes.
 *  HIGH    + everything MEDIUM has, plus filmic tone mapping, moon shadows, SSAO, volumetric light shafts,
 *          wet sheen and puddles, a light tree sway, film grain, depth of field and a speed-proportional motion blur.
 */
enum class Quality(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High");
}

/**
 * The player's fine-tuning of each effect, as a multiplier of the tier's designed strength (1 = as designed, 0 = off).
 * An effect the tier does not have is unaffected.
 */
data class FxTuning(
    val bloom: Float = 1f,
    val tone: Float = 1f,
    val shadows: Float = 1f,
    val ao: Float = 1f,
    val shafts: Float = 1f,
    val dof: Float = 1f,
    val motion: Float = 1f,
)

/** Everything a tier switches on. All tunables live here. */
class Features(val quality: Quality) {
    private val r = quality.ordinal
    val rank get() = r

    // Shapes
    val smoothMeshes = r >= 1
    val crownNearDetail = 3
    val rockDetail = 3
    val lifelikeTrees = r >= 1

    /** Real point lights from orbs, halos, gates, secrets and wormholes (the nearest ones, faded smoothly in and out). */
    val pointLights = if (r >= 2) 4 else if (r >= 1) 2 else 0

    /** Softening of the lamp's near-field falloff (0 keeps the web look exactly). */
    val lampSoft = if (r >= 2) 2.0f else if (r >= 1) 1.5f else 0f

    // Image
    val bloom = r >= 1
    val bloomLevels = if (r >= 2) 5 else 4
    /** Base threshold in linear light; the renderer raises it on bright (day) scenes so the sky never blooms. */
    val bloomThreshold = 0.30f
    val bloomStrength = if (r >= 2) 0.45f else 0.50f
    val tonemap = r >= 2
    val exposure = 0.92f
    val grain = if (r >= 2) 0.012f else 0f
    val chroma = if (r >= 2) 0.006f else 0f

    // Lighting and atmosphere (High)
    val env = r >= 2
    val wind = r >= 2
    val shadows = r >= 2
    val shadowRes = 2048
    val shadowExtent = 28f
    val ssao = r >= 2
    val ssaoSamples = 8
    val ssaoRadius = 0.7f
    val ssaoStrength = 0.85f
    val shafts = r >= 2
    val shaftSteps = 12
    val shaftMax = 32f
    val fogDensity = 0.020f
    val dof = r >= 2
    val dofStrength = 1.3f
    val focus = 15f
    val motionBlur = r >= 2
    /** Motion blur at full speed, as a fraction of the screen (it scales with speed below that). */
    val motionBlurMax = 0.020f

    val needsDepth get() = ssao || shafts || dof || motionBlur

    /** `#define`s for the lit shader. */
    fun litDefines(): String = buildString {
        if (env) append("#define Q_ENV\n")
        if (shadows) append("#define Q_SHADOW\n")
    }

    /** `#define`s for the effect shader. */
    fun postDefines(): String = buildString {
        if (r >= 1) append("#define Q_FULL\n")
        if (needsDepth) append("#define Q_DEPTH\n")
        if (shadows && shafts) append("#define Q_SHAFTS\n")
    }
}
