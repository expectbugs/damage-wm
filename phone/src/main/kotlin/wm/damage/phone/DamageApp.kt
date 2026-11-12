package wm.damage.phone

import android.app.Application
import wm.damage.core.util.UncaughtRecord

/**
 * The app's `Application` (APK 0.64, `HANDOFF.md` §75.1): the one place that runs before every
 * component of the process — `ShellService`, `MainActivity`, `BootReceiver` and the
 * notification listener the system binds by itself — so the uncaught-error record is installed
 * whichever of them brought the process up. Android's own default handler is the "previous" one:
 * it still ends the process, after the record is on disk.
 *
 * The manifest must name this class (`android:name=".DamageApp"` on `<application>`). An APK
 * without the attribute assembles and runs with no record; `tools/check_apk_wiring.py` reads the
 * merged manifest after `:phone:assembleDebug`, and `ShellService.onCreate` says at every start
 * whether the handler is in place.
 */
class DamageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // R2 (review 1b RF-9; insurance — no throw is known here): a fault in the install would repeat
        // at every launch with nothing readable (only logcat, which nothing on this setup reads). Its
        // text is kept for `ShellService.onCreate`, whose "NOT installed" ERROR line carries it to the
        // phone's "Damage errors" channel. An out-of-memory or other VM error is thrown on.
        try {
            UncaughtRecord.install(filesDir.toPath(), "apk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            installFailure = "${t.javaClass.name}: ${t.message}"
        }
    }

    companion object {
        /** Why `UncaughtRecord.install` failed in this process, or null (it ran, or it never did). */
        @Volatile var installFailure: String? = null
            private set
    }
}
