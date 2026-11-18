package com.woodlandweave.run.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Pure game logic. No Android imports: this file is a line-for-line port of the original
// simulation.ts and is covered by JVM unit tests that compare against values from the original.
// ---------------------------------------------------------------------------------------------

const val CELL = 6.0
const val RADIUS = 13
const val FOG_END = 38.0
const val MAX_HEADING = 37.5 * PI / 180.0
const val IMPACT_DURATION = 0.5
const val MAX_IMPACTS = 3
const val PAUSE_SLOW = 0.7
const val RESUME_TIME = 1.3
const val EXIT_TIME = 0.9

enum class PauseState { NONE, SLOWING, PAUSED, RESUMING, EXITING }
enum class Phase { MENU, AWAKENING, RUNNING, FALLING, FALLEN }
enum class Difficulty { EASY, NORMAL, HARD }
enum class GameMode { CLASSIC, SURVIVE, FREEROAM }
enum class PowerUpType { FREEZER, GHOST, SHOCKWAVE }
enum class ForestMood { NIGHT, DAY, AUTO }
enum class SecretType { MONOLITH, RING, LANTERN }
enum class SurviveStage { LIT, DYING, DARK, RED }

class Tree(val x: Double, val z: Double, val scale: Double, val id: String)
class Obstacle(val x: Double, val z: Double, val big: Boolean, val radius: Double, val id: String)
class PowerUp(var x: Double, var z: Double, val type: PowerUpType, val id: String)
class Secret(val x: Double, val z: Double, val type: SecretType, val id: String)
class Gate(val x: Double, val z: Double, val angle: Double, val id: String)
class BlackHole(val x: Double, val z: Double, val id: String)
class Spore(var x: Double, var z: Double, var h: Double)
class Point(val x: Double, val z: Double)

const val POWERUP_START = 100.0
const val POWERUP_DURATION = 5.0
const val POWERUP_GAP = 120.0
const val SHOCKWAVE_FULL = FOG_END + 6
const val SHOCKWAVE_REACH = 0.75
const val SHOCKWAVE_AHEAD = SHOCKWAVE_FULL * SHOCKWAVE_REACH
const val SHOCKWAVE_SIDE = 7.0
const val SHOCKWAVE_SPREAD = 1.25
const val SHOCKWAVE_WAVE = 1.5
const val SHOCKWAVE_SPEED = SHOCKWAVE_AHEAD / SHOCKWAVE_WAVE
const val SHOCKWAVE_GAP = 1.6
const val SHOCKWAVE_RINGS = 3
val SHOCKWAVE_LOOKAHEAD = doubleArrayOf(0.45, 0.72, 1.0)
const val SHOCKWAVE_DISSOLVE = 0.8
const val SHOCKWAVE_COMMIT = 0.35

const val SURVIVE_DARK_AT = 10.0
const val LIGHTS_DIE = 1.2
const val DARK_PAUSE = 1.75
const val FREEROAM_TURN = 1.5

const val SECRET_CELL = 70
const val SECRET_REACH = 3.5
const val SECRET_CLEARING = 6.0
val SECRET_NAMES = mapOf(
    SecretType.MONOLITH to "The Silent Monolith",
    SecretType.RING to "The Fairy Ring",
    SecretType.LANTERN to "The Lost Lantern",
)

const val GATE_CELL = 90
const val GATE_HALF = 2.4
const val GATE_DEPTH = 0.9
const val GATE_CLEARING = 5.0
const val GATE_REARM = 9.0

const val HOLE_CELL = 220
const val HOLE_CHANCE = 0.3
const val HOLE_SEE = 160.0
const val HOLE_RADIUS = 2.2
const val HOLE_HEIGHT = 2.6
const val HOLE_CORE = 2.3
const val HOLE_CLEARING = 10.0
const val WARP_DURATION = 10.0
const val WARP_FADE_IN = 0.7
const val WARP_FADE_OUT = 1.0
const val WARP_EXIT_TIME = 1.4

class Level(val treeGap: Double, val accel: Double, val maxSpeed: Double, val obstacleGap: Double)

fun levelOf(d: Difficulty) = when (d) {
    Difficulty.EASY -> Level(0.57, 140.0, 14.5, 0.84)
    Difficulty.NORMAL -> Level(0.66, 95.0, 17.0, 0.76)
    Difficulty.HARD -> Level(0.75, 70.0, 19.5, 0.66)
}

const val BASE_SPEED = 8.4
const val STRIDE_RATE = 11.0
const val STRIDE_MAX_MUL = 1.4
const val OBSTACLE_DENSITY = 0.5

fun strideMultiplier(speed: Double) = min(STRIDE_MAX_MUL, max(0.5, speed / BASE_SPEED))

/** Deterministic world hash. Int arithmetic wraps exactly like JavaScript's `| 0` and Math.imul. */
fun hash(x: Int, z: Int, salt: Int = 0): Double {
    var h = x * 374761393 + z * 668265263 + salt * 982451653
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967295.0
}

fun groundHeight(x: Double, z: Double): Double {
    val w1 = sin(x * 0.11 + sin(z * 0.07) * 2.4) * cos(z * 0.09 + sin(x * 0.05) * 2.1)
    val w2 = sin(z * 0.23 - sin(x * 0.16) * 1.8) * cos(x * 0.19 + sin(z * 0.12) * 1.4)
    val w3 = sin((x + z) * 0.47 + sin(x * 0.3) * 1.2)
    return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.3
}

private fun fl(v: Double) = floor(v).toInt()

// ---- survive / lights -------------------------------------------------------------------------

fun surviveOn(s: Simulation) = s.mode == GameMode.SURVIVE || (s.mode == GameMode.FREEROAM && s.surviveTime >= 0)

fun surviveStage(s: Simulation): SurviveStage {
    if (!surviveOn(s) || s.surviveTime < 0) return SurviveStage.LIT
    if (s.surviveTime < LIGHTS_DIE) return SurviveStage.DYING
    if (s.surviveTime < LIGHTS_DIE + DARK_PAUSE) return SurviveStage.DARK
    return SurviveStage.RED
}

fun forestLightsAround(x: Double, z: Double): List<Point> {
    val cx = fl(x / 84); val cz = fl(z / 84)
    return List(9) { i ->
        val gx = cx + i % 3 - 1; val gz = cz + i / 3 - 1
        Point(gx * 84.0 + 20, gz * 84.0 + 42)
    }
}

fun fixedForestLights(x: Double, z: Double) = listOf(Point(x, z - 100))

fun forestLightsFor(mode: GameMode, x: Double, z: Double) =
    if (mode == GameMode.FREEROAM) forestLightsAround(x, z) else fixedForestLights(x, z)

// ---- power-ups --------------------------------------------------------------------------------

fun shockwaveReach(ring: Int, speed: Double): Double {
    val share = SHOCKWAVE_LOOKAHEAD[min(ring, SHOCKWAVE_LOOKAHEAD.size - 1)]
    return (SHOCKWAVE_AHEAD * share) / (1 + max(0.0, speed) / SHOCKWAVE_SPEED)
}

fun canSpawnPowerUp(s: Simulation, lastSpawn: Double, hasLiveOrb: Boolean) =
    s.phase == Phase.RUNNING && s.distance >= POWERUP_START && s.powerUp == null && !hasLiveOrb &&
        (lastSpawn == 0.0 || s.distance - lastSpawn >= POWERUP_GAP)

fun placePowerUp(x: Double, z: Double, index: Int, generation: Int, heading: Double = 0.0): PowerUp? {
    if (hash(index, generation, 31) > 0.4) return null
    val type = PowerUpType.values()[min(2, floor(hash(index, generation, 32) * 3).toInt())]
    val side = (hash(index, generation, 33) - .5) * 14
    val ahead = 28 + hash(index, generation, 34) * 22
    return PowerUp(
        x + side * cos(heading) - ahead * sin(heading),
        z - side * sin(heading) - ahead * cos(heading),
        type, "p$index:$generation",
    )
}

fun collectPowerUp(s: Simulation, p: PowerUp): Boolean {
    if (s.phase != Phase.RUNNING || s.powerUp != null) return false
    if (hypot(s.x - p.x, s.z - p.z) > 1.1) return false
    s.powerUp = p.type
    s.powerUpTime = 0.0
    s.waveSpeed = runSpeed(s)
    if (p.type == PowerUpType.SHOCKWAVE) sweepShockwave(s)
    return true
}

// ---- secrets, gates -----------------------------------------------------------------------------

fun secretsAround(x: Double, z: Double): List<Secret> {
    val cx = fl(x / SECRET_CELL); val cz = fl(z / SECRET_CELL)
    val out = ArrayList<Secret>()
    for (i in -1..1) for (j in -1..1) {
        val gx = cx + i; val gz = cz + j
        if (gx == 0 && gz == 0) continue
        if (hash(gx, gz, 300) > 0.45) continue
        out += Secret(
            gx * SECRET_CELL + 15 + hash(gx, gz, 301) * (SECRET_CELL - 30),
            gz * SECRET_CELL + 15 + hash(gx, gz, 302) * (SECRET_CELL - 30),
            SecretType.values()[min(2, floor(hash(gx, gz, 303) * 3).toInt())],
            "s$gx:$gz",
        )
    }
    return out
}

fun discoverSecret(s: Simulation, secret: Secret): Boolean {
    if (s.mode != GameMode.FREEROAM || s.phase != Phase.RUNNING || s.found.contains(secret.id)) return false
    if (hypot(s.x - secret.x, s.z - secret.z) > SECRET_REACH) return false
    s.found.add(secret.id)
    return true
}

fun gatesAround(x: Double, z: Double): List<Gate> {
    val cx = fl(x / GATE_CELL); val cz = fl(z / GATE_CELL)
    val out = ArrayList<Gate>()
    for (i in -1..1) for (j in -1..1) {
        val gx = cx + i; val gz = cz + j
        if (gx == 0 && gz == 0) continue
        if (hash(gx, gz, 500) > 0.4) continue
        out += Gate(
            gx * GATE_CELL + 15 + hash(gx, gz, 501) * (GATE_CELL - 30),
            gz * GATE_CELL + 15 + hash(gx, gz, 502) * (GATE_CELL - 30),
            hash(gx, gz, 503) * PI, "g$gx:$gz",
        )
    }
    return out
}

fun passGate(s: Simulation, gate: Gate): Boolean {
    if (s.mode != GameMode.FREEROAM || s.phase != Phase.RUNNING) return false
    val dx = s.x - gate.x; val dz = s.z - gate.z
    if (s.lastGate == gate.id) {
        if (hypot(dx, dz) > GATE_REARM) s.lastGate = ""
        return false
    }
    val lateral = dx * cos(gate.angle) - dz * sin(gate.angle)
    val normal = dx * sin(gate.angle) + dz * cos(gate.angle)
    if (abs(lateral) > GATE_HALF || abs(normal) > GATE_DEPTH) return false
    s.lastGate = gate.id
    s.surviveTime = if (s.surviveTime >= 0) -1.0 else 0.0
    return true
}

// ---- universes, black holes -------------------------------------------------------------------

fun universeSalt(universe: Int) = if (universe <= 0) 0 else universe * 7919 + 13
fun universeHue(universe: Int) = if (universe <= 0) 0.0 else 0.9 + hash(universe, 3, 810) * 4.5
fun universeDensity(universe: Int) = if (universe <= 0) 1.0 else 0.78 + hash(universe, 5, 811) * 0.5
fun universeScale(universe: Int) = if (universe <= 0) 1.0 else 0.82 + hash(universe, 7, 812) * 0.5
fun universeTime(s: Simulation) = max(0.0, s.elapsed - s.universeAt)

fun holesAround(x: Double, z: Double, universe: Int = 0): List<BlackHole> {
    val cx = fl(x / HOLE_CELL); val cz = fl(z / HOLE_CELL)
    val salt = universeSalt(universe)
    val out = ArrayList<BlackHole>()
    for (i in -1..1) for (j in -1..1) {
        val gx = cx + i; val gz = cz + j
        if (gx == 0 && gz == 0) continue
        if (hash(gx + salt, gz - salt, 700) > HOLE_CHANCE) continue
        out += BlackHole(
            gx * HOLE_CELL + 25 + hash(gx + salt, gz - salt, 701) * (HOLE_CELL - 50),
            gz * HOLE_CELL + 25 + hash(gx + salt, gz - salt, 702) * (HOLE_CELL - 50),
            "h$gx:$gz" + (if (salt != 0) "~$salt" else ""),
        )
    }
    return out
}

fun holeExit(hole: BlackHole): Point {
    val angle = hash(fl(hole.x), fl(hole.z), 703) * PI * 2
    val distance = 170 + hash(fl(hole.x), fl(hole.z), 704) * 110
    return Point(hole.x + cos(angle) * distance, hole.z + sin(angle) * distance)
}

private fun smooth(t: Double) = t * t * (3 - 2 * t)

fun warpEnvelope(s: Simulation): Double {
    if (s.warp < 0 || s.warp >= WARP_DURATION) return 0.0
    return smooth(min(1.0, s.warp / WARP_FADE_IN)) * smooth(min(1.0, (WARP_DURATION - s.warp) / WARP_FADE_OUT))
}

fun enterHole(s: Simulation, hole: BlackHole): Boolean {
    if (s.mode != GameMode.FREEROAM || s.phase != Phase.RUNNING || s.warp >= 0) return false
    if (hypot(s.x - hole.x, s.z - hole.z) > HOLE_CORE) return false
    s.warp = 0.0
    s.warpHole = hole.id
    s.warpFrom = Point(hole.x, hole.z)
    s.warpOut = -1.0
    s.cell = ""
    return true
}

fun exitWarp(s: Simulation) {
    val exit = holeExit(BlackHole(s.warpFrom.x, s.warpFrom.z, s.warpHole))
    s.x = exit.x; s.z = exit.z
    s.warp = -1.0
    s.warpOut = 0.0
    s.stumble = 0.0; s.speedMul = 1.0
    s.pushX = 0.0
    s.universe += 1
    s.universeAt = s.elapsed
    s.cleared.clear(); s.clearedAt.clear()
    s.cell = ""
}

// ---- world generation -------------------------------------------------------------------------

fun treesAround(cx: Int, cz: Int, keep: Double = 0.57, universe: Int = 0): List<Tree> {
    val trees = ArrayList<Tree>()
    val salt = universeSalt(universe); val size = universeScale(universe)
    val density = universeDensity(universe)
    for (i in -RADIUS..RADIUS) for (j in -RADIUS..RADIUS) {
        val gx = cx + i; val gz = cz + j
        val hx = gx + salt; val hz = gz - salt
        if (hash(hx, hz) > keep * density) continue
        val x = gx * CELL + hash(hx, hz, 1) * CELL
        val z = gz * CELL + hash(hx, hz, 2) * CELL
        if (x * x + z * z < 12 * 12) continue
        trees += Tree(x, z, (0.75 + hash(hx, hz, 3) * 0.9) * size, "$gx:$gz" + (if (salt != 0) "~$salt" else ""))
    }
    return trees
}

fun obstaclesAround(cx: Int, cz: Int, keep: Double = 0.84): List<Obstacle> {
    val out = ArrayList<Obstacle>()
    for (i in -RADIUS..RADIUS) for (j in -RADIUS..RADIUS) {
        val gx = cx + i; val gz = cz + j
        if (hash(gx, gz, 9) <= keep) continue
        if (hash(gx, gz, 15) >= OBSTACLE_DENSITY) continue
        val x = gx * CELL + hash(gx, gz, 11) * CELL
        val z = gz * CELL + hash(gx, gz, 12) * CELL
        if (x * x + z * z < 12 * 12) continue
        val big = hash(gx, gz, 13) > 0.86
        val radius = if (big) 0.5 + hash(gx, gz, 14) * 0.45 else 0.22 + hash(gx, gz, 14) * 0.15
        out += Obstacle(x, z, big, radius, "o$gx:$gz")
    }
    return out
}

fun placeSpore(x: Double, z: Double, index: Int, generation: Int = 0, initial: Boolean = false) = Spore(
    x + (hash(index, generation, 6) - .5) * 48,
    z - (if (initial) -12 + hash(index, generation, 5) * 70 else 8 + hash(index, generation, 5) * 50),
    .7 + hash(index, generation, 7) * 3.5,
)

fun sporeOpacity(distance: Double, twinkle: Double): Double {
    val depth = max(0.0, 1 - distance / 70)
    val lamp = max(0.0, 1 - distance / 18)
    return (.32 + twinkle * .1 + lamp * .12) * depth
}

// ---- the simulation state ---------------------------------------------------------------------

class Simulation {
    var x = 0.0; var z = 0.0; var heading = 0.0; var distance = 0.0; var elapsed = 0.0; var phaseTime = 0.0
    var phase = Phase.MENU; var hits = 0; var cooldown = 0.0; var impact = 0.0; var impactSide = 1
    var pushX = 0.0; var lastTree = ""; var cell = ""
    var trees: List<Tree> = emptyList(); var obstacles: List<Obstacle> = emptyList()
    var difficulty = Difficulty.EASY; var stumble = 0.0; var speedMul = 1.0; var lastObstacle = ""; var stride = 0.0
    var pause = PauseState.NONE; var pauseTime = 0.0
    var mode = GameMode.CLASSIC; var powerUp: PowerUpType? = null; var powerUpTime = 0.0; var waveSpeed = BASE_SPEED
    var threat = 0.0
    val cleared = LinkedHashSet<String>(); val clearedAt = HashMap<String, Double>()
    var surviveTime = -1.0; var lastGate = ""; val found = ArrayList<String>()
    var warp = -1.0; var warpOut = -1.0; var warpHole = ""; var warpFrom = Point(0.0, 0.0)
    var universe = 0; var universeAt = 0.0

    /** Equivalent of `Object.assign(s, makeSimulation(), { ... })`. */
    fun reset(difficulty: Difficulty = this.difficulty, mode: GameMode = this.mode, phase: Phase = Phase.MENU, elapsed: Double = 0.0) {
        x = 0.0; z = 0.0; heading = 0.0; distance = 0.0; this.elapsed = elapsed; phaseTime = 0.0
        this.phase = phase; hits = 0; cooldown = 0.0; impact = 0.0; impactSide = 1
        pushX = 0.0; lastTree = ""; cell = ""; trees = emptyList(); obstacles = emptyList()
        this.difficulty = difficulty; stumble = 0.0; speedMul = 1.0; lastObstacle = ""; stride = 0.0
        pause = PauseState.NONE; pauseTime = 0.0
        this.mode = mode; powerUp = null; powerUpTime = 0.0; waveSpeed = BASE_SPEED
        threat = 0.0; cleared.clear(); clearedAt.clear()
        surviveTime = -1.0; lastGate = ""; found.clear()
        warp = -1.0; warpOut = -1.0; warpHole = ""; warpFrom = Point(0.0, 0.0)
        universe = 0; universeAt = 0.0
    }
}

fun beginRun(s: Simulation) = s.reset(s.difficulty, s.mode, Phase.AWAKENING)

fun runSpeed(s: Simulation): Double {
    val level = levelOf(s.difficulty)
    return min(level.maxSpeed, BASE_SPEED + s.distance / level.accel)
}

// ---- collisions -------------------------------------------------------------------------------

fun collide(s: Simulation, tree: Tree): Boolean {
    if (s.phase != Phase.RUNNING) return false
    if (s.powerUp == PowerUpType.GHOST || s.warp >= 0) return false
    if (s.cleared.contains(tree.id)) return false
    val dx = s.x - tree.x; val dz = s.z - tree.z
    val radius = 0.45 * tree.scale + 0.22
    val distance = hypot(dx, dz)
    if (distance >= radius) return false
    val side = if (dx == 0.0) (if (hash(fl(tree.x), fl(tree.z), 7) > 0.5) 1.0 else -1.0) else sign(dx)
    val nz = if (distance > 0.001) dz / distance else 0.0
    if (nz > 0) {
        s.x = tree.x + side * (sqrt(max(0.0, radius * radius - dz * dz)) + 0.01)
    } else {
        val nx = if (distance > 0.001) dx / distance else side
        s.x += nx * (radius - distance + 0.01)
        s.z += nz * (radius - distance + 0.01)
    }
    s.pushX = side * 3.5
    s.heading = if (s.mode == GameMode.FREEROAM) s.heading - side * 0.14
    else max(-MAX_HEADING, min(MAX_HEADING, s.heading - side * 0.14))
    if (s.cooldown > 0 || s.lastTree == tree.id) return false
    s.hits += 1
    s.cooldown = 0.9
    s.lastTree = tree.id
    s.impact = IMPACT_DURATION
    s.impactSide = side.toInt()
    if (s.hits >= MAX_IMPACTS) { s.phase = Phase.FALLING; s.phaseTime = 0.0 }
    if (surviveOn(s)) s.threat = 1.0
    return true
}

fun collideObstacle(s: Simulation, o: Obstacle): Boolean {
    if (s.phase != Phase.RUNNING || s.stumble > 0) return false
    if (s.powerUp == PowerUpType.GHOST || s.warp >= 0) return false
    if (s.cleared.contains(o.id)) return false
    if (hypot(s.x - o.x, s.z - o.z) >= o.radius + 0.24) return false
    s.stumble = if (o.big) 1.0 else 0.5
    s.speedMul = if (o.big) 0.65 else 0.85
    s.lastObstacle = o.id
    if (surviveOn(s)) s.threat = 1.0
    return true
}

// ---- shockwave ----------------------------------------------------------------------------------

fun inShockwave(s: Simulation, x: Double, z: Double): Boolean {
    val dx = x - s.x; val dz = z - s.z
    val along = -dx * sin(s.heading) - dz * cos(s.heading)
    val lateral = dx * cos(s.heading) - dz * sin(s.heading)
    return along > -1 && along < SHOCKWAVE_FULL && abs(lateral) < SHOCKWAVE_SIDE + max(0.0, along) * SHOCKWAVE_SPREAD
}

class ShockHit(val at: Double, val boundary: Boolean)

fun shockwaveHit(s: Simulation, along: Double): ShockHit? {
    val v = s.waveSpeed
    val now = if (s.powerUp == PowerUpType.SHOCKWAVE) s.powerUpTime else 0.0
    var best = Double.POSITIVE_INFINITY; var swept = 0.0
    for (i in 0 until SHOCKWAVE_RINGS) {
        val born = i * SHOCKWAVE_GAP; val reach = shockwaveReach(i, v); val end = born + reach / SHOCKWAVE_SPEED
        if (now >= end) { swept = max(swept, reach); continue }
        if (now > born) swept = max(swept, SHOCKWAVE_SPEED * (now - born))
        val meet = (along + v * now + SHOCKWAVE_SPEED * born) / (SHOCKWAVE_SPEED + v)
        if (meet >= max(now, born) - 1e-9 && meet <= end) best = min(best, meet)
    }
    if (along <= swept + 1e-9) return ShockHit(now, true)
    return if (best.isFinite()) ShockHit(best, false) else null
}

private class Pos(val x: Double, val z: Double, val id: String)

fun sweepShockwave(s: Simulation) {
    val now = if (s.powerUp == PowerUpType.SHOCKWAVE) s.powerUpTime else 0.0
    val all = ArrayList<Pos>(s.obstacles.size + s.trees.size)
    for (o in s.obstacles) all += Pos(o.x, o.z, o.id)
    for (t in s.trees) all += Pos(t.x, t.z, t.id)
    for (o in all) {
        if (s.cleared.contains(o.id) || !inShockwave(s, o.x, o.z)) continue
        val along = -(o.x - s.x) * sin(s.heading) - (o.z - s.z) * cos(s.heading)
        val hit = shockwaveHit(s, along) ?: continue
        if (hit.at - now > SHOCKWAVE_COMMIT) continue
        val spread = hash(fl(o.x * 7), fl(o.z * 7), 31) * (if (hit.boundary) 0.25 else 0.08)
        s.cleared.add(o.id)
        s.clearedAt[o.id] = s.elapsed + max(0.0, hit.at - now) + spread
    }
}

// ---- pause ------------------------------------------------------------------------------------

fun requestPause(s: Simulation): Boolean {
    if (s.phase != Phase.RUNNING && s.phase != Phase.AWAKENING) return false
    if (s.pause != PauseState.NONE && s.pause != PauseState.RESUMING) return false
    s.pause = PauseState.SLOWING; s.pauseTime = 0.0
    return true
}

fun requestResume(s: Simulation): Boolean {
    if (s.pause != PauseState.SLOWING && s.pause != PauseState.PAUSED) return false
    s.pause = PauseState.RESUMING; s.pauseTime = 0.0
    return true
}

fun requestExit(s: Simulation): Boolean {
    if (s.pause == PauseState.NONE || s.pause == PauseState.EXITING) return false
    s.pause = PauseState.EXITING; s.pauseTime = 0.0
    return true
}

fun timeScale(s: Simulation): Double {
    val p = s.pauseTime
    return when (s.pause) {
        PauseState.SLOWING -> 1 - smooth(min(1.0, p / PAUSE_SLOW))
        PauseState.RESUMING -> min(1.0, p / RESUME_TIME).pow(2)
        PauseState.NONE -> 1.0
        else -> 0.0
    }
}

// ---- the step ---------------------------------------------------------------------------------

fun stepSimulation(s: Simulation, steering: Double, delta: Double) {
    val real = min(delta, 0.05)
    if (s.pause != PauseState.NONE) {
        s.pauseTime += real
        if (s.pause == PauseState.SLOWING && s.pauseTime >= PAUSE_SLOW) { s.pause = PauseState.PAUSED; s.pauseTime = 0.0 }
        else if (s.pause == PauseState.RESUMING && s.pauseTime >= RESUME_TIME) { s.pause = PauseState.NONE; s.pauseTime = 0.0 }
        else if (s.pause == PauseState.EXITING && s.pauseTime >= EXIT_TIME) {
            s.reset(s.difficulty, s.mode, Phase.MENU, s.elapsed)
            return
        }
    }
    val dt = real * timeScale(s)
    s.elapsed += dt
    s.phaseTime += dt
    s.impact = max(0.0, s.impact - dt)
    s.cooldown = max(0.0, s.cooldown - dt)
    s.stumble = max(0.0, s.stumble - dt)
    if (s.powerUp != null) {
        s.powerUpTime += dt
        if (s.powerUpTime >= POWERUP_DURATION) { s.powerUp = null; s.powerUpTime = 0.0 }
    }
    if (s.warp >= 0 && s.phase == Phase.RUNNING) {
        s.warp += dt
        if (s.warp >= WARP_DURATION) exitWarp(s)
    }
    if (s.warpOut >= 0) {
        s.warpOut += dt
        if (s.warpOut >= WARP_EXIT_TIME) s.warpOut = -1.0
    }
    if (surviveOn(s) && (s.phase == Phase.FALLING || s.phase == Phase.FALLEN)) s.threat = 1.0
    else s.threat = max(0.0, s.threat - dt)
    if (s.phase == Phase.AWAKENING && s.phaseTime >= 1.15) { s.phase = Phase.RUNNING; s.phaseTime = 0.0 }
    if (s.phase == Phase.FALLING && s.phaseTime >= 2.8) { s.phase = Phase.FALLEN; s.phaseTime = 2.8 }
    if (s.mode == GameMode.SURVIVE) {
        if (s.surviveTime >= 0) s.surviveTime += dt
        else if (s.phase == Phase.RUNNING && s.distance > SURVIVE_DARK_AT) s.surviveTime = 0.0
    } else if (s.mode == GameMode.FREEROAM && s.surviveTime >= 0) {
        s.surviveTime += dt
    }
    if (s.phase != Phase.RUNNING) return
    val level = levelOf(s.difficulty)
    val free = s.mode == GameMode.FREEROAM
    val count = ceil(dt / (1.0 / 120)).toInt()
    if (count <= 0) return
    val sub = dt / count
    if (s.powerUp == PowerUpType.SHOCKWAVE) sweepShockwave(s)
    for (i in 0 until count) {
        if (s.phase != Phase.RUNNING) break
        val chaos = warpEnvelope(s)
        val speed = min(level.maxSpeed, BASE_SPEED + s.distance / level.accel) *
            (if (s.stumble > 0) s.speedMul else 1.0) * (if (s.powerUp == PowerUpType.FREEZER) 0.55 else 1.0) *
            (1 + chaos * 0.2 * sin(s.warp * 3.1))
        if (chaos > 0) s.heading += chaos * (sin(s.warp * 2.1) * cos(s.warp * 0.73 + 1.3) * 0.7 + sin(s.warp * 5.3) * 0.25) * sub
        s.heading = if (free) s.heading - steering * FREEROAM_TURN * sub
        else max(-MAX_HEADING, min(MAX_HEADING, s.heading - steering * 1.7 * sub))
        s.pushX *= exp(-7 * sub)
        s.x += (-sin(s.heading) * speed + s.pushX) * sub
        s.z -= cos(s.heading) * speed * sub
        s.distance += speed * sub
        s.stride += strideMultiplier(speed) * STRIDE_RATE * sub
        for (tree in s.trees) collide(s, tree)
        for (o in s.obstacles) collideObstacle(s, o)
    }
    if (free && s.warp < 0 && s.phase == Phase.RUNNING) {
        for (hole in holesAround(s.x, s.z, s.universe)) if (enterHole(s, hole)) break
    }
}

// ---- input helpers ----------------------------------------------------------------------------

fun getSteering(touchMode: Boolean, touch: Double, tilt: Double): Double {
    val input = if (touchMode) touch else tilt
    return if (abs(input) < 0.06) 0.0 else max(-1.0, min(1.0, input))
}

const val TILT_SETTLE_MS = 500.0
const val TILT_SAMPLES = 6
const val TILT_STEADY = 0.08
const val TILT_FORCE_MS = 1500.0
const val TILT_PEG = 1.2
const val TILT_PEG_MS = 1200.0

class TiltCal {
    var neutral: Double? = null; var angle: Int? = null; var settleUntil = 0.0
    var sum = 0.0; var count = 0; var min = 0.0; var max = 0.0; var since = 0.0; var pegSince = 0.0
}

fun resetTilt(c: TiltCal, now: Double, settleMs: Double = TILT_SETTLE_MS) {
    c.neutral = null; c.settleUntil = now + settleMs
    c.sum = 0.0; c.count = 0; c.since = now + settleMs; c.pegSince = 0.0
}

/** Feeds one tilt sample (-1..1, see [tiltFromGravity]); returns steering tilt -1..1. */
fun updateTilt(c: TiltCal, raw: Double, angle: Int, now: Double): Double {
    if (c.angle != angle) { c.angle = angle; resetTilt(c, now) }
    if (now < c.settleUntil) return 0.0
    val neutral = c.neutral
    if (neutral == null) {
        if (c.count > 0 && max(c.max, raw) - min(c.min, raw) > TILT_STEADY && now - c.since < TILT_FORCE_MS) {
            c.sum = 0.0; c.count = 0
        }
        if (c.count == 0) { c.min = raw; c.max = raw } else { c.min = min(c.min, raw); c.max = max(c.max, raw) }
        c.sum += raw; c.count++
        if (c.count >= TILT_SAMPLES) { c.neutral = c.sum / c.count; c.pegSince = 0.0 }
        return 0.0
    }
    val off = raw - neutral
    if (abs(off) > TILT_PEG) {
        if (c.pegSince == 0.0) c.pegSince = now
        else if (now - c.pegSince > TILT_PEG_MS) { resetTilt(c, now, 250.0); return 0.0 }
    } else c.pegSince = 0.0
    return max(-1.0, min(1.0, off))
}

/** Gravity component along the screen's horizontal axis (m/s², right edge down positive) → -1..1. */
fun tiltFromGravity(screenRight: Double): Double {
    val sx = max(-1.0, min(1.0, screenRight / 9.80665))
    val deg = asin(sx) * 180 / PI
    return max(-1.0, min(1.0, deg / 25))
}

fun daylightFor(mood: ForestMood, hours: Double): Double {
    if (mood == ForestMood.NIGHT) return 0.0
    if (mood == ForestMood.DAY) return 1.0
    val h = ((hours % 24) + 24) % 24
    fun ramp(a: Double, b: Double) = min(1.0, max(0.0, (h - a) / (b - a)))
    return smooth(ramp(5.0, 8.0)) * (1 - smooth(ramp(17.0, 20.0)))
}
