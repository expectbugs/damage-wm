package wm.damage.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Auto-start after device boot or a sideload update (the G2CC BootReceiver
 * pattern, service/BootReceiver.kt): the phone driving the glasses is the
 * DEFAULT configuration, so a reboot or an APK update must not leave the pair
 * undriven until the app is opened by hand.
 *
 *  - Only when the persisted Target is GLASSES — a SIM-target phone has no
 *    radio work, and auto-starting its simulator after every reboot is noise.
 *  - Gated on the battery-optimization exemption (the G2CC 4th-pass review
 *    decision, kept): auto-starting a service the OS will Doze-stall in
 *    minutes is worse than not starting, because there is no failure surface;
 *    a revoked exemption raises a re-grant notification instead.
 *  - connectedDevice-type FGS is allowed from BOOT_COMPLETED (exercised by
 *    G2CC daily); the Bluetooth runtime grants persist across reboots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.target != Prefs.Target.GLASSES) {
            android.util.Log.i(TAG, "$action: target is ${prefs.target} — not auto-starting")
            return
        }
        val exempt = app.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(app.packageName) == true
        if (!exempt) {
            android.util.Log.w(TAG, "$action: battery-opt exemption missing — not auto-starting (Doze would stall the lease renewals); prompting")
            postRegrantPrompt(app)
            return
        }
        android.util.Log.i(TAG, "$action: auto-starting the shell service (target glasses)")
        app.startForegroundService(Intent(app, ShellService::class.java))
    }

    private fun postRegrantPrompt(context: Context) {
        // POST_NOTIFICATIONS may be denied — notify() would do nothing in
        // silence, which is the exact failure this prompt exists to prevent;
        // log loudly instead (an Activity cannot be launched from here)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            android.util.Log.w(TAG, "battery-opt exemption revoked AND notifications are off — " +
                "the shell stays down until the app is opened")
            return
        }
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_URGENT, "Damage errors", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF_ID_REGRANT, NotificationCompat.Builder(context, CHANNEL_URGENT)
                .setSmallIcon(R.drawable.ic_damage)
                .setContentTitle("Damage: battery optimization is back on")
                .setContentText("Tap to re-grant the exemption — without it Doze stalls the glasses session.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "re-grant prompt failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "damage/boot"
        /** Same channel id ShellService uses for urgent notices. */
        private const val CHANNEL_URGENT = "damage-urgent"
        private const val NOTIF_ID_REGRANT = 0xD02E
    }
}
