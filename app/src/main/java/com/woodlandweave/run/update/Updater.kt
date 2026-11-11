package com.woodlandweave.run.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

// ---- pure logic (unit-tested) ----------------------------------------------------------------

const val RELEASES_API = "https://api.github.com/repos/Szucsy92/woodland-weave-run/releases/latest"
private const val RELEASE_PATH_PREFIX = "/Szucsy92/woodland-weave-run/releases/download/"

data class Asset(val name: String, val url: String)
data class Release(val tag: String?, val name: String?, val assets: List<Asset>)
data class UpdateInfo(val build: Int, val name: String, val apkUrl: String)

/** Tags look like v2.0.0-2026-11-10_21-30-build12; the build number is the Android versionCode. */
fun buildFromTag(tag: String): Int? = Regex("build(\\d+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()

fun pickUpdate(release: Release, current: Int): UpdateInfo? {
    val build = buildFromTag(release.tag ?: "") ?: return null
    val apk = release.assets.firstOrNull { it.name.lowercase().endsWith(".apk") } ?: return null
    if (build <= current) return null
    return UpdateInfo(build, release.name ?: release.tag ?: "", apk.url)
}

fun parseRelease(json: String): Release {
    val o = JSONObject(json)
    val arr = o.optJSONArray("assets")
    val assets = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            add(Asset(a.optString("name"), a.optString("browser_download_url")))
        }
    }
    return Release(o.optString("tag_name").ifEmpty { null }, o.optString("name").ifEmpty { null }, assets)
}

/** Only this game's own GitHub release assets, over https, may be downloaded. */
fun isTrustedUpdateUrl(url: String?): Boolean {
    if (url == null) return false
    return try {
        val u = URL(url)
        u.protocol == "https" && u.host == "github.com" && u.path.startsWith(RELEASE_PATH_PREFIX)
    } catch (_: Exception) { false }
}

enum class UpdateState { PROGRESS, PERMISSION, INSTALLING, ERROR }
data class UpdateEvent(val state: UpdateState, val percent: Int = 0, val message: String = "")

fun describeUpdateEvent(e: UpdateEvent) = when (e.state) {
    UpdateState.PROGRESS -> "Downloading… ${e.percent}%"
    UpdateState.PERMISSION -> "Allow installing from this app, then come back."
    UpdateState.INSTALLING -> "Downloaded. Confirm the install."
    UpdateState.ERROR -> "Update failed" + if (e.message.isNotEmpty()) ": ${e.message}" else "."
}

// ---- the controller (replaces the AndroidUpdater JavaScript bridge) ---------------------------

class UpdateController(private val activity: Activity, private val scope: CoroutineScope) {
    private val _event = MutableStateFlow<UpdateEvent?>(null)
    val event: StateFlow<UpdateEvent?> = _event
    private val _pending = MutableStateFlow<UpdateInfo?>(null)
    val pending: StateFlow<UpdateInfo?> = _pending
    private val downloading = AtomicBoolean(false)
    private var pendingApk: File? = null
    private var lastLook = 0L

    fun currentBuild(): Int = try {
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0)).toInt()
    } catch (_: Exception) { 0 }

    /** Asks GitHub for the latest release. Throws on network errors so the caller can say so. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "WoodlandWeaveRun")
            conn.useCaches = false
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            if (conn.responseCode != 200) throw Exception("status ${conn.responseCode}")
            pickUpdate(parseRelease(conn.inputStream.bufferedReader().readText()), currentBuild())
        } finally { conn.disconnect() }
    }

    /** Quiet look on launch and when the app returns to the foreground, at most every ten minutes. */
    fun lookQuietly() {
        val now = System.currentTimeMillis()
        if (now - lastLook < 10 * 60 * 1000) return
        lastLook = now
        scope.launch { try { check()?.let { _pending.value = it } } catch (_: Exception) { } }
    }

    fun clearEvent() { _event.value = null }

    fun download(url: String) {
        if (!isTrustedUpdateUrl(url)) return
        if (!downloading.compareAndSet(false, true)) return
        Thread {
            try { downloadAndInstall(url) } finally { downloading.set(false) }
        }.start()
    }

    private fun notify(state: UpdateState, percent: Int = 0, message: String = "") { _event.value = UpdateEvent(state, percent, message) }

    private fun mayInstall() = activity.packageManager.canRequestPackageInstalls()

    private fun launchInstall(apk: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(install)
        notify(UpdateState.INSTALLING, 100)
    }

    /** Back from the "install unknown apps" screen: continue with the saved download. */
    fun resumePending() {
        val apk = pendingApk ?: return
        if (!mayInstall()) return
        pendingApk = null
        try { launchInstall(apk) } catch (e: Exception) { notify(UpdateState.ERROR, 0, e.message ?: "") }
    }

    private fun downloadAndInstall(url: String) {
        val dir = File(activity.cacheDir, "updates").also { it.mkdirs() }
        val part = File(dir, "update.apk.part")
        val apk = File(dir, "update.apk")
        try {
            notify(UpdateState.PROGRESS, 0)
            var current = url
            var conn: HttpURLConnection? = null
            // GitHub answers with redirects to another host; follow them by hand.
            for (hop in 0 until 8) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "WoodlandWeaveRun")
                // No transparent gzip: it hides Content-Length and with it the integrity check.
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")
                    c.disconnect()
                    if (next == null) throw Exception("redirect without target")
                    val target = URL(URL(current), next)
                    if (target.protocol != "https") throw Exception("insecure redirect")
                    current = target.toString()
                    continue
                }
                if (code != 200) { c.disconnect(); throw Exception("HTTP $code") }
                conn = c
                break
            }
            if (conn == null) throw Exception("too many redirects")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; notify(UpdateState.PROGRESS, pct) }
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && done != total) throw Exception("incomplete download")
            if (apk.exists()) apk.delete()
            if (!part.renameTo(apk)) throw Exception("cannot save file")
            if (!mayInstall()) {
                pendingApk = apk
                notify(UpdateState.PERMISSION, 100)
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.runOnUiThread { activity.startActivity(settings) }
                return
            }
            activity.runOnUiThread {
                try { launchInstall(apk) } catch (e: Exception) { notify(UpdateState.ERROR, 0, e.message ?: "") }
            }
        } catch (e: Exception) {
            part.delete()
            notify(UpdateState.ERROR, 0, e.message ?: "")
        }
    }
}
