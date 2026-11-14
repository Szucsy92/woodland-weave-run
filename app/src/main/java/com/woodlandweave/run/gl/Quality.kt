package com.woodlandweave.run.gl

/**
 * Graphics tiers. LOW is the web-parity look of the previous builds; each step up adds a group of effects.
 *
 *  MEDIUM  filmic tone mapping, bloom, film grain, smooth foliage with procedural leaves (translucent, see-through),
 *          wind, image-based specular, puddles.
 *  HIGH    + moon shadow map, SSAO, volumetric light shafts, grass (40%), finer canopy.
 *  ULTRA   + two shadow cascades, denser shafts, full grass, depth of field, motion blur, chromatic aberration,
 *          and no flat quads at all: real shadows instead of decals, volumetric glows instead of sprites, 3D spores,
 *          rounded stone and column geometry, torus accretion rings.
 */
enum class Quality(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra");
}

/** Everything a tier switches on. All tunables live here. */
class Features(val quality: Quality) {
    private val r = quality.ordinal
    val rank get() = r

    // Image
    val tonemap = r >= 1
    val exposure = 0.92f
    val bloom = r >= 1
    val bloomLevels = if (r >= 2) 5 else 3
    val bloomThreshold = 0.9f
    val bloomStrength = if (r >= 3) 0.34f else 0.26f
    val grain = if (r >= 1) 0.012f else 0f
    val chroma = if (r >= 3) 0.012f else 0f

    // Materials
    val leaf = r >= 1
    val wind = r >= 1
    val smoothMeshes = r >= 1
    val crownNearDetail = if (r >= 2) 3 else 2
    val rockDetail = if (r >= 1) 3 else 2

    // Lighting
    val shadows = r >= 2
    val cascades = if (r >= 3) 2 else 1
    val shadowRes = if (r >= 3) 3072 else 2048
    val shadowExtent0 = if (r >= 3) 14f else 28f
    val shadowExtent1 = 46f
    val ssao = r >= 2
    val ssaoSamples = if (r >= 3) 14 else 8
    val ssaoRadius = 0.7f
    val ssaoStrength = if (r >= 3) 0.95f else 0.85f
    val shafts = r >= 2
    val shaftSteps = if (r >= 3) 40 else 14
    val shaftMax = if (r >= 3) 46f else 34f
    val fogDensity = if (r >= 3) 0.030f else 0.022f

    // Scene
    val grass = r >= 2
    val grassDensity = if (r >= 3) 1f else 0.4f
    val dof = r >= 3
    val dofStrength = 1.8f
    val focus = 15f
    val motionBlur = r >= 3

    /** No billboards or flat cards anywhere. */
    val noQuads = r >= 3

    val needsDepth get() = ssao || shafts || dof || noQuads || motionBlur

    /** `#define`s for the lit shader. */
    fun litDefines(): String = buildString {
        if (r >= 1) append("#define Q_MED\n")
        if (shadows) append("#define Q_SHADOW\n")
        if (cascades > 1) append("#define Q_CASCADE2\n")
    }

    /** `#define`s for the effect shader. */
    fun postDefines(): String = buildString {
        if (r >= 1) append("#define Q_FULL\n")
        if (needsDepth) append("#define Q_DEPTH\n")
        if (shadows && shafts) append("#define Q_SHAFTS\n")
        if (cascades > 1) append("#define Q_CASCADE2\n")
    }

    fun clampedTo(max: Quality) = if (quality.ordinal <= max.ordinal) this else Features(max)
}
