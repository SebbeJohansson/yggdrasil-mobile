package io.github.sebbejohansson.yggdrasil.update

import io.github.sebbejohansson.yggdrasil.parts.Parts
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebbejohansson.yggdrasil.BuildConfig
import io.github.sebbejohansson.yggdrasil.R
import io.github.sebbejohansson.yggdrasil.YggApp
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.model.ModelDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    /** A debug build: it's updated from Android Studio, never from releases. */
    data object DevBuild : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val bytes: Long, val total: Long) : UpdateState
    /** Downloaded and checked: ready to install. */
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

class UpdateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Install new releases by themselves, at a quiet moment. */
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit { putBoolean("auto", v) }

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(v) = prefs.edit { putLong("last_check", v) }

    /** The last version you were told about, so each one is told once. */
    var toldAbout: String?
        get() = prefs.getString("told_about", null)
        set(v) = prefs.edit { putString("told_about", v) }

    /** The version that was installed when the app last ran, to say "updated" once. */
    var lastRunVersion: String?
        get() = prefs.getString("last_run_version", null)
        set(v) = prefs.edit { putString("last_run_version", v) }
}

/**
 * Keeps Ygg up to date from its GitHub releases: looks every few hours,
 * downloads a newer APK, checks its SHA-256 and that it's signed with the
 * same key as this install, then installs it.
 *
 * Android 12+ lets an app update itself without asking, once you've allowed
 * Ygg to install apps. With *Install automatically* on, that happens when
 * the screen is off (or the phone is idle), never under your fingers and
 * never while a model is downloading. Otherwise Hermóðr tells you it's ready.
 */
class Updater(
    private val context: Context,
    val settings: UpdateSettings,
    private val hermodr: HermodrService,
    /** The GitHub token for a private repo; none for a public one. */
    private val vault: TokenVault,
    private val current: String = BuildConfig.VERSION_NAME,
    private val enabled: Boolean = !BuildConfig.DEBUG,
) {
    private val _state = MutableStateFlow<UpdateState>(if (enabled) UpdateState.Idle else UpdateState.DevBuild)
    val state: StateFlow<UpdateState> = _state
    private val lock = Mutex()
    private val dir = File(context.filesDir, "updates")

    val installed: String get() = current

    /** Whether you've allowed Ygg to install apps (Settings → Install unknown apps). */
    fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's page to allow it. */
    fun allowIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** Whether a GitHub token is kept. The token itself never leaves this class. */
    val hasToken: Boolean get() = vault.get() != null

    /** Keeps a pasted token; false when it doesn't look like one. */
    fun setToken(text: String): Boolean {
        val token = Updates.tokenShape(text) ?: return false
        vault.set(token)
        return true
    }

    fun removeToken() = vault.set(null)

    private class Refused(val code: Int) : IOException("HTTP $code")

    /**
     * Opens [url], following redirects by hand: the token goes to GitHub's API
     * only, never to the storage host a download redirects to.
     */
    private fun open(url: String, accept: String): HttpURLConnection {
        val token = vault.get()
        var at = URL(url)
        repeat(5) {
            val conn = (at.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                // GitHub wants a User-Agent; a generic one, nothing about you.
                setRequestProperty("User-Agent", "yggdrasil-mobile-updater")
                setRequestProperty("Accept", accept)
                if (token != null && at.host == "api.github.com") {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: throw IOException("a redirect without a Location")
                conn.disconnect()
                at = URL(at, next)
                if (at.protocol != "https") throw IOException("a redirect away from https")
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw Refused(code)
            }
            return conn
        }
        throw IOException("too many redirects")
    }

    private fun readText(url: String, accept: String): String {
        val conn = open(url, accept)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun why(e: Exception): String = if (e is Refused) Updates.refusal(e.code, hasToken) else e.message ?: e.javaClass.simpleName

    private fun fileFor(r: Release) = File(dir, "yggdrasil-${r.version}.apk")

    /** Looks for a newer release. Null when there's none (or this is a dev build). */
    suspend fun check(): Release? = lock.withLock {
        if (!enabled) return null
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { Updates.parse(JSONObject(readText(Updates.LATEST_URL, GITHUB_JSON))) }
            settings.lastCheck = System.currentTimeMillis()
            if (release == null || !Updates.isNewer(release, current)) {
                withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
                _state.value = UpdateState.UpToDate(settings.lastCheck)
                null
            } else {
                val file = fileFor(release)
                _state.value = if (file.isFile && file.length() == release.apkSize) UpdateState.Ready(release, file) else UpdateState.Available(release)
                release
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = UpdateState.Failed("Couldn't look for updates. ${why(e)}")
            null
        }
    }

    /** Downloads and checks the release's APK. */
    suspend fun download(release: Release): File? = lock.withLock {
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                dir.listFiles()?.filter { it.name != fileFor(release).name && it.name != fileFor(release).name + ".part" }?.forEach { it.delete() }
                val file = fileFor(release)
                if (!(file.isFile && file.length() == release.apkSize)) {
                    val part = File(dir, file.name + ".part")
                    _state.value = UpdateState.Downloading(release, 0, release.apkSize)
                    val conn = open(release.apkUrl, BINARY)
                    try {
                        var have = 0L
                        conn.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                var lastEmit = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    have += n
                                    if (have - lastEmit >= 512 * 1024) {
                                        lastEmit = have
                                        _state.value = UpdateState.Downloading(release, have, release.apkSize)
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    release.shaUrl?.let { url ->
                        val want = Updates.shaFrom(readText(url, BINARY)) ?: throw IOException("the release's SHA-256 file can't be read")
                        if (sha256(part) != want) {
                            part.delete()
                            throw IOException("the download doesn't match its SHA-256")
                        }
                    }
                    if (!part.renameTo(file)) throw IOException("couldn't move the download into place")
                }
                if (!sameSigner(file)) {
                    file.delete()
                    throw IOException("it isn't signed with this install's key")
                }
                _state.value = UpdateState.Ready(release, file)
                file
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = UpdateState.Failed("Couldn't download ${release.version}. ${why(e)}", release)
            null
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Android refuses an update signed with another key anyway; this says so before it asks you. */
    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (info.packageName != context.packageName) return false
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        return signers.isNotEmpty() && signers.all { pm.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509) }
    }

    /**
     * Hands the APK to Android's installer. Without asking where Android
     * allows it; else Android shows its own "update this app?" page.
     */
    fun install(release: Release, file: File) {
        if (!allowed()) {
            _state.value = UpdateState.Failed("Allow Ygg to install updates first.", release)
            return
        }
        _state.value = UpdateState.Installing(release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    context, id, Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't install ${release.version}: ${e.message ?: e.javaClass.simpleName}", release)
        }
    }

    /** What the installer said, from [UpdateReceiver]. */
    internal fun installerSaid(status: Int, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = release?.let { r -> fileFor(r).takeIf { it.isFile }?.let { UpdateState.Ready(r, it) } } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed("Android didn't install it: ${message ?: "status $status"}", release)
        }
    }

    /**
     * The background round: look, download, then install now if the screen is
     * off, later when the phone is idle, or tell you it's ready.
     */
    suspend fun round() {
        if (!enabled) return
        val release = check() ?: return
        val file = download(release) ?: return
        if (settings.auto && allowed()) {
            if (quietNow()) install(release, file) else UpdateInstallWorker.schedule(context)
        } else if (settings.toldAbout != release.version.toString()) {
            settings.toldAbout = release.version.toString()
            hermodr.send(
                MessageRequest(
                    "updates", "Ygg ${release.version} is ready",
                    "Install it under Settings → Updates." + release.summary(3).let { if (it.isBlank()) "" else "\n$it" },
                    priority = Priority.LOW, tag = "update",
                ),
            )
        }
    }

    /** A moment the update won't get in your way: screen off and no model downloading. */
    fun quietNow(): Boolean =
        !context.getSystemService(PowerManager::class.java).isInteractive && !ModelDownloadWorker.anyRunning(context)

    /** Once after an update: what's new, in Messages. */
    suspend fun sayIfUpdated() {
        val last = settings.lastRunVersion
        settings.lastRunVersion = current
        if (!enabled || last == null || last == current) return
        hermodr.send(MessageRequest("updates", "Ygg updated to $current", "From $last.", priority = Priority.LOW, push = false, tag = "update"))
    }
}

private const val GITHUB_JSON = "application/vnd.github+json"

/** Asks the API for a release asset's bytes rather than its description. */
private const val BINARY = "application/octet-stream"

/** Looks for a release every few hours, on any network. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as YggApp).container
        c.loops.run(Parts.UPDATES_CHECK.first, Parts.UPDATES_CHECK.second) {
            c.updater.round()
            (c.updater.state.value as? UpdateState.Failed)?.let { error(it.message) }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            if (BuildConfig.DEBUG) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "update-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(Updates.CHECK_EVERY_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

/** Installs a downloaded update when the phone is idle (usually at night). */
class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = (applicationContext as YggApp).container.updater
        val ready = updater.state.value as? UpdateState.Ready
            ?: updater.check()?.let { updater.download(it) }?.let { updater.state.value as? UpdateState.Ready }
            ?: return Result.success()
        if (!updater.settings.auto || !updater.allowed()) return Result.success()
        if (!updater.quietNow()) return Result.retry()
        updater.install(ready.release, ready.file)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "update-install",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateInstallWorker>()
                    .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
                    .build(),
            )
        }
    }
}

/**
 * The installer's answers. When Android wants you to confirm, it opens its
 * page if Ygg is on screen, or leaves a notification to tap if not (an app
 * in the background may not open pages).
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val app = context.applicationContext as YggApp
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        app.container.updater.installerSaid(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.onScreen) {
            runCatching { context.startActivity(confirm) }
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Ygg update ready")
                .setContentText("Tap to install it.")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
    }

    companion object {
        const val ACTION_STATUS = "io.github.sebbejohansson.yggdrasil.UPDATE_STATUS"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 7301
    }
}
