package com.woodlandweave.run.gl

/**
 * One block per visual element of the forest. Every element has its own switch and its own numbers here, and in GameRenderer its
 * own sprite batch and texture: nothing is shared between elements, so bringing one back or changing one never touches another.
 * (Colours stay in Palette; the geometry of a mesh stays in Meshes.)
 */
object Visuals {
    /** The power-up glass sphere: still, translucent, nothing inside it. */
    object OrbSphere {
        const val ON = true
        const val SIZE = .42
        const val ALPHA = .5f
        const val EMISSIVE = 1.6f
        const val ROUGH = .2f
        const val HEIGHT = 1.15      // centre above the ground
    }

    /** The soft glow around a power-up sphere: its own additive, fogged sprite batch with its own texture. */
    object OrbHalo {
        const val ON = true
        const val SIZE = 2.8
        const val ALPHA = .6
        const val DAY_DIM = .12      // fraction taken off by day
        const val HEIGHT = 1.15
    }

    /**
     * The gate: a plain ring (a torus of radius GATE_HALF) standing upright in the gate's plane, so the runner passes through it.
     * The gate itself works with or without it. GAP is how far the lowest point of the ring floats above the ground.
     */
    object GateRing {
        const val ON = true
        const val GAP = .2             // lowest point of the tube above the ground, in metres
        const val TORUS_R = 2.4        // radius of the torusA mesh; the ring is scaled to GATE_HALF / TORUS_R
        const val TORUS_TUBE = .14     // tube radius of the torusA mesh (scaled the same way)
        const val SEE = 130.0          // gates further away than this get no glow and no lens

        /** A ring of additive glow sprites that shows through the fog (fog is off for them). */
        const val GLOW = true
        const val GLOW_COUNT = 22      // sprites around one ring
        const val GLOW_SIZE = 3.2      // sprite size in metres
        const val GLOW_ALPHA = .5
        const val GLOW_PULSE = .15     // slow breathing, fraction of the alpha
        const val GLOW_MAX = 6         // gates that glow at the same time (the sprite batch holds GLOW_MAX * GLOW_COUNT)

        /** Lens distortion inside the ring, done by the effect pass in screen space. */
        const val LENS = true
        const val LENS_MAGNIFY = .45   // how much the middle is magnified (0 = none)
        const val LENS_FADE_NEAR = 6.0 // the lens fades out over the last metres, so it does not fill the screen when you step through
    }

    /**
     * The black hole, as it always was (body, accretion veil, halo and sparks, the warp's dots, and the warp's screen effects in
     * Effects and the effect shader), except that the wormhole's relics are gone: not drawn in the warp, not left behind in the
     * universes it opens into.
     */
    object BlackHole {
        const val BODY = true
        const val VEILS = true
        const val GLOW = true           // halo and orbiting sparks
        const val WARP_SPORES = true    // the dots that fill the warp
        const val MAX_VISIBLE = 6
        const val BOB = .12
    }

    /** The distant green / purple / lavender forest lights. */
    object ForestLights {
        const val ON = true
        const val HEIGHT = 4.2
        const val SIZE_FIRST = 24.0
        const val SIZE_SIDE = 20.0
        const val SIZE_HIGH = 30.0
        const val ALPHA_LOW = .66
        const val ALPHA_HIGH = .14
        const val DAY_DIM = .25
        const val FAR_FADE_START = 60.0
        const val FAR_FADE_END = 100.0
    }

    /** The forest pollen: an additive glow plus a small alpha-blended core per mote (two batches, two textures). */
    object Pollen {
        const val ON = true
        const val RANGE = 48.0
        const val NEAR_FADE_DIST = 3.0
        const val FLUTTER_SPEED = .38
        const val BOB = .16
        const val PULSE = .06
        const val WARM_STRENGTH = .78
        const val GLOW_SIZE_BASE = .34
        const val GLOW_SIZE_GAIN = .22
        const val GLOW_ALPHA = .7
        const val GLOW_DAY_DIM = .3
        const val CORE_SIZE_BASE = .11
        const val CORE_SIZE_GAIN = .07
        const val CORE_ALPHA_NIGHT = .45
        const val CORE_ALPHA_DAY = .5
    }

    /** The low drifting mist ahead of the runner. */
    object Mist {
        const val ON = true
        const val COUNT = 4
        const val DISTANCE_FIRST = 10
        const val DISTANCE_STEP = 6
        const val WIDTH_BASE = 14.0
        const val WIDTH_STEP = 3.0
        const val HEIGHT = 3.2
        const val ALPHA = .07
        const val LIFT = .7
        const val LIFT_STEP = .15
    }
}
