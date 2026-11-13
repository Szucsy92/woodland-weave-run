package com.woodlandweave.run.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.woodlandweave.run.GameEngine
import com.woodlandweave.run.UiState
import com.woodlandweave.run.data.Settings
import com.woodlandweave.run.gl.GlassState
import com.woodlandweave.run.gl.GameView
import com.woodlandweave.run.sim.*
import com.woodlandweave.run.update.UpdateController
import com.woodlandweave.run.update.UpdateInfo
import com.woodlandweave.run.update.UpdateState
import com.woodlandweave.run.update.describeUpdateEvent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

// The forest palette and glass look from styles.css.
// oklch(0.94 .004 290), oklch(0.77 .015 290), oklch(0.36 .009 290 / .3), oklch(0.87 .01 290 / .2)
private val TextC = Color(0xFFEBEBEE)
private val Muted = Color(0xFFB4B3BD)
private val GlassC = Color(0x4D3D3C42)   // only used when no GL backdrop is available
private val Edge = Color(0x33D4D3DA)
private val Lit = Color(0x1AFFFFFF)

/** The effect pass blurs the picture behind the glass panels; panels report where they are through this. */
private val LocalGlass = staticCompositionLocalOf<GlassState?> { null }

class Actions(
    val setSound: (Boolean) -> Unit,
    val setHaptics: (Boolean) -> Unit,
    val setTouch: (Boolean) -> Unit,
    val setAnyOrientation: (Boolean) -> Unit,
    val setDifficulty: (Difficulty) -> Unit,
    val setMode: (GameMode) -> Unit,
    val setMood: (ForestMood) -> Unit,
    val finish: () -> Unit,
)

@Composable
fun ForestApp(engine: GameEngine, gameView: GameView, actions: Actions, updates: UpdateController) {
    val ui by engine.ui.collectAsState()
    val density = LocalDensity.current.density
    var settingsOpen by remember { mutableStateOf(false) }
    val settingsNow by rememberUpdatedState(settingsOpen)
    val uiNow by rememberUpdatedState(ui)
    val s = ui.settings
    val running = ui.phase == Phase.AWAKENING || ui.phase == Phase.RUNNING
    val menu = ui.phase == Phase.MENU || ui.phase == Phase.FALLEN
    val paused = ui.pause == PauseState.PAUSED

    BackHandler {
        when {
            settingsOpen -> settingsOpen = false
            running && ui.pause == PauseState.NONE -> engine.pause()
            paused -> engine.resume()
            else -> actions.finish()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = TextC, surface = Color(0xFF101A2C), onSurface = TextC)) {
      CompositionLocalProvider(LocalGlass provides engine.glass) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { engine.glass.rootWidthDp = it.width / density; engine.glass.rootHeightDp = it.height / density }
                .background(Color(0xFF282828))
                // Same touch model as the web build: hold the left / right half to steer; in the menu a tap
                // starts the run and a horizontal drag looks around. Anything a button handled is ignored.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
                        val look = uiNow.phase == Phase.MENU || uiNow.phase == Phase.FALLEN || uiNow.pause == PauseState.PAUSED
                        val startX = down.position.x
                        var moved = false
                        fun apply(x: Float) {
                            if (look) {
                                val dx = x - startX
                                if (abs(dx) > 8f) moved = true
                                engine.touch = (-dx / 90f).coerceIn(-1f, 1f).toDouble()
                            } else if (uiNow.pause == PauseState.NONE) {
                                engine.touch = if (x < size.width / 2f) -1.0 else 1.0
                            }
                        }
                        apply(down.position.x)
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Final)
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            apply(c.position.x)
                        }
                        engine.touch = 0.0
                        if (look && !moved && uiNow.phase.let { it == Phase.MENU || it == Phase.FALLEN } && uiNow.pause == PauseState.NONE && !settingsNow) engine.start()
                    }
                },
        ) {
            AndroidView(factory = { gameView }, modifier = Modifier.fillMaxSize())

            val power = ui.power ?: ui.leavingPower
            if (power != null) PowerFrame(power, active = ui.power != null)

            if (running || ui.phase == Phase.FALLING) Hud(ui)
            if (running && ui.pause == PauseState.NONE) PauseButton(onClick = engine::pause)
            if (menu && !settingsOpen) MenuPanel(ui, updates, onSettings = { settingsOpen = true })
            if (paused && !settingsOpen) PausePanel(onResume = engine::resume, onSettings = { settingsOpen = true }, onExit = { settingsOpen = false; engine.exitToMenu() })
            if (settingsOpen) SettingsPanel(ui, actions, updates, onClose = { settingsOpen = false })
        }
      }
    }
}

// ---- glass pieces ------------------------------------------------------------------------------

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlass.current
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(10.dp)
    // .forest-glass { animation: forest-arrive 1.2s ease }: fade in while rising 6px.
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, tween(1200, easing = CubicBezierEasing(.25f, .1f, .25f, 1f))) }
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val b = bounds
    val a = arrive.value
    SideEffect {
        // backdrop-filter: blur(20px) saturate(70%) and the 0 16px 70px shadow are drawn by the GL effect pass.
        glass?.set(0, if (b == null) null else GlassState.Panel(b.left / density, b.top / density, b.right / density, b.bottom / density, 20f, .7f, true, a))
    }
    DisposableEffect(Unit) { onDispose { glass?.set(0, null) } }
    Column(
        modifier
            .widthIn(max = 356.dp)
            .graphicsLayer { alpha = arrive.value; translationY = (1f - arrive.value) * 6f * density }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(shape)
            .then(if (glass == null) Modifier.background(GlassC) else Modifier)
            .border(1.dp, Edge, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    val density = LocalDensity.current.density
    // .forest-title::before / ::after: a purple and a green copy that flicker sideways for a moment every 3.4 s.
    val clock = rememberInfiniteTransition(label = "glitch").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "t",
    )
    Box {
        Text(text, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.44.sp)
        Text(
            text, color = Color(0xFF883CDB), fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.44.sp,
            modifier = Modifier.graphicsLayer {
                val t = clock.value
                alpha = if (t < .89f) 0f else if (t < .92f) .85f else if (t < .95f) .6f else 0f
                translationX = (if (t < .89f) 0f else if (t < .92f) 2f else if (t < .95f) -2f else 0f) * density
            },
        )
        Text(
            text, color = Color(0xFF24D975), fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.44.sp,
            modifier = Modifier.graphicsLayer {
                val t = clock.value
                alpha = if (t < .90f) 0f else if (t < .93f) .8f else if (t < .96f) .55f else 0f
                translationX = (if (t < .90f) 0f else if (t < .93f) -2f else if (t < .96f) 2f else 0f) * density
            },
        )
    }
}

@Composable
private fun IconBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun Hud(ui: UiState) {
    val density = LocalDensity.current.density
    // text-shadow: 0 1px 14px oklch(0.1 0.01 290 / 0.6)
    val shadow = Shadow(Color(0x99030306), Offset(0f, density), 14f * density)
    val big = TextStyle(shadow = shadow, fontFeatureSettings = "tnum")
    val small = TextStyle(shadow = shadow)
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 4.dp)) {
        Row(Modifier.align(Alignment.TopCenter), verticalAlignment = Alignment.Bottom) {
            Text("${ui.distance}", color = TextC, fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = 1.04.sp, style = big)
            Text("m", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp), style = small)
            ui.secretCount?.let {
                Text(" · $it ${if (it == 1) "secret" else "secrets"}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp), style = small)
            }
        }
    }
}

@Composable
private fun PauseButton(onClick: () -> Unit) {
    val glass = LocalGlass.current
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val b = bounds
    SideEffect {
        // .forest-pause: backdrop-filter: blur(8px), opacity .45 (the GL pass applies both).
        glass?.set(1, if (b == null) null else GlassState.Panel(b.left / density, b.top / density, b.right / density, b.bottom / density, 8f, 1f, false, .45f))
    }
    DisposableEffect(Unit) { onDispose { glass?.set(1, null) } }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 12.dp, top = 6.dp)) {
        Box(
            Modifier.align(Alignment.TopStart).size(40.dp).alpha(.45f)
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .clip(RoundedCornerShape(10.dp))
                .then(if (glass == null) Modifier.background(GlassC) else Modifier)
                .border(1.dp, Edge, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { Box(Modifier.size(width = 4.dp, height = 15.dp).clip(RoundedCornerShape(1.dp)).background(TextC)) }
            }
        }
    }
}

/**
 * Every power-up: the same frame glowing inward from the screen outline (a flare on pickup that settles into a soft
 * pulse) plus a faint veil in the power-up's colour. Mirrors .forest-power-glow / .forest-power-veil.
 */
@Composable
private fun PowerFrame(type: PowerUpType, active: Boolean) {
    val c = when (type) { PowerUpType.FREEZER -> Color(0xFF2B6CFF); PowerUpType.GHOST -> Color(0xFF2EE88A); PowerUpType.SHOCKWAVE -> Color(0xFFB04DFF) }
    val density = LocalDensity.current.density
    val entry = remember { Animatable(0f) }
    val pulse = remember { Animatable(.55f) }
    val veilIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { veilIn.animateTo(1f, tween(600, easing = LinearOutSlowInEasing)) }
    LaunchedEffect(active, type) {
        if (active) {
            entry.snapTo(0f)
            entry.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
            pulse.snapTo(.55f)
            while (true) {
                pulse.animateTo(1f, tween(1700, easing = FastOutSlowInEasing))
                pulse.animateTo(.55f, tween(1700, easing = FastOutSlowInEasing))
            }
        }
    }
    // The ending is a slow, even fade for frame and veil alike.
    val fade by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 0 else 1100, easing = FastOutSlowInEasing), label = "fade")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = veilIn.value * fade }.background(c.copy(alpha = .10f)))
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val e = entry.value
                    // forest-power-in: 0% (0, scale 1.14) -> 30% (1, scale 1) -> 100% (.55), then the pulse.
                    val a = if (e < 1f) (if (e < .3f) e / .3f else 1f - (e - .3f) / .7f * .45f) else pulse.value
                    alpha = a * fade
                    val sc = if (e < .3f) 1.14f - .14f * (e / .3f) else 1f
                    scaleX = sc; scaleY = sc
                }
                .drawBehind { frameGlow(c, density) },
        )
    }
}

private fun erf(x: Float): Float {
    val t = 1f / (1f + 0.3275911f * abs(x))
    val y = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * exp(-x * x)
    return if (x >= 0f) y else -y
}
private fun phi(x: Float) = 0.5f * (1f + erf(x / 1.41421356f))

/** box-shadow: inset 0 0 90px 18px (colour @ 38%), inset 0 0 6px 1px (white @ 45%): a soft frame from the edges. */
private fun DrawScope.frameGlow(c: Color, d: Float) {
    fun bands(reachDp: Float, dists: FloatArray, alphaAt: (Float) -> Float, color: Color) {
        val reach = reachDp * d
        val stops = Array(dists.size) { i -> (dists[i] / reachDp) to color.copy(alpha = alphaAt(dists[i]).coerceIn(0f, 1f)) }
        drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = reach), Offset(0f, 0f), Size(size.width, reach))
        drawRect(Brush.verticalGradient(*stops, startY = size.height, endY = size.height - reach), Offset(0f, size.height - reach), Size(size.width, reach))
        drawRect(Brush.horizontalGradient(*stops, startX = 0f, endX = reach), Offset(0f, 0f), Size(reach, size.height))
        drawRect(Brush.horizontalGradient(*stops, startX = size.width, endX = size.width - reach), Offset(size.width - reach, 0f), Size(reach, size.height))
    }
    bands(165f, floatArrayOf(0f, 12f, 24f, 40f, 60f, 80f, 105f, 135f, 165f), { x -> .38f * (1f - phi((x - 18f) / 45f)) }, c)
    bands(14f, floatArrayOf(0f, 1f, 2f, 3.5f, 5f, 7f, 10f, 14f), { x -> .45f * (1f - phi((x - 1f) / 3f)) }, Color.White)
}

@Composable
private fun MenuPanel(ui: UiState, updates: UpdateController, onSettings: () -> Unit) {
    val s = ui.settings
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp), contentAlignment = Alignment.Center) {
        Glass(Modifier.fillMaxWidth(.95f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Title(if (ui.phase == Phase.FALLEN) "tap and run again!" else "tap and run!") }
                IconBtn("⚙", onSettings)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Score("Last", s.last, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(40.dp).background(Edge))
                Score("Best", s.best, Modifier.weight(1f))
            }
            UpdateBanner(updates)
        }
    }
}

@Composable
private fun Score(label: String, value: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Light)
            Text("m", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        }
    }
}

@Composable
private fun PausePanel(onResume: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp), contentAlignment = Alignment.Center) {
        Glass(Modifier.fillMaxWidth(.95f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onResume).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { Title("resume") }
                Box(Modifier.width(1.dp).height(34.dp).background(Edge))
                Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { IconBtn("⚙", onSettings); IconBtn("✕", onExit) }
            }
        }
    }
}

// ---- settings ----------------------------------------------------------------------------------

@Composable
private fun SettingsPanel(ui: UiState, a: Actions, updates: UpdateController, onClose: () -> Unit) {
    val s = ui.settings
    val inMenu = ui.phase == Phase.MENU
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Glass(Modifier.fillMaxWidth(.9f).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterStart)) { IconBtn("←", onClose) }
                Text("Settings", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
            }
            Column(
                Modifier.heightIn(max = 340.dp)
                    // mask-image: the list fades out over 26px at both ends.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val f = (26.dp.toPx() / size.height).coerceIn(0f, .5f)
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, (1f - f) to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingRow("Difficulty", topLine = false, hint = if (!inMenu) "Applies from the next run." else null) {
                    Seg(listOf(Difficulty.EASY to "Easy", Difficulty.NORMAL to "Normal", Difficulty.HARD to "Hard"), s.difficulty, a.setDifficulty)
                }
                SettingRow("Mode", hint = if (!inMenu) "Applies from the next run." else null) {
                    Seg(listOf(GameMode.CLASSIC to "Classic", GameMode.SURVIVE to "Survive", GameMode.FREEROAM to "Freeroam"), s.mode, a.setMode)
                }
                SettingRow("Forest") { Seg(listOf(ForestMood.NIGHT to "Night", ForestMood.DAY to "Day", ForestMood.AUTO to "Auto"), s.mood, a.setMood) }
                SettingRow("Controls") { Seg(listOf(false to "Tilt", true to "Touch"), s.touch, a.setTouch) }
                SettingRow("Orientation") { Seg(listOf(false to "Landscape", true to "Any"), s.anyOrientation, a.setAnyOrientation) }
                SettingRow("Audio") { OnOff(s.sound, a.setSound) }
                SettingRow("Haptics") { OnOff(s.haptics, a.setHaptics) }
                UpdateRow(updates)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, hint: String? = null, topLine: Boolean = true, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(BorderStroke(0.dp, Color.Transparent)).padding(vertical = 7.dp)) {
        if (topLine) Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextC, fontSize = 13.sp)
            control()
        }
        if (hint != null) Text(hint, color = Muted.copy(alpha = .8f), fontSize = 10.sp)
    }
}

@Composable
private fun <T> Seg(items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(1.dp, Edge, shape)) {
        for ((value, label) in items) {
            val on = value == selected
            Box(
                Modifier.heightIn(min = 34.dp).background(if (on) Color(0x1FFFFFFF) else Color.Transparent)
                    .clickable { onPick(value) }.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) TextC else Muted, fontSize = 11.sp, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.widthIn(min = 52.dp).heightIn(min = 34.dp).alpha(if (enabled) 1f else .5f).clip(shape)
            .background(if (on) Lit else Color.Transparent)
            .border(1.dp, if (on) Color(0x59FFFFFF) else Edge, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) TextC else Muted, fontSize = 12.sp) }
}

@Composable
private fun OnOff(value: Boolean, onChange: (Boolean) -> Unit) = Pill(if (value) "On" else "Off", value) { onChange(!value) }

// ---- updates (the in-app OTA, same flow as the web build) ----------------------------------------

@Composable
private fun UpdateBanner(updates: UpdateController) {
    val pending by updates.pending.collectAsState()
    val event by updates.event.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    LaunchedEffect(event) { event?.let { if (it.state != UpdateState.PROGRESS) downloading = false } }
    val info = pending ?: return
    if (dismissed) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (downloading) (event?.let { describeUpdateEvent(it) } ?: "Downloading…") else (event?.let { describeUpdateEvent(it) } ?: "New update available!"),
                color = TextC, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false),
            )
            if (!downloading) {
                Pill("Update", on = true) { updates.clearEvent(); downloading = true; updates.download(info.apkUrl) }
                Pill("Later", on = false) { dismissed = true }
            }
        }
    }
}

@Composable
private fun UpdateRow(updates: UpdateController) {
    val scope = rememberCoroutineScope()
    val event by updates.event.collectAsState()
    var state by remember { mutableStateOf("idle") }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        if (e.state == UpdateState.ERROR) state = "error" else if (e.state != UpdateState.PROGRESS) state = "idle"
    }
    val detail = event?.let { describeUpdateEvent(it) }
    SettingRow("Update") {
        val f = found
        if (f != null && state != "downloading") Pill("Download", on = true) { state = "downloading"; updates.clearEvent(); updates.download(f.apkUrl) }
        else Pill("Check", on = false, enabled = state != "checking" && state != "downloading") {
            state = "checking"; found = null; updates.clearEvent()
            scope.launch {
                try { val u = updates.check(); if (u != null) { found = u; state = "idle" } else state = "latest" }
                catch (_: Exception) { state = "error" }
            }
        }
    }
    val line = when {
        found != null && state != "downloading" -> "New update available (build ${found!!.build})."
        (state == "error" || state == "idle") && detail != null -> detail
        state == "checking" -> "Checking…"
        state == "latest" -> "Latest version installed."
        state == "error" -> "Could not reach updates."
        state == "downloading" -> detail ?: "Downloading…"
        else -> null
    }
    if (line != null) Text(line, color = Muted.copy(alpha = .8f), fontSize = 10.sp)
}
