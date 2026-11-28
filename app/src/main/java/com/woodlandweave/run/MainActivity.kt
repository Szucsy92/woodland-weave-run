package com.woodlandweave.run

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.woodlandweave.run.audio.ForestAudio
import com.woodlandweave.run.audio.Haptics
import com.woodlandweave.run.data.SettingsRepo
import com.woodlandweave.run.gl.GameView
import com.woodlandweave.run.input.TiltSensor
import com.woodlandweave.run.ui.Actions
import com.woodlandweave.run.ui.ForestApp
import com.woodlandweave.run.update.UpdateController
import kotlinx.coroutines.launch

/** Single activity: owns the engine, the GL surface, the sensors and the system-level behaviour. */
class MainActivity : ComponentActivity() {
    private lateinit var repo: SettingsRepo
    private lateinit var audio: ForestAudio
    private lateinit var engine: GameEngine
    private lateinit var gameView: GameView
    private lateinit var tilt: TiltSensor
    private lateinit var updates: UpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        repo = SettingsRepo(applicationContext)
        audio = ForestAudio(applicationContext).also { it.start() }
        engine = GameEngine(applicationContext, lifecycleScope, repo, audio, Haptics(applicationContext))
        gameView = GameView(this, engine)
        updates = UpdateController(this, lifecycleScope)
        tilt = TiltSensor(this, rotation = {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }, onSample = engine::onTiltSample)

        lifecycleScope.launch {
            repo.flow.collect { s ->
                engine.applySettings(s)
                requestedOrientation = if (s.anyOrientation) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        val actions = Actions(
            setSound = { v -> lifecycleScope.launch { repo.setSound(v) } },
            setHaptics = { v -> lifecycleScope.launch { repo.setHaptics(v) } },
            setTouch = { v -> lifecycleScope.launch { repo.setTouch(v) } },
            setAnyOrientation = { v -> lifecycleScope.launch { repo.setAnyOrientation(v) } },
            setDifficulty = { v -> lifecycleScope.launch { repo.setDifficulty(v) } },
            setMode = { v -> lifecycleScope.launch { repo.setMode(v) } },
            setMood = { v -> lifecycleScope.launch { repo.setMood(v) } },
            setChristmas = { v -> lifecycleScope.launch { repo.setChristmas(v) } },
            setTimeOfDay = { v -> lifecycleScope.launch { repo.setTimeOfDay(v) } },
            setQuality = { v -> lifecycleScope.launch { repo.setQuality(v) } },
            setFx = { k, v -> lifecycleScope.launch { repo.setFx(k, v) } },
            setDev = { k, v -> lifecycleScope.launch { repo.setDev(k, v) } },
            audioStatus = { audio.status() },
            finish = { finish() },
        )
        setContent { ForestApp(engine, gameView, actions, updates) }
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        gameView.onResume()
        audio.onForeground()
        tilt.start()
        engine.recenterTilt()
        // Back from the "install unknown apps" screen, and a quiet look for a newer release.
        updates.resumePending()
        updates.lookQuietly()
    }

    override fun onPause() {
        // Backgrounding mid-run eases into the pause state rather than letting the forest run on.
        engine.pause()
        tilt.stop()
        audio.onBackground()
        gameView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        // Always: a destroyed (even if not finishing) activity gets a new ForestAudio in onCreate, so this one must not outlive it.
        audio.release()
        super.onDestroy()
    }
}
