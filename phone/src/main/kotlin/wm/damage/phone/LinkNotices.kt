package wm.damage.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import wm.damage.core.transport.PairWatch
import wm.damage.core.util.Log

/**
 * Stage BW (`private/build085/BL-decisions.md` Part E item 1): the phone's LE link-up and link-down notices per lens
 * (they see a link whoever holds it, where the GATT list sees only an app's) for the PROCESS's life — a switch to
 * Sim and back builds a new transport, and the record must not start empty there (2026-11-25: that switch is what
 * he did). One receiver, registered once on the application's context by the first [install] (the service's
 * start; a service recreated in the same process finds it in place) and never unregistered: it ends with the
 * process. Bluetooth turning off clears the record; a register that fails leaves it empty, and unknown counts as
 * not held (a lens is then taken by its address only on the phone's GATT list).
 *
 * [watch] is handed to each `BleTransport`. Every call on it is made under its own lock: this receiver (the main
 * thread), the scan's callback (a binder thread) and the scan's asker (a coroutine). Its per-scan part (the
 * sightings and the asks) is reset by each scan's start, and one scan runs at a time: the phone runs one stack,
 * and `ShellService.stopStack` ends the old transport's scan before the next stack is built.
 */
@SuppressLint("MissingPermission")   // the broadcast is delivered once the Bluetooth permission is granted
object LinkNotices {
    val watch = PairWatch()
    private var installed = false

    /** Register the receiver, once per process. [remembered] names the lenses in the log's notice lines. */
    @Synchronized
    fun install(context: Context, remembered: () -> BleTransport.Remembered) {
        if (installed) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (i.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE) != BluetoothDevice.TRANSPORT_LE) return
                        val dev = deviceOf(i) ?: run { Log.w("ble", "link notice ${i.action} with no device — not recorded"); return }
                        val up = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                        synchronized(watch) { watch.linkNotice(dev.address, up, System.currentTimeMillis()) }
                        val known = remembered()
                        val tag = when { dev.address.equals(known.leftAddress, true) -> "L"; dev.address.equals(known.rightAddress, true) -> "R"; else -> null }
                        if (tag != null) Log.i("ble", "$tag ${dev.address}: link-${if (up) "up" else "down"} notice")
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) synchronized(watch) { watch.bluetoothOff() }
                    }
                }
            }
        }
        try {
            context.applicationContext.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            })
            installed = true
            Log.i("ble", "link notices: recorded for the process's life")
        } catch (e: Exception) {
            Log.w("ble", "link notices not registered (${e.javaClass.simpleName}: ${e.message}) — a lens counts as held only on " +
                "the phone's GATT list; asked again at the service's next start")
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
