package com.woodlandweave.run.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.woodlandweave.run.sim.Difficulty
import com.woodlandweave.run.sim.ForestMood
import com.woodlandweave.run.sim.GameMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

data class Settings(
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val touch: Boolean = false,
    val anyOrientation: Boolean = false,
    val difficulty: Difficulty = Difficulty.EASY,
    val mode: GameMode = GameMode.CLASSIC,
    val mood: ForestMood = ForestMood.NIGHT,
    val best: Int = 0,
    val last: Int = 0,
    val secrets: Set<String> = emptySet(),
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
        val best = intPreferencesKey("best")
        val last = intPreferencesKey("last")
        val secrets = stringSetPreferencesKey("secrets")
    }

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
            mood = p.enumOf(K.mood, ForestMood.NIGHT),
            best = p[K.best] ?: 0,
            last = p[K.last] ?: 0,
            secrets = p[K.secrets] ?: emptySet(),
        )
    }

    suspend fun setSound(v: Boolean) = context.store.edit { it[K.sound] = v }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[K.haptics] = v }
    suspend fun setTouch(v: Boolean) = context.store.edit { it[K.touch] = v }
    suspend fun setAnyOrientation(v: Boolean) = context.store.edit { it[K.orient] = v }
    suspend fun setDifficulty(v: Difficulty) = context.store.edit { it[K.difficulty] = v.name }
    suspend fun setMode(v: GameMode) = context.store.edit { it[K.mode] = v.name }
    suspend fun setMood(v: ForestMood) = context.store.edit { it[K.mood] = v.name }
    suspend fun setSecrets(v: Set<String>) = context.store.edit { it[K.secrets] = v }
    suspend fun recordScore(score: Int) = context.store.edit {
        it[K.last] = score
        it[K.best] = max(it[K.best] ?: 0, score)
    }
}
