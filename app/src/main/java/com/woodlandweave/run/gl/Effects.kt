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
}

/** Port of ForestEffects.tsx's per-frame logic: what drives blur, glitch, lens kicks, frost and the rest. */
class EffectsModel {
    private companion object {
        const val LENS_PICKUP = .95 * 1.1
        const val LENS_RING = .5 * 1.1
        const val LENS_END = .45
    }

    private var frost = 0.0
    private var clock = 0.0
    private var hueNow = 0.0
    private var hadPower = false
    private var powerEndedAt = -99.0

    fun update(s: Simulation, delta: Double, out: EffectState, holeFor: (Simulation) -> FloatArray?) {
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
        var glitch = impact * 1.1 + occasional +
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
            lens = (if (ring == 0) LENS_PICKUP else LENS_RING) * exp(-age * 3.2) * cos(age * 14)
            hadPower = true
        } else {
            if (hadPower) { hadPower = false; powerEndedAt = clock }
            val age = clock - powerEndedAt
            if (age >= 0 && age < 2) lens = LENS_END * exp(-age * 3.2) * cos(age * 14)
        }
        var flash = 0.0
        if (s.warp >= 0 && s.warp < 1.6) {
            lens += 2.2 * exp(-s.warp * 2.2) * cos(s.warp * 11)
            flash += max(0.0, 1 - s.warp / .3) * .6
        }
        if (s.warpOut >= 0) {
            lens += 1.9 * exp(-s.warpOut * 2.6) * cos(s.warpOut * 12)
            flash += exp(-s.warpOut * 3.4) * min(1.0, (WARP_EXIT_TIME - s.warpOut) / .5) * .9
        }
        val warp = warpEnvelope(s)

        val hole = holeFor(s)
        if (hole != null) hole.copyInto(out.hole) else { out.hole[0] = .5f; out.hole[1] = .5f; out.hole[2] = .1f; out.hole[3] = 0f }

        val hueTarget = universeHue(s.universe) + warp * sin(clock * 1.4) * .7
        hueNow += (hueTarget - hueNow) * min(1.0, delta * 1.8)

        val shock = s.powerUp == PowerUpType.SHOCKWAVE
        out.waveT = if (shock) s.powerUpTime else -1.0
        if (shock) for (i in 0 until SHOCKWAVE_RINGS) out.reach[i] = shockwaveReach(i, s.waveSpeed).toFloat()
        out.wave = if (shock) min(1.0, s.powerUpTime / .2) * min(1.0, (POWERUP_DURATION - s.powerUpTime) / .3) else 0.0
        val frostTarget = if (s.powerUp == PowerUpType.FREEZER) min(1.0, (POWERUP_DURATION - s.powerUpTime) / .9) else 0.0
        frost += (frostTarget - frost) * min(1.0, delta * 4)
        out.frost = if (frost < .005) 0.0 else frost

        // Survive atmosphere: the red onset hits with a short violent tear.
        if (surviveOn(s) && s.surviveTime >= 0) {
            val r = s.surviveTime - LIGHTS_DIE - DARK_PAUSE
            if (r >= 0 && r < .45) glitch += sin((r / .45) * PI) * 1.1
        }
        glitch += warp * .12
        clock += min(delta, .1)
        out.blur = blur; out.glitch = glitch; out.lens = lens; out.warp = warp; out.flash = flash
        out.hue = hueNow; out.time = clock
    }
}
