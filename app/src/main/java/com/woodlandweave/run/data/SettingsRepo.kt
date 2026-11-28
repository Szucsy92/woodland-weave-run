package com.woodlandweave.run.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.woodlandweave.run.sim.ChristmasMode
import com.woodlandweave.run.sim.Difficulty
import com.woodlandweave.run.gl.FX_NEUTRAL
import com.woodlandweave.run.gl.FxTuning
import com.woodlandweave.run.gl.Quality
import com.woodlandweave.run.sim.ForestMood
import com.woodlandweave.run.sim.GameMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

/** Developer tab: how often each thing occurs, in percent of the built-in rate (100 = as designed, 0 = never). */
data class DevTuning(
    val powerUps: Float = 100f,
    val dayGates: Float = 100f,
    val surviveGates: Float = 100f,
    val blackHoles: Float = 100f,
    val secrets: Float = 100f,
)

data class Settings(
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val touch: Boolean = false,
    val anyOrientation: Boolean = false,
    val difficulty: Difficulty = Difficulty.EASY,
    val mode: GameMode = GameMode.CLASSIC,
    val mood: ForestMood = ForestMood.MANUAL,
    val christmas: ChristmasMode = ChristmasMode.AUTO,
    /** Time of day for the manual mode: 0 night, .25 dawn, .5 day, .75 dusk, 1 night. */
    val timeOfDay: Float = 0f,
    val quality: Quality = Quality.LOW,
    val fx: FxTuning = FxTuning(),
    val dev: DevTuning = DevTuning(),
    val best: Int = 0,
    val last: Int = 0,
    /** The most relics found in a single run (the found relics themselves reset every run). */
    val bestSecrets: Int = 0,
)

private val Context.store by preferencesDataStore("forest")

/** Replaces the ten localStorage keys of the web version. */
class SettingsRepo(private val context: Context) {
    private object K {
        val sound = booleanPreferencesKey("sound")
        val haptics = booleanPreferencesKey("haptics")
        val touch = booleanPreferencesKey("touch")
        val orient = booleanPreferencesKey("any_orientation")
        val difficulty = stringPreferencesKey("difficulty")
        val mode = stringPreferencesKey("mode")
        val mood = stringPreferencesKey("mood")
        val christmas = stringPreferencesKey("christmas")
        val tod = floatPreferencesKey("time_of_day")
        val quality = stringPreferencesKey("quality")
        val best = intPreferencesKey("best")
        val last = intPreferencesKey("last")
        val bestSecrets = intPreferencesKey("best_secrets")
    }

    private fun fxKey(name: String) = floatPreferencesKey("fx_$name")
    private fun devKey(name: String) = floatPreferencesKey("dev_$name")

    private inline fun <reified E : Enum<E>> Preferences.enumOf(
        key: Preferences.Key<String>, default: E,
    ): E = this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    val flow: Flow<Settings> = context.store.data.map { p ->
        Settings(
            sound = p[K.sound] ?: true,
            haptics = p[K.haptics] ?: true,
            touch = p[K.touch] ?: false,
            anyOrientation = p[K.orient] ?: false,
            difficulty = p.enumOf(K.difficulty, Difficulty.EASY),
            mode = p.enumOf(K.mode, GameMode.CLASSIC),
            // Older installs stored NIGHT / DAY / AUTO: NIGHT and DAY become a fixed time of day.
            mood = if (p[K.mood] == "AUTO") ForestMood.AUTO else ForestMood.MANUAL,
            christmas = p.enumOf(K.christmas, ChristmasMode.AUTO),
            timeOfDay = p[K.tod] ?: (if (p[K.mood] == "DAY") .5f else 0f),
            quality = Quality.HIGH,   // the only tier: the full effect set (Low / Medium were removed; the stored value is ignored)
            fx = FxTuning(
                bloom = p[fxKey("bloom")] ?: FX_NEUTRAL, tone = p[fxKey("tone")] ?: FX_NEUTRAL, shadows = p[fxKey("shadows")] ?: FX_NEUTRAL,
                ao = 0f, sharp = 0f, shafts = p[fxKey("shafts")] ?: FX_NEUTRAL, dof = p[fxKey("dof")] ?: FX_NEUTRAL, motion = p[fxKey("motion")] ?: FX_NEUTRAL,
            ),
            dev = DevTuning(
                powerUps = p[devKey("powerups")] ?: 100f, dayGates = p[devKey("daygates")] ?: 100f, surviveGates = p[devKey("survivegates")] ?: 100f,
                blackHoles = p[devKey("blackholes")] ?: 100f, secrets = p[devKey("secrets")] ?: 100f,
            ),
            best = p[K.best] ?: 0,
            last = p[K.last] ?: 0,
            bestSecrets = p[K.bestSecrets] ?: 0,
        )
    }

    suspend fun setSound(v: Boolean) = context.store.edit { it[K.sound] = v }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[K.haptics] = v }
    suspend fun setTouch(v: Boolean) = context.store.edit { it[K.touch] = v }
    suspend fun setAnyOrientation(v: Boolean) = context.store.edit { it[K.orient] = v }
    suspend fun setDifficulty(v: Difficulty) = context.store.edit { it[K.difficulty] = v.name }
    suspend fun setMode(v: GameMode) = context.store.edit { it[K.mode] = v.name }
    suspend fun setMood(v: ForestMood) = context.store.edit { it[K.mood] = v.name }
    suspend fun setChristmas(v: ChristmasMode) = context.store.edit { it[K.christmas] = v.name }
    /** Dragging the slider takes over from Auto. */
    suspend fun setTimeOfDay(v: Float) = context.store.edit { it[K.tod] = v.coerceIn(0f, 1f); it[K.mood] = ForestMood.MANUAL.name }
    suspend fun setQuality(v: Quality) = context.store.edit { it[K.quality] = v.name }
    suspend fun setFx(name: String, v: Float) = context.store.edit { it[fxKey(name)] = v }
    suspend fun setDev(name: String, v: Float) = context.store.edit { it[devKey(name)] = v.coerceIn(0f, 300f) }
    suspend fun setBestSecrets(v: Int) = context.store.edit { it[K.bestSecrets] = max(it[K.bestSecrets] ?: 0, v) }
    suspend fun recordScore(score: Int) = context.store.edit {
        it[K.last] = score
        it[K.best] = max(it[K.best] ?: 0, score)
    }
}
