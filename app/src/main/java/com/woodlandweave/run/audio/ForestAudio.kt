package com.woodlandweave.run.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.woodlandweave.run.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class Sting { START, END, THREAT }
enum class Cue { FREEZER, GHOST, SHOCKWAVE, RING, END, STUMBLE, GATE, HOLE, WARPOUT, SECRET }

/**
 * Native audio engine (AudioTrack only, no web audio). The forest loop streams through a
 * one-pole low-pass and gain that glide exactly like the old Web Audio chain did: dull in the
 * menu (220 Hz), open while running (4 kHz), dark once the lights die (380 Hz).
 */
class ForestAudio(private val context: Context) {
    private companion object {
        const val RATE = 44100
        const val MUFFLED_HZ = 380f
        const val MENU_HZ = 220f
        const val RUN_HZ = 4000f
    }

    @Volatile private var enabled = true
    @Volatile private var running = false
    @Volatile private var foreground = true
    @Volatile private var muffled = false
    @Volatile private var systemMuted = false
    @Volatile private var dipUntilNs = 0L
    @Volatile private var dipEndedNs = 0L
    @Volatile private var released = false

    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val stings = java.util.concurrent.ConcurrentHashMap<Sting, ShortArray>()
    private val cues = java.util.concurrent.ConcurrentHashMap<Cue, ShortArray>()
    private var loop: LoopThread? = null

    private fun canPlay() = enabled && foreground && !systemMuted && !released

    fun start() {
        refreshMute()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            loadSting(Sting.START, R.raw.run_start, 1200f)
            loadSting(Sting.END, R.raw.run_end, 900f)
            loadSting(Sting.THREAT, R.raw.threat, 0f)
            for (c in Cue.values()) cues[c] = synthesize(c)
        }, "forest-audio-load").start()
        loop = LoopThread().also { it.start() }
    }

    fun release() {
        released = true
        loop?.interrupt()
    }

    fun refreshMute() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        systemMuted = AudioOutputPolicy.shouldMute(
            am.getStreamVolume(AudioManager.STREAM_MUSIC),
            am.isStreamMute(AudioManager.STREAM_MUSIC),
            am.ringerMode,
        )
    }

    fun setMode(enabled: Boolean, running: Boolean) { this.enabled = enabled; this.running = running }
    fun setMuffled(value: Boolean) { muffled = value }
    fun onForeground() { foreground = true; refreshMute() }
    fun onBackground() { foreground = false }

    /** 500 ms dip matching the impact glitch: instantly muffled, then back. */
    fun impactDip() {
        val now = System.nanoTime()
        dipUntilNs = now + 500_000_000L
        dipEndedNs = dipUntilNs
    }

    fun play(sting: Sting) {
        val data = stings[sting] ?: return
        if (canPlay()) playPcm(data, 2)
    }

    fun cue(cue: Cue) {
        val data = cues[cue] ?: return
        if (canPlay()) playPcm(data, 1)
    }

    // ---- one-shot playback -----------------------------------------------------------------

    private fun playPcm(data: ShortArray, channels: Int) {
        try {
            val format = AudioFormat.Builder()
                .setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs).setAudioFormat(format)
                .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(data, 0, data.size)
            track.play()
            val ms = data.size / channels * 1000L / RATE + 250
            handler.postDelayed({ try { track.release() } catch (_: Exception) { } }, ms)
        } catch (_: Exception) {
            // A cue must never break a run.
        }
    }

    // ---- the looping forest ----------------------------------------------------------------

    private inner class LoopThread : Thread("forest-loop") {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcm = try { soften(readWav(R.raw.forest_loop)) } catch (_: Exception) { return }
            val format = AudioFormat.Builder()
                .setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf, 16384) * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            val frames = pcm.size / 2
            val chunkFrames = 1024
            val out = ShortArray(chunkFrames * 2)
            var pos = 0
            var gain = 0f
            var cutoff = MENU_HZ
            var lpL = 0f
            var lpR = 0f
            var playing = false
            val dt = chunkFrames.toFloat() / RATE
            try {
                while (!released && !isInterrupted) {
                    if (!canPlay()) {
                        if (playing) { track.pause(); track.flush(); playing = false; gain = 0f }
                        sleep(60)
                        continue
                    }
                    if (!playing) { track.play(); playing = true }
                    val now = System.nanoTime()
                    val dipping = now < dipUntilNs
                    val targetGain = if (running) (if (muffled) 0.26f else 0.45f) else 0.3f
                    val stateHz = if (running) (if (muffled) MUFFLED_HZ else RUN_HZ) else MENU_HZ
                    val targetHz = if (dipping) MENU_HZ else stateHz
                    val hzTc = if (dipping) 0.03f else if (now - dipEndedNs < 1_500_000_000L) 0.18f else 0.65f
                    gain += (targetGain - gain) * (1 - exp(-dt / 0.8f))
                    cutoff += (targetHz - cutoff) * (1 - exp(-dt / hzTc))
                    val a = 1f - exp(-2f * PI.toFloat() * cutoff / RATE)
                    for (i in 0 until chunkFrames) {
                        val l = pcm[pos * 2].toFloat()
                        val r = pcm[pos * 2 + 1].toFloat()
                        lpL += a * (l - lpL); lpR += a * (r - lpR)
                        out[i * 2] = (lpL * gain).toInt().coerceIn(-32768, 32767).toShort()
                        out[i * 2 + 1] = (lpR * gain).toInt().coerceIn(-32768, 32767).toShort()
                        if (++pos >= frames) pos = 0
                    }
                    track.write(out, 0, out.size)
                }
            } catch (_: InterruptedException) {
            } finally {
                try { track.stop() } catch (_: Exception) { }
                track.release()
            }
        }
    }

    // ---- decoding and synthesis ------------------------------------------------------------

    /** Minimal RIFF reader: returns interleaved 16-bit stereo at 44.1 kHz (the shipped files). */
    private fun readWav(res: Int): ShortArray {
        val bytes = context.resources.openRawResource(res).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (id == "data") {
                val n = min(size, bytes.size - p - 8) / 2
                val out = ShortArray(n)
                bb.position(p + 8)
                bb.asShortBuffer().get(out)
                return out
            }
            p += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    /** Crossfades the loop's tail into its head so the seam is inaudible. */
    private fun soften(src: ShortArray): ShortArray {
        val frames = src.size / 2
        val fade = min((RATE * 0.1).toInt(), frames shr 2)
        if (fade < 2) return src
        val length = frames - fade
        val out = src.copyOf(length * 2)
        for (k in 0 until fade) {
            val t = k.toFloat() / fade * (PI.toFloat() / 2)
            val s = sin(t); val c = kotlin.math.cos(t)
            for (ch in 0..1) {
                out[k * 2 + ch] = (src[k * 2 + ch] * s + src[(length + k) * 2 + ch] * c).toInt().toShort()
            }
        }
        return out
    }

    private fun loadSting(sting: Sting, res: Int, toneHz: Float) {
        try {
            val d = readWav(res)
            val frames = d.size / 2
            // Muffled start/end: roll the highs off, then fade out over the last 0.35 s.
            val a = if (toneHz > 0) 1f - exp(-2f * PI.toFloat() * toneHz / RATE) else 1f
            var l = 0f; var r = 0f
            val fadeFrames = (RATE * 0.35f).toInt()
            for (i in 0 until frames) {
                l += a * (d[i * 2] - l); r += a * (d[i * 2 + 1] - r)
                val g = 0.9f * (if (i > frames - fadeFrames) (frames - i).toFloat() / fadeFrames else 1f)
                d[i * 2] = (l * g).toInt().toShort(); d[i * 2 + 1] = (r * g).toInt().toShort()
            }
            stings[sting] = d
        } catch (_: Exception) { }
    }

    private class Blip(val from: Float, val to: Float, val seconds: Float, val peak: Float, val type: Int = 0, val delay: Float = 0f)

    private fun synthesize(cue: Cue): ShortArray {
        val b = when (cue) {
            Cue.FREEZER -> listOf(Blip(660f, 330f, .65f, .09f), Blip(990f, 495f, .55f, .05f, 1, .04f))
            Cue.GHOST -> listOf(Blip(220f, 440f, .75f, .08f), Blip(330f, 660f, .75f, .045f, 0, .08f))
            Cue.SHOCKWAVE -> listOf(Blip(110f, 40f, .85f, .34f), Blip(150f, 520f, .5f, .08f, 1, .05f))
            Cue.RING -> listOf(Blip(140f, 50f, .6f, .24f), Blip(210f, 110f, .4f, .05f, 1, .02f))
            Cue.END -> listOf(Blip(300f, 150f, .45f, .065f))
            Cue.STUMBLE -> listOf(Blip(95f, 52f, .2f, .15f, 1))
            Cue.GATE -> listOf(Blip(72f, 54f, .7f, .11f), Blip(144f, 108f, .6f, .05f, 0, .05f))
            Cue.HOLE -> listOf(Blip(90f, 28f, 1.6f, .3f), Blip(520f, 70f, 1.4f, .07f, 1, .05f), Blip(330f, 1200f, 1.2f, .035f, 0, .25f))
            Cue.WARPOUT -> listOf(Blip(60f, 1500f, .5f, .09f, 2), Blip(140f, 38f, 1.1f, .32f, 0, .18f))
            Cue.SECRET -> listOf(Blip(880f, 880f, .9f, .055f), Blip(1320f, 1320f, .9f, .04f, 0, .12f), Blip(1760f, 1760f, 1.1f, .03f, 0, .26f))
        }
        val total = b.maxOf { it.delay + it.seconds } + 0.06f
        val mix = FloatArray((total * RATE).toInt())
        for (bl in b) {
            val start = (bl.delay * RATE).toInt()
            val n = (bl.seconds * RATE).toInt()
            val attack = min(0.03f, bl.seconds / 4)
            var phase = 0.0
            for (i in 0 until n) {
                if (start + i >= mix.size) break
                val t = i.toFloat() / RATE
                val f = bl.from * (bl.to / bl.from).pow(t / bl.seconds)
                phase += f / RATE
                val ph = (phase % 1.0).toFloat()
                val osc = when (bl.type) {
                    1 -> 4f * kotlin.math.abs(ph - 0.5f) - 1f
                    2 -> 2f * ph - 1f
                    else -> sin(2f * PI.toFloat() * ph)
                }
                val env = if (t < attack) 0.0001f * (bl.peak / 0.0001f).pow(t / attack)
                else bl.peak * (0.0001f / bl.peak).pow((t - attack) / (bl.seconds - attack))
                mix[start + i] += osc * env
            }
        }
        return ShortArray(mix.size) { (mix[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}
