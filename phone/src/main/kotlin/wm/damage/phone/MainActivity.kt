package wm.damage.phone

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import wm.damage.core.wire.EvenHubMsg

/**
 * The phone UI: the lens preview IS the app. A thin control strip on top —
 * long-press-release injection, lens toggle, status — everything else is the
 * shell itself, driven by touch (LensView maps touch to the ring grammar).
 */
class MainActivity : ComponentActivity() {

    private var service: ShellService? = null
    private var lens: LensView? = null
    private lateinit var root: FrameLayout
    private lateinit var status: TextView

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as ShellService.LocalBinder).service
            service = svc
            attachLens(svc)
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
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(smallButton("lens") { lens?.toggleArm() })
            addView(smallButton("rel") { service?.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE) })
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        startForegroundService(Intent(this, ShellService::class.java))
        bindService(Intent(this, ShellService::class.java), conn, Context.BIND_AUTO_CREATE)

        // keep the status line honest
        status.postDelayed(object : Runnable {
            override fun run() {
                val svc = service
                if (svc != null) {
                    val driving = if (svc.remoteDriving) " · PC DRIVING" else ""
                    status.text = "${svc.statusLine}$driving"
                    if (svc.shell != null && lens == null) attachLens(svc)
                }
                status.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun smallButton(label: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun attachLens(svc: ShellService) {
        val s = svc.sim ?: return
        if (lens != null) return
        val v = LensView(this, s) { svc.postGesture(it) }
        lens = v
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onDestroy() {
        // the SERVICE keeps running (the shell must not die with the activity);
        // only the binding goes
        try { unbindService(conn) } catch (e: IllegalArgumentException) { /* not bound */ }
        super.onDestroy()
    }
}
