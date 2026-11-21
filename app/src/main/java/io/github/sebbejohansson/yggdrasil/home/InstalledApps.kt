package io.github.sebbejohansson.yggdrasil.home

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The apps on the phone, for the home screen. Apps in this profile come from
 * the package manager; a work profile's from LauncherApps, which badges
 * their icons.
 */
class InstalledApps(private val context: Context) {
    private val pm = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val me = Process.myUserHandle()
    private val icons = LruCache<String, ImageBitmap>(250)

    private fun serial(user: UserHandle) = if (user == me) 0L else users.getSerialNumberForUser(user)

    private fun userFor(profile: Long): UserHandle? = if (profile == 0L) me else users.getUserForSerialNumber(profile)

    /** Every app with a launcher entry, in every profile you can open, but not Ygg itself. */
    fun list(): List<AppEntry> {
        val mine = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { r ->
                val a = r.activityInfo
                AppEntry(AppEntry.keyOf(a.packageName, a.name, 0), r.loadLabel(pm).toString(), a.packageName, a.name)
            }
        val others = runCatching {
            launcherApps.profiles.filter { it != me && !users.isQuietModeEnabled(it) }.flatMap { user ->
                val s = serial(user)
                launcherApps.getActivityList(null, user).map { i ->
                    val c = i.componentName
                    AppEntry(AppEntry.keyOf(c.packageName, c.className, s), i.label.toString(), c.packageName, c.className, s)
                }
            }
        }.getOrDefault(emptyList())
        return (mine + others).filter { it.packageName != context.packageName }.distinctBy { it.key }
    }

    /** Fires when an app is installed, removed or changed. */
    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageAdded(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(p: String?, u: UserHandle?) { icons.evictAll(); trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
        }
        launcherApps.registerCallback(callback)
        awaitClose { launcherApps.unregisterCallback(callback) }
    }

    /** The app's icon at [px] pixels, cached. Call off the main thread. */
    fun icon(app: AppEntry, px: Int): ImageBitmap? = icons.get(app.key) ?: runCatching {
        val drawable = if (app.work) {
            val user = userFor(app.profile) ?: return null
            launcherApps.resolveActivity(Intent().setComponent(ComponentName(app.packageName, app.activity)), user)
                ?.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        } else {
            pm.getActivityIcon(ComponentName(app.packageName, app.activity))
        } ?: return null
        drawable.toBitmap(px, px).asImageBitmap().also { icons.put(app.key, it) }
    }.getOrNull()

    /** Opens an app; false if it's gone. */
    fun open(app: AppEntry, bounds: Rect? = null): Boolean = try {
        val component = ComponentName(app.packageName, app.activity)
        if (app.work) {
            launcherApps.startMainActivity(component, userFor(app.profile) ?: return false, bounds, null)
        } else {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    .also { it.sourceBounds = bounds },
            )
        }
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Android's page for the app: storage, permissions, force stop. */
    fun details(app: AppEntry) {
        val user = userFor(app.profile) ?: return
        runCatching { launcherApps.startAppDetailsActivity(ComponentName(app.packageName, app.activity), user, null, null) }
    }

    /** Asks Android to uninstall the app; Android confirms with you. */
    fun uninstall(app: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The pins you keep at the top of the app list. */
class HomeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val _pins = MutableStateFlow(read())
    val pins: StateFlow<List<String>> = _pins

    private fun read() = prefs.getString("pins", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun setPins(pins: List<String>) {
        prefs.edit { putString("pins", pins.joinToString("\n")) }
        _pins.value = pins
    }
}

/** Whether Ygg is the phone's home screen, and the ways to change that. */
object HomeRole {
    fun held(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java)
        return roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    /** Android's "use Ygg as home?" question, or null where it can't ask. */
    fun request(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        return if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
    }

    /** Android's default-apps page, to pick another home screen. */
    fun settings(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)
}
