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
    val bestSecrets: Int = 0,
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
    /** Lattice centre (cell indices) the current trees were generated around; written before worldVersion is bumped. */
    @Volatile var worldCx = 0; @Volatile var worldCz = 0
    var secrets: List<Secret> = emptyList(); private set
    var gates: List<Gate> = emptyList(); private set
    var dayGates: List<DayGate> = emptyList(); private set
    val orbs = ArrayList<Orb>()
    val spores = ArrayList<Spore>(FOREST_POLLEN_COUNT)
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
    private var pollenGeneration = 0
    private var keyCx = Int.MIN_VALUE; private var keyCz = Int.MIN_VALUE; private var keyUniverse = Int.MIN_VALUE
    private var pollenUniverse = Int.MIN_VALUE
    private var lastPowerUp: PowerUpType? = null
    private var lastRing = -1
    private var lastWarp = -1.0
    private var lastDive = -1.0
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
    private var uiBestSecrets = 0

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun post(block: () -> Unit) { commands.add(block) }

    // ---- commands from the UI -------------------------------------------------------------

    fun applySettings(next: Settings) = post {
        val prev = settings
        settings = next
        touchMode = next.touch
        uiBest = next.best; uiLast = next.last
        uiBestSecrets = max(uiBestSecrets, next.bestSecrets)
        audio.setMode(next.sound, inRun())
        // Apply the seasonal soundtrack at the moment persisted settings arrive.
        // Do not rely on the simulation event loop to propagate a menu setting change.
        audio.setChristmas(next.christmas)
        // Developer rates: applied at once, and the gates / holes / relics around the runner are generated again with them.
        val d = next.dev
        if (d != prev.dev) {
            Tuning.powerUps = d.powerUps / 100.0; Tuning.dayGates = d.dayGates / 100.0; Tuning.surviveGates = d.surviveGates / 100.0
            Tuning.blackHoles = d.blackHoles / 100.0; Tuning.secrets = d.secrets / 100.0
            keyCx = Int.MIN_VALUE
        }
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
        // The relics come back every run (beginRun cleared them); only the best count is kept.
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
        publish(force = false)
    }

    private fun rebuildWorldIfNeeded() {
        val s = sim
        val cx = floor(s.x / CELL).toInt(); val cz = floor(s.z / CELL).toInt()
        // Compare plain numbers: the old String key was built (and thrown away) on every single frame.
        if (s.cell.isNotEmpty() && cx == keyCx && cz == keyCz && s.universe == keyUniverse) return
        keyCx = cx; keyCz = cz; keyUniverse = s.universe
        s.cell = "$cx,${cz}u${s.universe}"
        val level = levelOf(s.difficulty)
        // The forest layout is the same in every universe: nothing re-rolls (no trees popping up in front of the runner) when
        // a black hole is entered or left. Only the hue and the holes change with the universe.
        var trees = treesAround(cx, cz, level.treeGap, 0)
        // Undergrowth bushes are disabled: keep the forest floor clear and avoid extra collision/draw work.
        var obstacles = emptyList<Obstacle>()
        if (s.mode == GameMode.FREEROAM) {
            secrets = secretsAround(s.x, s.z)
            gates = gatesAround(s.x, s.z)
            dayGates = dayGatesAround(s.x, s.z)
            val holes = holesAround(s.x, s.z, s.universe)
            fun open(x: Double, z: Double) =
                secrets.all { hypot(x - it.x, z - it.z) > SECRET_CLEARING } &&
                    gates.all { hypot(x - it.x, z - it.z) > GATE_CLEARING } &&
                    dayGates.all { hypot(x - it.x, z - it.z) > DAYGATE_CLEARING } &&
                    holes.all { hypot(x - it.x, z - it.z) > HOLE_CLEARING }
            trees = trees.filter { open(it.x, it.z) }
            obstacles = obstacles.filter { open(it.x, it.z) }
        } else { secrets = emptyList(); gates = emptyList(); dayGates = emptyList() }
        s.trees = trees
        s.obstacles = obstacles
        worldCx = cx; worldCz = cz
        refreshForestPollen(cx, cz, s.universe)
        worldVersion++
    }

    /** Sparse, deterministic forest pollen. Only recycled when it leaves the visible area. */
    private fun refreshForestPollen(cx: Int, cz: Int, universe: Int) {
        if (pollenUniverse != universe) {
            spores.clear()
            pollenUniverse = universe
        }
        val wx = cx * CELL
        val wz = cz * CELL
        for (i in 0 until FOREST_POLLEN_COUNT) {
            val old = spores.getOrNull(i)
            val dx = old?.x?.minus(wx) ?: Double.POSITIVE_INFINITY
            val dz = old?.z?.minus(wz) ?: Double.POSITIVE_INFINITY
            if (old == null || dx * dx + dz * dz > 46.0 * 46.0) {
                val generation = pollenGeneration++
                val sx = wx + (hash(cx + i * 17, cz - i * 11, 701 + generation * 31) - .5) * 64.0
                val sz = wz + (hash(cx - i * 13, cz + i * 19, 709 + generation * 31) - .22) * 68.0
                val h = 1.25 + hash(cx + i * 23, cz - i * 7, 719 + generation * 31) * 2.9
                val phase = hash(cx - i * 5, cz + i * 29, 727 + generation * 31) * (2.0 * PI)
                val strength = .48 + hash(cx + i * 31, cz + i * 3, 733 + generation * 31) * .52
                val spore = Spore(sx, sz, groundHeight(sx, sz), h, phase, strength)
                if (old == null) spores += spore else spores[i] = spore
            }
        }
    }

    private fun handleEvents(raw: Double) {
        val s = sim
        if (s.phase != previousPhase) {
            if (s.phase == Phase.AWAKENING || s.phase == Phase.MENU) {
                orbs.clear(); lastOrbSpawn = 0.0; lastRing = -1
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
            for (g in gates) if (passGate(s, g)) { buzz(30, 40, 30); audio.cue(if (s.surviveTime >= 0) Cue.GHOST else Cue.END) }   // in / out of Survive
            for (g in dayGates) if (enterDayGate(s, g)) break
            for (sec in secrets) if (discoverSecret(s, sec)) onSecret()
        }
        if (s.dive >= 0 && lastDive < 0) { buzz(40, 30, 60, 30, 80); audio.cue(Cue.GHOST) }   // into the day gate
        if (s.dive < 0 && lastDive >= 0) audio.cue(Cue.END)                                   // out of it
        lastDive = s.dive
    }

    private fun updateOrbs(raw: Double) {
        val s = sim
        val live = orbs.any { it.taken == null }
        if (canSpawnPowerUp(s, lastOrbSpawn, live)) {
            val index = floor(s.distance / powerUpGap()).toInt()
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
        // In-place compaction: keeps the survivors in order without a temporary list and set every frame.
        var kept = 0
        for (r in 0 until orbs.size) {
            val orb = orbs[r]
            val taken = orb.taken
            if (taken != null) { if ((s.elapsed - taken) / .25 >= 1) continue; orbs[kept++] = orb; continue }
            if (hypot(orb.p.x - s.x, orb.p.z - s.z) > 70) continue
            if (collectPowerUp(s, orb.p)) { orb.taken = s.elapsed; onPowerUp(orb.p.type) }
            orbs[kept++] = orb
        }
        while (orbs.size > kept) orbs.removeAt(orbs.size - 1)

        if (lastPowerUp != null && s.powerUp == null) onPowerUpEnd()
        lastPowerUp = s.powerUp
        val ring = if (s.powerUp == PowerUpType.SHOCKWAVE) min(SHOCKWAVE_RINGS - 1, floor(s.powerUpTime / SHOCKWAVE_GAP).toInt()) else -1
        if (ring > lastRing && ring > 0) audio.cue(Cue.RING)
        lastRing = ring
        if (s.warp >= 0 && lastWarp < 0) { buzz(60, 40, 120, 40, 60); audio.cue(Cue.GHOST) }   // into the black hole
        if (s.warpOut >= 0 && lastWarpOut < 0) { buzz(120, 30, 40); audio.cue(Cue.END) }   // out of it
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
        audio.cue(Cue.GHOST)   // the ghost orb's sound for every orb (and Cue.END, its fading, when it runs out)
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
        if (secretCount > uiBestSecrets) {
            uiBestSecrets = secretCount
            val best = uiBestSecrets
            scope.launch { repo.setBestSecrets(best) }
        }
    }

    // ---- camera and lamp -------------------------------------------------------------------

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
        // Day gate: the runner falls face first into the hole, the world switches at the middle, and he comes up out of the ground and stands.
        // The camera only pitches (down into the hole, then up at the new sky, then level): it never rolls and never swings round.
        val dive = if (s.dive >= 0) min(1.0, s.dive / DAYGATE_TIME) else -1.0
        val vh = s.heading + menuLook
        val tx = s.x - sin(vh) * 18 + stepX
        val tz = s.z - cos(vh) * 18 + stepZ
        c.tx = tx; c.tz = tz
        c.ty = groundHeight(s.x, s.z) * .6 + groundHeight(s.x - sin(vh) * 18, s.z - cos(vh) * 18) * .4 + 1.62 + fall * 3
        val warpW = warpEnvelope(s)
        if (dive >= 0) {
            val pitch = if (dive < .5) -1.3 * sin(PI / 2 * dive / .5)             // looking down into the hole
                        else 1.3 * (1 - smoothstep(dive, .5, 1.0))                   // the same view from the new world's side (pitch mirrored), then level
            c.ey -= 1.05 * sin(PI * dive)
            c.ty = c.ey + 18 * kotlin.math.tan(pitch)
        }
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
            bestSecrets = uiBestSecrets,
            settings = settings.copy(best = uiBest, last = uiLast),
            notice = notice,
            warp = warpEnvelope(s).toFloat(),
            warpFlash = if (s.warpOut >= 0) (1 - s.warpOut / WARP_EXIT_TIME).toFloat().coerceIn(0f, 1f) else 0f,
            ready = true,
        )
        if (next != published || menuPhase) { published = next; _ui.value = next }
    }

    companion object { const val FOREST_POLLEN_COUNT = 24 }
}
