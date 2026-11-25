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
        /** The morphing veils inside the sphere (off: the plain still glass above). */
        const val FLOW = true
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
     * The relics (secrets): the spore spiral, two arms of spores slowly turning among the other spores (with three odd-coloured spores
     * drifting in it), and the fairy ring, six glass spheres of different colours. When found, a swirl of spores rises and the relic
     * dissolves for this run.
     */
    object Relic {
        const val ON = true
        const val MAX = 6                 // relics drawn at once
        const val SEE = 110.0
        const val VANISH_TIME = 2.4       // seconds from "found" to gone

        const val GLOW_PULSE = .18

        // spore spiral
        const val SPIRAL_COUNT = 36       // spores in the two arms
        const val SPIRAL_RADIUS = 3.2
        const val SPIRAL_TURNS = 1.25
        const val SPIRAL_SPIN = .35       // radians per second
        const val SPIRAL_SIZE = .46
        const val SPIRAL_ALPHA = .8
        const val TRIO_SIZE = .7          // the three odd-coloured spores
        const val TRIO_ALPHA = .95

        // fairy ring
        const val FAIRY_COUNT = 6
        const val FAIRY_RADIUS = 2.3
        const val FAIRY_HEIGHT = 1.0
        const val FAIRY_SPIN = .28
        const val FAIRY_SPHERE = .3
        const val FAIRY_GLOW_SIZE = 2.2
        const val FAIRY_GLOW_ALPHA = .55

        // spores: a wide cluster around an unfound relic, and the swirl when it is found
        const val CLUSTER_COUNT = 34
        const val CLUSTER_RADIUS = 7.5
        const val CLUSTER_HEIGHT = 2.2
        const val CLUSTER_SIZE = .42
        const val CLUSTER_ALPHA = .65
        const val SWIRL_COUNT = 44
        const val SWIRL_SIZE = .5
    }

    /**
     * The gate: a plain ring (a torus of radius GATE_HALF) standing upright in the gate's plane, so the runner passes through it.
     * The gate itself works with or without it. GAP is how far the lowest point of the ring floats above the ground.
     */
    object GateRing {
        const val ON = true
        const val GAP = 0.0            // lowest point of the tube above the ground, in metres (0 = the ring may touch the ground)
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
