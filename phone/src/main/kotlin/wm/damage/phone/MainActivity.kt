package wm.damage.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import wm.damage.core.wire.EvenHubMsg

/**
 * The phone UI: the lens replica IS the app. A thin control strip on top —
 * the display target (sim / glasses, confirm on tap), lens toggle, the
 * long-press-release injection, status — everything else is the shell itself,
 * driven by touch (LensView maps touch to the ring grammar and the service
 * injects it through the transport, so it reaches a PC shell too).
 *
 * Runtime permissions are requested up front: POST_NOTIFICATIONS carries the
 * §9.3 out-of-band error channel (silently dead without the grant), and the
 * Bluetooth pair is what lets the banked GLASSES target ever come alive.
 * The service starts after the request resolves either way — the sim target
 * needs none of them.
 */
class MainActivity : ComponentActivity() {

    private var service: ShellService? = null
    private var lens: LensView? = null
    private var lensGeneration = -1
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var targetButton: Button
    private var polling = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val denied = grants.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                status.text = "denied: ${denied.joinToString { it.substringAfterLast('.') }} — " +
                    "error notifications/BLE limited"
            }
            maybeRequestDozeExemption()
            startShellService()
        }

    private fun dozeExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    /** One-time battery-optimization exemption ask (the G2CC pattern —
     *  setup/BatteryOptimization.kt): without the exemption Doze can stop
     *  even the foreground service, and the FB lease fails open. Asked once;
     *  a decline stays visible in the status line, and the boot receiver
     *  re-prompts if the grant is later revoked. */
    @SuppressLint("BatteryLife")
    private fun maybeRequestDozeExemption() {
        if (dozeExempt()) return
        val prefs = Prefs(this)
        if (prefs.dozeAsked) return
        prefs.setDozeAsked()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            status.text = "battery-exemption dialog unavailable: ${e.message} — grant it in Settings"
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ShellService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        status = TextView(this).apply {
            setTextColor(0xFF3CB060.toInt())
            textSize = 12f
            text = "starting the shell service..."
        }
        targetButton = Button(this).apply {
            text = "target: ${Prefs(this@MainActivity).target.name.lowercase()}"
            setOnClickListener { confirmTargetSwitch() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(targetButton)
            addView(smallButton("lens") { lens?.toggleArm() })
            addView(smallButton("type") { typeDialog() })
            addView(smallButton("rel") { service?.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE) })
            addView(smallButton("ring") { ringProbeDialog() })
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        val wanted = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) {
            maybeRequestDozeExemption()
            startShellService()
        } else permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun startShellService() {
        startForegroundService(Intent(this, ShellService::class.java))
        bindService(Intent(this, ShellService::class.java), conn, Context.BIND_AUTO_CREATE)
        if (!polling) {
            polling = true
            status.postDelayed(poller, 1000)
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            if (!polling) return
            val svc = service
            if (svc != null) {
                val driving = if (svc.remoteDriving) " · PC DRIVING" else ""
                val doze = if (!dozeExempt()) " · no Doze exemption (renewals may stall)" else ""
                status.text = "${svc.displayStatus()}$driving$doze"
                targetButton.text = "target: ${Prefs(this@MainActivity).target.name.lowercase()}"
                // re-attach when the service rebuilt its stack (new mirror): the
                // old view would render a stale mirror forever otherwise
                if (svc.mirror != null && svc.stackGeneration != lensGeneration) attachLens(svc)
            }
            status.postDelayed(this, 1000)
        }
    }

    /** Switch the display target with a confirmation — GLASSES restarts the
     *  stack onto the real BLE path (refused loudly on non-CFW firmware). */
    private fun confirmTargetSwitch() {
        val svc = service ?: return
        val next = if (Prefs(this).target == Prefs.Target.GLASSES) Prefs.Target.SIM else Prefs.Target.GLASSES
        AlertDialog.Builder(this)
            .setTitle("Display target")
            .setMessage("Switch the display target to ${next.name.lowercase()}? The shell restarts on it.")
            .setPositiveButton("switch") { _, _ -> svc.switchTarget(next) }
            .setNegativeButton("keep", null)
            .show()
    }

    private fun smallButton(label: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    /** Typed-text entry (TMUX.md verdict 1): the line rides the transport to
     *  whichever shell drives; the focused window stages it behind a confirm
     *  ON GLASS, so this dialog sends without running anything. */
    private fun typeDialog() {
        val svc = service ?: return
        val box = android.widget.EditText(this).apply {
            hint = "line for the focused window (Tmux confirms before running)"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Type")
            .setView(box)
            .setPositiveButton("send") { _, _ ->
                val line = box.text?.toString().orEmpty()
                if (line.isNotBlank()) svc.postText(line)
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    /** The ring GATT probe (read-only, over the phone's OWN link to the ring —
     *  the glasses cannot relay ring data, see RingProbe). Confirmed on tap:
     *  it is a deliberate radio contact, not a passive view. */
    private fun ringProbeDialog() {
        val svc = service ?: return
        AlertDialog.Builder(this)
            .setTitle("Ring probe")
            .setMessage("Connect to the ring over this phone's own BLE link and list its " +
                "services, read-only (battery + firmware revision if offered)? " +
                "One connection, no pairing; the result arrives as a notification.")
            .setPositiveButton("probe") { _, _ -> svc.probeRing() }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun attachLens(svc: ShellService) {
        val m = svc.mirror ?: return
        lens?.let {
            it.detach()
            root.removeView(it)
        }
        val v = LensView(this, m, svc.runningTarget.name.lowercase()) { svc.postGesture(it) }
        lens = v
        lensGeneration = svc.stackGeneration
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onDestroy() {
        // the SERVICE keeps running (the shell must not die with the activity);
        // only the binding, the poller, and the sim listener go
        polling = false
        status.removeCallbacks(poller)
        lens?.detach()
        try { unbindService(conn) } catch (e: IllegalArgumentException) { /* not bound */ }
        super.onDestroy()
    }
}
