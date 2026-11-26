package com.woodlandweave.run.gl

import com.woodlandweave.run.sim.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Uniform values of the full-screen effect pass. */
class EffectState {
    var blur = 0.0; var glitch = 0.0; var frost = 0.0; var lens = 0.0; var wave = 0.0; var waveT = -1.0
    var warp = 0.0; var flash = 0.0; var hue = 0.0; var time = 0.0
    val reach = FloatArray(SHOCKWAVE_RINGS)
    val hole = floatArrayOf(.5f, .5f, .1f, 0f)
    /** The nearest gate on screen: x, y (0..1), radius (screen heights), strength; [gateSx] squashes the circle into the ellipse the upright ring makes. */
    val gate = floatArrayOf(.5f, .5f, .1f, 0f)
    var gateSx = 1f
    /** Screen aspect (width / height), so the gate-pass kick can match the gate lens's centre zoom. */
    var aspect = 16.0 / 9.0
}

/** Port of ForestEffects.tsx's per-frame logic: what drives blur, glitch, lens kicks, frost and the rest. */
class EffectsModel {
    private companion object {
        /** The tree-hit colour split: how strong it peaks, and how quickly (seconds) the other kicks die away. */
        const val GLITCH_PEAK = 2.6
        const val GLITCH_FALL = 2.2      // how sharply it dies away: higher = quicker
        const val GLITCH_KICK_TIME = .32
    }

    private var frost = 0.0
    private var clock = 0.0
    private var hueNow = 0.0
    private var hadPower = false
    private var powerEndedAt = -99.0

    /**
     * The gate's lens kick, used for EVERY lens event (pickup, expiry, black hole in / out, gate pass): the zoom at the centre of the
     * ring (GateRing.LENS_MAGNIFY) as a springy damped oscillation, identical shape, size and timing everywhere, no 25% reduction.
     */
    private fun gateSpring(age: Double, aspect: Double): Double {
        val m = Visuals.GateRing.LENS_MAGNIFY
        val edge = (aspect * .5).pow(2.0) + .25
        return m / ((1 - m) * edge * 1.6) * exp(-age * 2.4) * cos(age * 11)
    }

    /** The one colour-split punch: k runs from 1 (the moment of the event) to 0. The tree hit and every other kick use it. */
    private fun punch(k: Double) = k.pow(GLITCH_FALL) * GLITCH_PEAK

    fun update(s: Simulation, delta: Double, out: EffectState, holeFor: (Simulation) -> FloatArray?, gateFor: (Simulation) -> FloatArray? = { null }) {
        val lying = s.phase == Phase.FALLING || s.phase == Phase.FALLEN
        val awake = s.phase == Phase.AWAKENING
        val impact = if (s.impact > 0) sin((1 - s.impact / IMPACT_DURATION) * PI) else 0.0
        val occasional = if (lying) max(0.0, sin(s.elapsed * .89 + 2)).pow(50.0) * .38 else 0.0
        var blur = when {
            s.phase == Phase.MENU -> 1.1
            lying -> 1.4 + .5 * sin(s.elapsed * .4)
            awake -> (1 - s.phaseTime / 1.15) * 1.2
            else -> impact * 1.7
        }
        // A tree hit: a sharp punch that decays (the old sine envelope started at 0 and was smeared away by the blur).
        val hitK = if (s.impact > 0) (s.impact / IMPACT_DURATION).coerceIn(0.0, 1.0) else 0.0
        var glitch = punch(hitK) + occasional +
            (if (awake) sin(s.phaseTime * 8).pow(2.0) * (1 - s.phaseTime / 1.15) * .35 else 0.0)

        // Pause warp: blur and glitch swell while time slows, hold softly while frozen, drain as the run resumes.
        val p = s.pauseTime
        var pb = 0.0; var pg = 0.0
        when (s.pause) {
            PauseState.SLOWING -> { val k = min(1.0, p / PAUSE_SLOW); pb = k * 1.5; pg = sin(k * PI) * .9 + k * .08 }
            PauseState.PAUSED -> { pb = 1.5 + .2 * sin(p * .6); pg = .08 + max(0.0, sin(p * 1.3)).pow(40.0) * .3 }
            PauseState.RESUMING -> { val k = min(1.0, p / RESUME_TIME); pb = (1 - k) * 1.5; pg = sin(k * PI) * .8 + (1 - k) * .08 }
            PauseState.EXITING -> { val k = min(1.0, p / EXIT_TIME); pb = 1.5 + k * 2.5; pg = .3 + sin(k * PI) * .9 }
            PauseState.NONE -> {}
        }
        val arrive = if (s.phase == Phase.MENU) max(0.0, 1 - s.phaseTime / .9) * 2.9 else 0.0
        blur = max(blur + arrive, pb)
        glitch += pg + arrive * .15

        // Power-ups: a glitch surge on pickup, a faint shimmer while active and a last flutter as it fades.
        s.powerUp?.let { type ->
            val a = s.powerUpTime; val left = POWERUP_DURATION - a
            val surge = if (a < .5) sin((a / .5) * PI) * .7 else 0.0
            val fade = if (left < .6) sin((left / .6) * PI) * .45 else 0.0
            glitch += if (type == PowerUpType.FREEZER) surge * .3
            else (if (type == PowerUpType.SHOCKWAVE) surge * .5 else surge) + fade + .05
        }

        // Elastic fisheye on pickup: a damped spring (bulge, overshoot, settle).
        var lens = 0.0
        if (s.powerUp != null) {
            val a = s.powerUpTime
            val ring = if (s.powerUp == PowerUpType.SHOCKWAVE) min(SHOCKWAVE_RINGS - 1, floor(a / SHOCKWAVE_GAP).toInt()) else 0
            val age = a - ring * SHOCKWAVE_GAP
            lens = gateSpring(age, out.aspect)
            hadPower = true
        } else {
            if (hadPower) { hadPower = false; powerEndedAt = clock }
            val age = clock - powerEndedAt
            if (age >= 0 && age < GATE_KICK_TIME) lens = gateSpring(age, out.aspect)
        }
        var flash = 0.0
        if (s.warp >= 0 && s.warp < 1.6) {
            lens += gateSpring(s.warp, out.aspect)
            flash += max(0.0, 1 - s.warp / .3) * .6
        }
        // Passing a survive gate (in or out): the same elastic fisheye as the black hole / a pickup, a damped spring.
        // Its size is the one the gate's own lens defines (GateRing.LENS_MAGNIFY = the zoom at the centre of the ring), as a springy
        // damped oscillation, and it skips the 25% reduction of the other kicks.
        val aspect = out.aspect
        var gateLens = 0.0
        if (s.gateKick >= 0 && s.gateKick < GATE_KICK_TIME) {
            gateLens = gateSpring(s.gateKick, aspect)
            flash += max(0.0, 1 - s.gateKick / .25) * .25
        }
        if (s.warpOut >= 0) {
            lens += if (s.warpOut < GATE_KICK_TIME) gateSpring(s.warpOut, out.aspect) else 0.0
            flash += exp(-s.warpOut * 3.4) * min(1.0, (WARP_EXIT_TIME - s.warpOut) / .5) * .9
        }
        val warp = warpEnvelope(s)

        val hole = holeFor(s)
        if (hole != null) hole.copyInto(out.hole) else { out.hole[0] = .5f; out.hole[1] = .5f; out.hole[2] = .1f; out.hole[3] = 0f }

        val gate = gateFor(s)
        if (gate != null) { out.gate[0] = gate[0]; out.gate[1] = gate[1]; out.gate[2] = gate[2]; out.gate[3] = gate[3]; out.gateSx = gate[4] }
        else { out.gate[0] = .5f; out.gate[1] = .5f; out.gate[2] = .1f; out.gate[3] = 0f; out.gateSx = 1f }

        val hueTarget = universeHue(s.universe) + warp * sin(clock * 1.4) * .7
        hueNow += (hueTarget - hueNow) * min(1.0, delta * 1.8)

        val shock = s.powerUp == PowerUpType.SHOCKWAVE
        out.waveT = if (shock) s.powerUpTime else -1.0
        if (shock) for (i in 0 until SHOCKWAVE_RINGS) out.reach[i] = shockwaveReach(i, s.waveSpeed).toFloat()
        out.wave = if (shock) min(1.0, s.powerUpTime / .2) * min(1.0, (POWERUP_DURATION - s.powerUpTime) / .3) else 0.0
        val frostTarget = if (s.powerUp == PowerUpType.FREEZER) min(1.0, (POWERUP_DURATION - s.powerUpTime) / .9) else 0.0
        // Frost creeps in and melts away slowly: a full fade both ways, no snap.
        frost += (frostTarget - frost) * min(1.0, delta * (if (frostTarget > frost) 2.4 else 1.6))
        out.frost = if (frost < .002) 0.0 else frost

        // Survive atmosphere: the red onset hits with a short violent tear.
        if (surviveOn(s) && s.surviveTime >= 0) {
            val r = s.surviveTime - LIGHTS_DIE - DARK_PAUSE
            if (r >= 0 && r < .45) glitch += sin((r / .45) * PI) * 1.1
        }
        // The same smooth purple/green colour split as a tree hit: black hole in and out, passing a gate, finding a secret.
        fun kick(age: Double) = if (age >= 0 && age < GLITCH_KICK_TIME) punch(1 - age / GLITCH_KICK_TIME) else 0.0
        glitch += kick(s.warp) + kick(s.warpOut) + kick(s.gateKick)
        val lastFound = s.foundAt.values.maxOrNull()
        if (lastFound != null) glitch += kick(s.elapsed - lastFound)
        glitch += warp * .12
        clock += min(delta, .1)
        out.blur = blur; out.glitch = glitch; out.lens = lens + gateLens; out.warp = warp; out.flash = flash
        out.hue = hueNow; out.time = clock
    }
}
