package wm.damage.phone.music

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import wm.damage.core.util.Log

/**
 * The phone's notification listener for Music (`MUSIC.md` §3.10, §7, verdicts
 * 3 / 14 / 20 / 25). It exists for two jobs, both of which need the one-time
 * "notification access" grant the user gives in Settings:
 *
 *  1. **Spotify's media session.** `MediaSessionManager.getActiveSessions()`
 *     only answers a component that holds this grant, so [SpotifyRemote] passes
 *     [component] to it. Nothing else about Spotify happens here.
 *  2. **The hearing-safety notice** (verdict 14). Android's sound-dose feature
 *     lowers the media stream after a long stretch at a high level and posts a
 *     system notification. On Adam's phone there is no confirmation dialog —
 *     volume-up simply restores it — and the phone is out of reach on the cart,
 *     so the player re-sets the stream to the held level. The high-confidence
 *     signal that the drop was the limiter (rather than a run of volume-down
 *     taps) is this notification, and [onVolumeLowered] hands its text to the
 *     player's HoldVolume logic.
 *
 * 🔴 **The matcher is unverified.** Nothing on the device could be checked the
 * night this was written, so [rules] is a plain data list of (package prefix,
 * keywords) that can be corrected from one real sighting — and every
 * notification from the system packages in [rules] is logged at info with its
 * title and text so the first real trigger at work is captured. Notifications
 * from any other app are never logged and never leave this class: the package
 * gate is checked before the text is read.
 *
 * Lifecycle facts (AOSP `NotificationListenerService` docs; the same shape
 * G2CC's `service/NotifyListener.kt` has run daily on this phone):
 *  - the SYSTEM owns the binding — never start or bind this service;
 *  - callbacks arrive on the main thread and an exception thrown out of one is
 *    what produces the "granted but never reconnects" state, so every callback
 *    body here is wrapped and only logs;
 *  - `onListenerDisconnected` → `requestRebind` is the first-line recovery;
 *    [rebindIfStuck] re-registers the component when it stays unconnected.
 */
class MusicListener : NotificationListenerService() {

    /**
     * One matcher row: the package prefix a notice may come from, the words
     * that must ALL appear in "title text" (lowercased), and the words of
     * which at least one must appear. Adjust after the first real sighting —
     * the log line in [inspect] carries the exact package, title and text.
     */
    data class NoticeRule(
        val pkgPrefix: String,
        val all: List<String> = listOf("volume"),
        val any: List<String> = listOf("hearing", "protect", "lower", "safe", "loud"),
    )

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "notification listener disconnected — Spotify control and the volume notice are unavailable until it rebinds")
        try {
            if (granted(this)) requestRebind(component(this))
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // NEVER let anything escape into the system's binder/main callback.
        try {
            inspect(sbn ?: return)
        } catch (e: Exception) {
            Log.e(TAG, "notification inspection failed", e)
        } catch (e: LinkageError) {
            Log.e(TAG, "notification inspection failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Read title/text ONLY for the packages [rules] names; match, log, deliver. */
    private fun inspect(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val watching = rules.filter { pkg.startsWith(it.pkgPrefix) }
        if (watching.isEmpty()) return                 // another app's content is never read
        val extras = sbn.notification?.extras ?: return
        val title = str(extras.getCharSequence(Notification.EXTRA_TITLE))
        val text = str(extras.getCharSequence(Notification.EXTRA_TEXT))
            .ifBlank { str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)) }
        val hay = "$title $text".lowercase()
        val hit = watching.firstOrNull { r ->
            r.all.all { hay.contains(it) } && (r.any.isEmpty() || r.any.any { hay.contains(it) })
        }
        // Verdict 14's capture: every notification from the system packages is
        // logged with its wording, so the first real limiter notice at work is
        // in the log even if the keywords below miss it.
        if (hit != null || SYSTEM_LOG_PREFIXES.any { pkg.startsWith(it) }) {
            Log.i(TAG, "notification pkg=$pkg match=${hit != null} title=${q(title)} text=${q(text)}")
        }
        if (hit == null) return
        val notice = when {
            title.isBlank() -> text
            text.isBlank() -> title
            text.contains(title, ignoreCase = true) -> text
            else -> "$title — $text"
        }
        val hook = onVolumeLowered
        if (hook == null) {
            Log.w(TAG, "volume-lowered notice seen with no player listening: ${q(notice)}")
            return
        }
        Log.i(TAG, "volume-lowered notice → player: ${q(notice)}")
        try {
            hook(notice)
        } catch (e: Exception) {
            Log.e(TAG, "onVolumeLowered handler failed", e)
        }
    }

    private fun str(cs: CharSequence?): String = cs?.toString()?.trim() ?: ""

    /** Quote for the log so an empty field is visible rather than blank. */
    private fun q(s: String): String = "\"$s\""

    companion object {
        private const val TAG = "music-listener"

        /**
         * MODELED, not verified: Android's sound-dose notice is posted by
         * SystemUI on AOSP (the `csd_*` strings — "Volume lowered to a safer
         * level" / "Headphone volume has been high for longer than is
         * recommended"), and a Pixel may post it from Settings or another
         * Google system package instead. The list is deliberately inclusive
         * because the word rule ("volume" + a hearing word) is what makes a
         * match narrow.
         */
        val DEFAULT_RULES: List<NoticeRule> = listOf(
            NoticeRule("com.android.systemui"),
            NoticeRule("com.android.settings"),
            // the Google-branded settings package only — a bare `com.google.android.`
            // prefix let a text message reading "volume … safe" re-set the stream
            // and land in the log (review 2026-09-02)
            NoticeRule("com.google.android.settings"),
        )

        /**
         * Packages whose notifications are logged in FULL (title + text) even
         * when they do not match, so the first real notice is captured. Only
         * system UI / settings packages: a Google *app* (Messages, Gmail) can
         * still MATCH through [DEFAULT_RULES], but its unrelated notifications
         * are never logged.
         */
        val SYSTEM_LOG_PREFIXES: List<String> = listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.settings",
        )

        /** The live matcher. Swap it after the first real sighting. */
        @Volatile
        var rules: List<NoticeRule> = DEFAULT_RULES

        /**
         * Installed by the player (`AndroidMusicPlayer`): the hearing-safety
         * notice's text. Called on the main thread; keep the handler cheap.
         */
        @Volatile
        var onVolumeLowered: ((String) -> Unit)? = null

        /** True between `onListenerConnected` and `onListenerDisconnected`. */
        @Volatile
        var connected: Boolean = false
            private set

        /** The component `MediaSessionManager.getActiveSessions()` is asked with. */
        fun component(ctx: Context): ComponentName = ComponentName(ctx.applicationContext, MusicListener::class.java)

        /**
         * Is the one-time "notification access" grant in place?
         * `NotificationManager.isNotificationListenerAccessGranted` (API 27+)
         * is the direct answer; the `Settings.Secure` list is the fallback for
         * a device where that call is refused.
         */
        fun granted(ctx: Context): Boolean {
            val comp = component(ctx)
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm != null) return nm.isNotificationListenerAccessGranted(comp)
                Log.w(TAG, "no NotificationManager — falling back to the Settings.Secure list")
            } catch (e: Exception) {
                Log.w(TAG, "isNotificationListenerAccessGranted refused (${e.message}) — falling back to the Settings.Secure list")
            }
            return try {
                // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is not public API;
                // the key string is, and it is a ':'-separated list of flattened
                // component names.
                val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
                val want = setOf(comp.flattenToString(), comp.flattenToShortString())
                flat.split(':').any { it.trim() in want }
            } catch (e: Exception) {
                Log.e(TAG, "notification-access check failed both ways", e)
                false
            }
        }

        /**
         * The Settings screen where the grant is given. The no-argument form is
         * the plain list; [settingsIntent] with a context deep-links to this
         * app's own row (API 30+, and minSdk is 31). The caller adds
         * `FLAG_ACTIVITY_NEW_TASK` when launching from a non-activity context.
         */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        fun settingsIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())

        /**
         * Granted but never connected (the state a fault inside a callback can
         * leave behind): ask for a rebind, and if it is still not connected
         * after a paced re-check, toggle the component so the system rebuilds
         * its service record. The toggle does NOT revoke the user's grant.
         * Call it from an activity's `onStart` — it is a no-op when connected
         * or when the grant is missing. (Pacing, not a timeout: nothing waits
         * on it and no I/O is bounded by it.)
         */
        fun rebindIfStuck(ctx: Context) {
            val app = ctx.applicationContext
            if (connected || !granted(app)) return
            Log.w(TAG, "notification access granted but the listener is not connected — requesting a rebind")
            try {
                requestRebind(component(app))
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind failed", e)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (connected || !granted(app)) return@postDelayed
                Log.w(TAG, "still not connected after the rebind — toggling the component (the grant is untouched)")
                try {
                    val cn = component(app)
                    app.packageManager.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    app.packageManager.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    requestRebind(cn)
                } catch (e: Exception) {
                    Log.e(TAG, "component toggle failed", e)
                }
            }, REBIND_RECHECK_MS)
        }

        /** How long after a rebind request the toggle is tried (paced re-check). */
        private const val REBIND_RECHECK_MS = 10_000L
    }
}
