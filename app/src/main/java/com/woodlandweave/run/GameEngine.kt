package com.woodlandweave.run

import android.content.Context
import com.woodlandweave.run.audio.Cue
import com.woodlandweave.run.audio.ForestAudio
import com.woodlandweave.run.audio.Haptics
import com.woodlandweave.run.audio.Sting
import com.woodlandweave.run.data.Settings
import com.woodlandweave.run.data.SettingsRepo
import com.woodlandweave.run.gl.GlassState
import com.woodlandweave.run.sim.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Orb(val p: PowerUp, var taken: Double? = null)

class CameraPose {
    var ex = 0.0; var ey = 1.6; var ez = 0.0
    var tx = 0.0; var ty = 1.6; var tz = -18.0
    var rotX = 0.0; var rotY = 0.0; var rotZ = 0.0
}

/** What Compose draws. Immutable, published a few times per second. */
data class UiState(
    val phase: Phase = Phase.MENU,
    val pause: PauseState = PauseState.NONE,
    val distance: Int = 0,
    val power: PowerUpType? = null,
    val leavingPower: PowerUpType? = null,
    val secretCount: Int? = null,
    val settings: Settings = Settings(),
    val notice: String = "",
    val warp: Float = 0f,
    val warpFlash: Float = 0f,
    val ready: Boolean = false,
)

/**
 * Owns the simulation and everything the web version kept in React refs. All mutation happens
 * on the GL thread inside [frame]; the UI talks to it through [post].
 */
class GameEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val repo: SettingsRepo,
    val audio: ForestAudio,
    private val haptics: Haptics,
) {
    val sim = Simulation()
    val camera = CameraPose()
    /** Where the UI's glass panels are, for the effect pass's backdrop blur. */
    val glass = GlassState()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    // Input (written by sensor / touch threads).
    @Volatile var touch = 0.0
    @Volatile var tilt = 0.0
    @Volatile var touchMode = false
    val tiltCal = TiltCal()

    @Volatile var settings = Settings()
        private set

    // World bookkeeping read by the renderer.
    @Volatile var worldVersion = 0
    var secrets: List<Secret> = emptyList(); private set
    var gates: List<Gate> = emptyList(); private set
    val orbs = ArrayList<Orb>()
    val spores = ArrayList<Spore>()
    var redMix = 0.0; private set
    var dieRef = 0.0; private set
    var ghostK = 0.0; private set
    var lampX = 0.0; var lampY = 0.0; var lampZ = 0.0; var lampIntensity = 65.0; private set
    var lean = 0.0; private set
    var menuLook = 0.0; private set
    var steeringNow = 0.0; private set

    private var lampSway = 0.0
    private var previousPhase = Phase.MENU
    private var lastImpact = 0.0
    private var lastStumble = 0.0
    private var stage = SurviveStage.LIT
    private var lastOrbSpawn = 0.0
    private var lastPowerUp: PowerUpType? = null
    private var lastRing = -1
    private var lastWarp = -1.0
    private var lastWarpOut = -1.0
    private var activePower: PowerUpType? = null
    private var leavingPower: PowerUpType? = null
    private var leavingUntilNs = 0L
    private var lastPowerType: PowerUpType? = null
    private var secretCount = 0
    private var lastPublishNs = 0L
    private var published = UiState()
    private var uiBest = 0
    private var uiLast = 0

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun post(block: () -> Unit) { commands.add(block) }

    // ---- commands from the UI -------------------------------------------------------------

    fun applySettings(next: Settings) = post {
        val prev = settings
        settings = next
        touchMode = next.touch
        uiBest = next.best; uiLast = next.last
        if (prev.secrets != next.secrets && sim.found.isEmpty()) { /* restored later by start() */ }
        audio.setMode(next.sound, inRun())
        if (sim.phase == Phase.MENU) applySelection()
        publish(force = true)
    }

    fun start() = post {
        val s = sim
        if (s.phase != Phase.MENU && s.phase != Phase.FALLEN) return@post
        applySelection()
        beginRun(s)
        touch = 0.0; tilt = 0.0
        resetTilt(tiltCal, nowMs())
        // Secrets found in earlier sessions stay found.
        s.found.addAll(settings.secrets)
        secretCount = s.found.size
        audio.play(Sting.START)
        publish(force = true)
    }

    fun pause() = post {
        if (requestPause(sim)) { touch = 0.0; publish(force = true) }
    }

    fun resume() = post {
        if (requestResume(sim)) { tilt = 0.0; resetTilt(tiltCal, nowMs()); publish(force = true) }
    }

    fun exitToMenu() = post { if (requestExit(sim)) publish(force = true) }

    fun recenterTilt() { tilt = 0.0; resetTilt(tiltCal, nowMs()) }

    fun onTiltSample(raw: Double, angle: Int, now: Double) {
        tilt = updateTilt(tiltCal, raw, angle, now)
    }

    fun clearNotice() = post { notice = ""; publish(force = true) }
    private var notice = ""

    // ---- helpers --------------------------------------------------------------------------

    private fun nowMs() = System.nanoTime() / 1_000_000.0
    private fun inRun() = (sim.phase == Phase.AWAKENING || sim.phase == Phase.RUNNING) && sim.pause == PauseState.NONE

    private fun applySelection() {
        val s = sim
        if (s.mode == settings.mode && s.difficulty == settings.difficulty) return
        s.mode = settings.mode
        s.difficulty = settings.difficulty
        s.cell = ""
    }

    private fun buzz(vararg p: Long) { if (settings.haptics) haptics.pulse(*p) }

    private fun smoothstep(x: Double, a: Double, b: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    private fun smootherstep(x: Double, a: Double, b: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * t * (t * (t * 6 - 15) + 10)
    }

    // ---- the frame ------------------------------------------------------------------------

    fun frame(raw: Double) {
        while (true) commands.poll()?.invoke() ?: break
        val s = sim
        rebuildWorldIfNeeded()
        // In the menu and while paused a drag (or tilt) looks around; while running it steers.
        val lookMode = s.phase == Phase.MENU || s.phase == Phase.FALLEN || s.pause != PauseState.NONE
        steeringNow = if (lookMode && kotlin.math.abs(touch) > 0.06) touch.coerceIn(-1.0, 1.0) else getSteering(touchMode, touch, tilt)
        stepSimulation(s, steeringNow, raw)
        handleEvents(raw)
        updateCamera(raw)
        updateSpores()
        publish(force = false)
    }

    private fun rebuildWorldIfNeeded() {
        val s = sim
        val cx = floor(s.x / CELL).toInt(); val cz = floor(s.z / CELL).toInt()
        val key = "$cx,${cz}u${s.universe}"
        if (key == s.cell) return
        s.cell = key
        val level = levelOf(s.difficulty)
        var trees = treesAround(cx, cz, level.treeGap, s.universe)
        var obstacles = obstaclesAround(cx, cz, level.obstacleGap)
        if (s.mode == GameMode.FREEROAM) {
            secrets = secretsAround(s.x, s.z)
            gates = gatesAround(s.x, s.z)
            val holes = holesAround(s.x, s.z, s.universe)
            fun open(x: Double, z: Double) =
                secrets.all { hypot(x - it.x, z - it.z) > SECRET_CLEARING } &&
                    gates.all { hypot(x - it.x, z - it.z) > GATE_CLEARING } &&
                    holes.all { hypot(x - it.x, z - it.z) > HOLE_CLEARING }
            trees = trees.filter { open(it.x, it.z) }
            obstacles = obstacles.filter { open(it.x, it.z) }
        } else { secrets = emptyList(); gates = emptyList() }
        s.trees = trees
        s.obstacles = obstacles
        worldVersion++
    }

    private fun handleEvents(raw: Double) {
        val s = sim
        if (s.phase != previousPhase) {
            if (s.phase == Phase.AWAKENING || s.phase == Phase.MENU) {
                orbs.clear(); lastOrbSpawn = 0.0; spores.clear(); lastRing = -1
            }
            if (s.phase == Phase.AWAKENING) { dieRef = 0.0; ghostK = 0.0 }
            previousPhase = s.phase
            onPhase(s.phase, floor(s.distance).toInt())
        }
        audio.setMode(settings.sound, inRun())
        if (s.impact > 0 && lastImpact <= 0) { buzz(65); audio.impactDip() }
        lastImpact = s.impact
        if (s.stumble > 0 && lastStumble <= 0) { buzz(22); audio.cue(Cue.STUMBLE) }
        lastStumble = s.stumble

        // Survive sequence: lights die, a dark moment, then the pursuer arrives.
        val st = surviveStage(s)
        if (st != stage) {
            if (st == SurviveStage.DYING) audio.setMuffled(true)
            if (st == SurviveStage.RED) { audio.play(Sting.THREAT); buzz(40, 40, 90) }
            if (st == SurviveStage.LIT && stage != SurviveStage.LIT) audio.setMuffled(false)
            stage = st
        }
        val lying = s.phase == Phase.FALLING || s.phase == Phase.FALLEN
        val redGoal = if (st == SurviveStage.RED) (if (lying) 1.0 else .8 + s.threat * .2) else 0.0
        redMix += (redGoal - redMix) * min(1.0, raw * (if (redGoal > redMix) 9.0 else 2.5))
        val dieTarget = if (s.surviveTime >= 0) min(1.0, s.surviveTime / LIGHTS_DIE) else 0.0
        dieRef = if (dieTarget >= dieRef) dieTarget else dieRef + (dieTarget - dieRef) * min(1.0, raw * 1.2)

        updateOrbs(raw)

        if (s.mode == GameMode.FREEROAM) {
            for (g in gates) if (passGate(s, g)) { buzz(30, 40, 30); audio.cue(Cue.GATE) }
            for (sec in secrets) if (discoverSecret(s, sec)) onSecret()
        }
    }

    private fun updateOrbs(raw: Double) {
        val s = sim
        val live = orbs.any { it.taken == null }
        if (canSpawnPowerUp(s, lastOrbSpawn, live)) {
            val index = floor(s.distance / POWERUP_GAP).toInt()
            val p = placePowerUp(s.x, s.z, index, floor(s.distance).toInt(), s.heading)
            if (p != null) {
                // Never let a wisp hang inside a trunk: slide it to open ground beside the nearest tree.
                for (tree in s.trees) {
                    val clear = .45 * tree.scale + .22 + 1.3
                    val dx = p.x - tree.x; val dz = p.z - tree.z; val d = hypot(dx, dz)
                    if (d >= clear) continue
                    val nx = if (d > .001) dx / d else 1.0; val nz = if (d > .001) dz / d else 0.0
                    p.x = tree.x + nx * clear; p.z = tree.z + nz * clear
                }
                orbs += Orb(p); lastOrbSpawn = s.distance
            }
        }
        val gone = ArrayList<Orb>()
        for (orb in orbs) {
            val taken = orb.taken
            if (taken != null) { if ((s.elapsed - taken) / .25 >= 1) gone += orb; continue }
            if (hypot(orb.p.x - s.x, orb.p.z - s.z) > 70) { gone += orb; continue }
            if (collectPowerUp(s, orb.p)) { orb.taken = s.elapsed; onPowerUp(orb.p.type) }
        }
        orbs.removeAll(gone.toSet())

        if (lastPowerUp != null && s.powerUp == null) onPowerUpEnd()
        lastPowerUp = s.powerUp
        val ring = if (s.powerUp == PowerUpType.SHOCKWAVE) min(SHOCKWAVE_RINGS - 1, floor(s.powerUpTime / SHOCKWAVE_GAP).toInt()) else -1
        if (ring > lastRing && ring > 0) audio.cue(Cue.RING)
        lastRing = ring
        if (s.warp >= 0 && lastWarp < 0) { buzz(60, 40, 120, 40, 60); audio.cue(Cue.HOLE) }
        if (s.warpOut >= 0 && lastWarpOut < 0) { buzz(120, 30, 40); audio.cue(Cue.WARPOUT) }
        lastWarp = s.warp; lastWarpOut = s.warpOut

        val ghostLeft = POWERUP_DURATION - s.powerUpTime
        val ghostTarget = if (s.powerUp == PowerUpType.GHOST) smootherstep(ghostLeft, 0.0, 1.6) else 0.0
        ghostK += (ghostTarget - ghostK) * min(1.0, raw * (if (ghostTarget > ghostK) 7.0 else 5.0))
        if (ghostK < .01) ghostK = 0.0
    }

    // ---- UI event hooks -------------------------------------------------------------------

    private fun onPhase(next: Phase, score: Int) {
        if (next == Phase.MENU) { applySelection(); audio.setMuffled(false) }
        if (next == Phase.FALLING) {
            audio.play(Sting.END)
            uiLast = score; uiBest = max(uiBest, score)
            buzz(70, 60, 110, 60, 150)
            scope.launch { repo.recordScore(score) }
        }
    }

    private fun onPowerUp(type: PowerUpType) {
        lastPowerType = type; activePower = type; leavingPower = null
        buzz(35, 30, 60)
        audio.cue(when (type) { PowerUpType.FREEZER -> Cue.FREEZER; PowerUpType.GHOST -> Cue.GHOST; PowerUpType.SHOCKWAVE -> Cue.SHOCKWAVE })
    }

    private fun onPowerUpEnd() {
        activePower = null
        leavingPower = lastPowerType
        leavingUntilNs = System.nanoTime() + 1_100_000_000L
        buzz(25)
        audio.cue(Cue.END)
    }

    private fun onSecret() {
        secretCount = sim.found.size
        buzz(30, 50, 30, 50, 80)
        audio.cue(Cue.SECRET)
        val snapshot = sim.found.toSet()
        scope.launch { repo.setSecrets(snapshot) }
    }

    // ---- camera, lamp, spores ---------------------------------------------------------------

    private fun updateCamera(raw: Double) {
        val s = sim
        val t = s.elapsed
        val lying = s.phase == Phase.FALLING || s.phase == Phase.FALLEN
        val fall = if (lying) smoothstep(s.phaseTime, 0.0, 1.25) else 0.0
        val waking = if (s.phase == Phase.AWAKENING) 1 - smoothstep(s.phaseTime, 0.0, 1.15) else 0.0
        val bump = if (s.impact > 0) sin((1 - s.impact / IMPACT_DURATION) * PI) * s.impactSide else 0.0
        val leanAmount = lean
        val tripping = min(1.0, s.stumble * (if (s.speedMul < .8) 1.0 else 2.0))
        val running = s.phase == Phase.RUNNING
        val steering = steeringNow
        val leanTarget = if (lying) sin(t * .13) * .035 else steering
        val bob = if (running) (sin(s.stride) * .5 + .5) * .03 else sin(t * .4) * .012
        val lateralStep = if (running) sin(s.stride * .5) * .09 else 0.0
        val stepX = cos(s.heading) * lateralStep
        val stepZ = -sin(s.heading) * lateralStep
        lean += (leanTarget - lean) * min(1.0, raw * (if (running) 15.0 else 2.0))
        val c = camera
        c.ex = s.x + fall * .45 + leanAmount * .3 + stepX
        c.ey = groundHeight(s.x, s.z) + 1.6 - fall * 1.28 + bob
        c.ez = s.z + fall * .65 + stepZ
        val canLook = s.phase == Phase.MENU || s.phase == Phase.FALLEN || s.pause != PauseState.NONE
        val lookTarget = if (canLook) steering * .5 else 0.0
        menuLook += (lookTarget - menuLook) * min(1.0, raw * (if (canLook) 1.4 else 6.0))
        val vh = s.heading + menuLook
        val tx = s.x - sin(vh) * 18 + stepX
        val tz = s.z - cos(vh) * 18 + stepZ
        c.tx = tx; c.tz = tz
        c.ty = groundHeight(s.x, s.z) * .6 + groundHeight(s.x - sin(vh) * 18, s.z - cos(vh) * 18) * .4 + 1.62 + fall * 3
        val warpW = warpEnvelope(s)
        c.rotX = warpW * sin(t * 1.7) * .035 + bump * .085 + waking * sin(t * 5) * .045 + fall * .065 * sin(t * .32) +
            tripping * sin(t * 17) * .085 + (if (running) sin(s.stride) * .006 else 0.0)
        c.rotY = -leanAmount * .06
        c.rotZ = warpW * (sin(t * 1.1) * .12 + sin(t * 2.7) * .04) - leanAmount * .065 + bump * .12 + fall * .72 +
            waking * sin(t * 4) * .03 + (if (running) sin(s.stride * .5) * .007 else sin(t * .4) * .008) +
            tripping * sin(t * 13) * .06

        val swayTarget = if (running) -steering * .55 else 0.0
        lampSway += (swayTarget - lampSway) * min(1.0, raw * 4)
        val stepSway = if (running) sin(s.stride) * .09 else 0.0
        val rx = cos(vh); val rz = -sin(vh)
        lampX = c.ex + rx * (lampSway + stepSway)
        lampY = c.ey + .35 + (if (running) (sin(s.stride) * .5 + .5) * .05 else 0.0)
        lampZ = c.ez + rz * (lampSway + stepSway)
        val flicker = if (lying) (if (sin(t * 5.7) + sin(t * 13.2) > .25) .14 else .035) else 1 - waking * .8
        lampIntensity = 65 * flicker
    }

    private fun updateSpores() {
        val s = sim
        if (spores.isEmpty()) for (i in 0 until SPORE_COUNT) spores += placeSpore(s.x, s.z, i, 0, true)
        val sh = sin(s.heading); val ch = cos(s.heading)
        for (i in 0 until SPORE_COUNT) {
            val sp = spores[i]
            val wx = sp.x - s.x; val wz = sp.z - s.z
            val side = wx * ch - wz * sh; val back = wx * sh + wz * ch
            if (back > 18 || kotlin.math.abs(side) > 55) {
                val fresh = placeSpore(s.x, s.z, i, floor(s.distance).toInt())
                val dx = fresh.x - s.x; val dz = fresh.z - s.z
                sp.x = s.x + dx * ch + dz * sh
                sp.z = s.z - dx * sh + dz * ch
                sp.h = fresh.h
            }
        }
    }

    // ---- publishing to Compose --------------------------------------------------------------

    private fun publish(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastPublishNs < 90_000_000L) return
        lastPublishNs = now
        val s = sim
        if (leavingPower != null && now > leavingUntilNs) leavingPower = null
        val menuPhase = s.phase == Phase.MENU || s.phase == Phase.FALLEN
        val pauseUi = when (s.pause) {
            PauseState.NONE, PauseState.RESUMING -> PauseState.NONE
            PauseState.EXITING -> PauseState.EXITING
            else -> PauseState.PAUSED
        }
        val next = UiState(
            phase = s.phase,
            pause = pauseUi,
            distance = floor(s.distance).toInt(),
            power = activePower,
            leavingPower = leavingPower,
            secretCount = if (s.mode == GameMode.FREEROAM) secretCount else null,
            settings = settings.copy(best = uiBest, last = uiLast),
            notice = notice,
            warp = warpEnvelope(s).toFloat(),
            warpFlash = if (s.warpOut >= 0) (1 - s.warpOut / WARP_EXIT_TIME).toFloat().coerceIn(0f, 1f) else 0f,
            ready = true,
        )
        if (next != published || menuPhase) { published = next; _ui.value = next }
    }

    companion object { const val SPORE_COUNT = 72 }
}
