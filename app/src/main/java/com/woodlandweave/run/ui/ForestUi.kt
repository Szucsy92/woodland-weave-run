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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.woodlandweave.run.gl.GameView
import com.woodlandweave.run.sim.*
import com.woodlandweave.run.update.UpdateController
import com.woodlandweave.run.update.UpdateInfo
import com.woodlandweave.run.update.UpdateState
import com.woodlandweave.run.update.describeUpdateEvent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

// The forest palette and glass look from styles.css.
private val TextC = Color(0xFFEFEFF3)
private val Muted = Color(0xFFBDBDC9)
private val GlassC = Color(0x6B3F3F4A)
private val Edge = Color(0x33DFDFEB)
private val Lit = Color(0x1AFFFFFF)

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
        Box(
            Modifier
                .fillMaxSize()
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

// ---- glass pieces ------------------------------------------------------------------------------

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .widthIn(max = 356.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassC)
            .border(1.dp, Edge, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    Text(text, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)
}

@Composable
private fun IconBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun Hud(ui: UiState) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 4.dp)) {
        Row(Modifier.align(Alignment.TopCenter), verticalAlignment = Alignment.Bottom) {
            Text("${ui.distance}", color = TextC, fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
            Text("m", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        ui.secretCount?.let {
            Text("secrets $it", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        }
    }
}

@Composable
private fun PauseButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 12.dp, top = 6.dp)) {
        Box(
            Modifier.align(Alignment.TopStart).size(40.dp).alpha(.45f).clip(RoundedCornerShape(10.dp)).background(GlassC)
                .border(1.dp, Edge, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { Box(Modifier.size(width = 4.dp, height = 15.dp).clip(RoundedCornerShape(1.dp)).background(TextC)) }
            }
        }
    }
}

/** The frame that glows inward from the screen outline plus a faint colour veil, as long as a power-up lasts. */
@Composable
private fun PowerFrame(type: PowerUpType, active: Boolean) {
    val c = when (type) { PowerUpType.FREEZER -> Color(0xFF2B6CFF); PowerUpType.GHOST -> Color(0xFF2EE88A); PowerUpType.SHOCKWAVE -> Color(0xFFB04DFF) }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = .55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
    )
    val fade by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 600 else 1100), label = "fade")
    Box(
        Modifier.fillMaxSize().alpha(fade).drawBehind {
            drawRect(c.copy(alpha = .10f))
            val r = hypot(size.width, size.height) / 2f
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent, .62f to Color.Transparent, 1f to c.copy(alpha = .38f * pulse),
                    center = Offset(size.width / 2f, size.height / 2f), radius = r,
                ),
            )
        },
    )
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
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                SettingRow("Difficulty", hint = if (!inMenu) "Applies from the next run." else null) {
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
private fun SettingRow(label: String, hint: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(BorderStroke(0.dp, Color.Transparent)).padding(vertical = 7.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
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
