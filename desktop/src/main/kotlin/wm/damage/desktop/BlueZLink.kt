package wm.damage.desktop

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.DiscoveryFilter
import com.github.hypfvieh.bluetooth.DiscoveryTransport
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.types.UInt16
import wm.damage.core.util.Log

/**
 * What the PC-direct transport needs from BlueZ, behind a seam so the glue is
 * unit-testable with a fake (HANDOFF.md §8.3 C2/C3) and so its first run on a
 * radio happens after Adam flashes, not during the build. Object paths are
 * BlueZ D-Bus paths (`/org/bluez/hci0/dev_XX_..`, `.../serviceNNNN/charNNNN`).
 * Every call may block (D-Bus round trips); the transport wraps them in an
 * interruptible IO context so a cancelled attempt ends promptly.
 */
interface BlueZLink {
    data class Adapter(val path: String, val name: String, val address: String, val powered: Boolean)

    data class Peer(
        val path: String, val address: String, val name: String,
        val connected: Boolean, val servicesResolved: Boolean,
        /** BlueZ reports RSSI only while the device is being seen by the
         *  current scan — its presence says "advertising now". */
        val rssi: Int?,
    )

    data class Chars(val writePath: String, val notifyPath: String)

    /** `Connected` and `ServicesResolved` of a device, read directly. */
    data class Probe(val connected: Boolean, val servicesResolved: Boolean)

    sealed class Event {
        /** A notification on a characteristic (`Value` changed). */
        data class Notification(val charPath: String, val value: ByteArray) : Event()

        /** A device's `Connected` property changed. */
        data class Connected(val devicePath: String, val connected: Boolean) : Event()

        /** The signal path itself failed (an exception in the handler). */
        data class Failure(val detail: String) : Event()
    }

    /** The default adapter; throws when there is none. */
    fun adapter(): Adapter

    fun startDiscovery()
    fun stopDiscovery()

    /** True while this client's discovery session is running. */
    fun discovering(): Boolean

    /** Devices BlueZ currently knows on the adapter (discovery adds to them;
     *  previously connected ones persist even when not advertising). */
    fun peers(): List<Peer>

    /** Connect; returns when the link is up (BlueZ's `Connect`), throws with
     *  the reason otherwise. No deadline of our own. */
    fun connect(devicePath: String)
    fun disconnect(devicePath: String)

    /** Read the device's link state; throws when the read itself fails. */
    fun probe(devicePath: String): Probe

    /** The G2 service's write and notify characteristics; throws naming what
     *  was found when they are missing (firmware drift shows itself). */
    fun characteristics(devicePath: String): Chars

    fun startNotify(charPath: String)

    /** Write-without-response (`WriteValue` type=command); throws on failure. */
    fun write(charPath: String, value: ByteArray)

    /** The characteristic's negotiated MTU (BlueZ ≥ 5.62), or null when the
     *  read failed — the transport refuses on null. */
    fun mtu(charPath: String): Int?

    fun rssi(devicePath: String): Int?

    /** Receive notifications, connection changes and handler failures; one
     *  listener per link. */
    fun listen(l: (Event) -> Unit)

    /** Stop listening. The bus connection is process-wide and stays open. */
    fun close()

    companion object {
        const val SERVICE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5450"
        const val WRITE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5401"
        const val NOTIFY_UUID = "00002760-08c2-11e1-9073-0e8ac72e5402"
    }
}

/**
 * The real link: bluez-dbus (MIT) over dbus-java (MIT) on the system bus.
 * Choices from reading the library and bluetoothd sources (review round 1):
 *  - dbus-java answers every method call with a 20 s reply deadline by default;
 *    an LE `Connect` can legitimately take the kernel's 20 s. The deadline is
 *    set to 0 (wait for the reply): a bus that ends completes every pending
 *    call with an error, so nothing waits on a dead bus. A bluetoothd that
 *    stops answering while the bus lives is the case "supervise externally"
 *    covers.
 *  - `Device1.Connect` is called RAW: the wrapper's `connect()` discards the
 *    failure reason. BlueZ errors arrive as plain `DBusExecutionException`s
 *    (the library's typed `Bluez*Exception` classes are never raised by the
 *    bus), so the glue reports their message text.
 *  - `Connected`/`ServicesResolved`/`MTU` are read through `Properties.Get`
 *    directly, so a failed read is an exception, never a silent `false`/null.
 *  - `DeviceManager` is a process-wide singleton and its device maps are not
 *    thread-safe: all map access goes through one lock; blocking calls to
 *    bluetoothd (Connect, WriteValue) run outside it.
 *  - Notifications arrive as `PropertiesChanged(Value)` signals on the
 *    characteristic's path, on one signal thread (ordered).
 */
class BlueZDbus : BlueZLink {
    private val dm: DeviceManager = try {
        DeviceManager.getInstance()
    } catch (e: IllegalStateException) {
        DeviceManager.createInstance(false)      // false = the SYSTEM bus
    }
    private val lock = Any()
    private val chars = HashMap<String, BluetoothGattCharacteristic>()
    private var handler: AbstractPropertiesChangedHandler? = null

    init {
        MethodCall.setDefaultTimeout(0)          // wait for the reply, not a deadline
    }

    private fun <T> locked(f: () -> T): T = synchronized(lock, f)

    private fun adapterObj(): BluetoothAdapter = locked {
        val a = try { dm.adapter } catch (e: Exception) { null }
            ?: try { dm.scanForBluetoothAdapters(); dm.adapter } catch (e: Exception) { null }
        a ?: throw IllegalStateException("no Bluetooth adapter on the system bus (is bluetoothd running?)")
    }

    private fun props(path: String): Properties =
        dm.dbusConnection.getRemoteObject("org.bluez", path, Properties::class.java)

    private fun <T> prop(path: String, iface: String, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return props(path).Get<Any>(iface, name) as T
    }

    override fun adapter(): BlueZLink.Adapter {
        val a = adapterObj()
        val powered = try { prop<Boolean>(a.dbusPath, "org.bluez.Adapter1", "Powered") } catch (e: Exception) {
            throw IllegalStateException("adapter ${a.dbusPath}: Powered unreadable: ${e.message}", e)
        }
        val name = try { a.deviceName } catch (e: Exception) { null } ?: a.dbusPath.substringAfterLast('/')
        val address = try { a.address } catch (e: Exception) { null } ?: "?"
        return BlueZLink.Adapter(a.dbusPath, name, address, powered)
    }

    override fun startDiscovery() {
        val a = adapterObj()
        try {
            dm.setScanFilter(mapOf(DiscoveryFilter.Transport to DiscoveryTransport.LE))
        } catch (e: Exception) {
            Log.w("bluez", "LE discovery filter not applied: ${e.message} — scanning unfiltered")
        }
        try {
            a.rawAdapter.StartDiscovery()
        } catch (e: Exception) {
            if (!discovering()) throw IllegalStateException("StartDiscovery refused on ${a.dbusPath}: ${e.message}", e)
        }
    }

    override fun stopDiscovery() {
        try { adapterObj().rawAdapter.StopDiscovery() } catch (e: Exception) { Log.w("bluez", "StopDiscovery: ${e.message}") }
    }

    override fun discovering(): Boolean = try {
        prop<Boolean>(adapterObj().dbusPath, "org.bluez.Adapter1", "Discovering")
    } catch (e: Exception) {
        Log.w("bluez", "Discovering unreadable: ${e.message}")
        false
    }

    /** BlueZ's device objects under the adapter (the wrapper introspects). */
    private fun devices(): List<BluetoothDevice> = locked {
        val a = adapterObj()
        dm.getDevices(a.address, true)
    }

    private fun device(path: String): BluetoothDevice =
        devices().firstOrNull { it.dbusPath == path }
            ?: throw IllegalStateException("device $path is no longer known to BlueZ")

    override fun peers(): List<BlueZLink.Peer> = devices().map { d ->
        fun <T> safe(f: () -> T?): T? = try { f() } catch (e: Exception) { null }
        BlueZLink.Peer(d.dbusPath, safe { d.address } ?: "", safe { d.name } ?: safe { d.alias } ?: "",
            safe { d.isConnected } == true, safe { d.isServicesResolved } == true, safe { d.rssi }?.toInt())
    }

    override fun connect(devicePath: String) {
        val d = device(devicePath)
        try {
            d.rawDevice.Connect()        // raw: a refusal keeps its reason (already connected = success on 5.86)
        } catch (e: Exception) {
            throw IllegalStateException("connect $devicePath: ${e.message ?: e::class.simpleName}", e)
        }
        val p = probe(devicePath)
        if (!p.connected) throw IllegalStateException("connect $devicePath: not connected after Connect returned")
    }

    override fun disconnect(devicePath: String) {
        val d = try { device(devicePath) } catch (e: Exception) {
            Log.i("bluez", "disconnect $devicePath: ${e.message} (already gone)"); return
        }
        try { d.rawDevice.Disconnect() } catch (e: Exception) { Log.w("bluez", "disconnect $devicePath: ${e.message}") }
    }

    override fun probe(devicePath: String): BlueZLink.Probe = try {
        BlueZLink.Probe(
            prop(devicePath, "org.bluez.Device1", "Connected"),
            prop(devicePath, "org.bluez.Device1", "ServicesResolved"),
        )
    } catch (e: Exception) {
        throw IllegalStateException("$devicePath: link state unreadable: ${e.message ?: e::class.simpleName}", e)
    }

    override fun characteristics(devicePath: String): BlueZLink.Chars = locked {
        val d = device(devicePath)
        d.refreshGattServices()
        val svc = d.getGattServiceByUuid(BlueZLink.SERVICE_UUID)
            ?: throw IllegalStateException("$devicePath: service 5450 not found — services: " +
                d.gattServices.joinToString { it.uuid })
        val w = svc.getGattCharacteristicByUuid(BlueZLink.WRITE_UUID)
        val n = svc.getGattCharacteristicByUuid(BlueZLink.NOTIFY_UUID)
        if (w == null || n == null)
            throw IllegalStateException("$devicePath: chars 5401/5402 missing under 5450 — have: " +
                svc.gattCharacteristics.joinToString { it.uuid })
        chars[w.dbusPath] = w
        chars[n.dbusPath] = n
        BlueZLink.Chars(w.dbusPath, n.dbusPath)
    }

    private fun char(path: String) = locked { chars[path] }
        ?: throw IllegalStateException("characteristic $path not resolved")

    override fun startNotify(charPath: String) {
        try { char(charPath).rawGattCharacteristic.StartNotify() } catch (e: Exception) {
            throw IllegalStateException("StartNotify $charPath: ${e.message ?: e::class.simpleName}", e)
        }
    }

    override fun write(charPath: String, value: ByteArray) {
        try { char(charPath).writeValue(value, mapOf("type" to "command")) } catch (e: Exception) {
            throw IllegalStateException("WriteValue $charPath (${value.size} B): ${e.message ?: e::class.simpleName}", e)
        }
    }

    override fun mtu(charPath: String): Int? = try {
        when (val v = props(charPath).Get<Any>("org.bluez.GattCharacteristic1", "MTU")) {
            is UInt16 -> v.toInt()
            is Number -> v.toInt()
            else -> { Log.w("bluez", "MTU on $charPath has type ${v?.javaClass?.simpleName}"); null }
        }
    } catch (e: Exception) {
        Log.w("bluez", "MTU property not readable on $charPath: ${e.message}")
        null
    }

    override fun rssi(devicePath: String): Int? = try {
        prop<Short>(devicePath, "org.bluez.Device1", "RSSI").toInt()
    } catch (e: Exception) {
        null     // absent while not advertising — the normal connected state
    }

    override fun listen(l: (BlueZLink.Event) -> Unit) {
        close()
        val h = object : AbstractPropertiesChangedHandler() {
            override fun handle(s: Properties.PropertiesChanged) {
                try {
                    val changed = s.propertiesChanged ?: return
                    when (s.interfaceName) {
                        "org.bluez.GattCharacteristic1" -> {
                            val v = changed["Value"]?.value ?: return
                            val bytes: ByteArray = when (v) {
                                is ByteArray -> v
                                is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
                                else -> { l(BlueZLink.Event.Failure("Value of unexpected type ${v::class.simpleName}")); return }
                            }
                            l(BlueZLink.Event.Notification(s.path, bytes))
                        }
                        "org.bluez.Device1" -> {
                            val c = changed["Connected"]?.value as? Boolean ?: return
                            l(BlueZLink.Event.Connected(s.path, c))
                        }
                    }
                } catch (e: Exception) {
                    // an exception here would otherwise end on the signal
                    // thread's uncaught handler, invisible to the transport
                    l(BlueZLink.Event.Failure("signal handler: ${e.message ?: e::class.simpleName}"))
                }
            }
        }
        dm.registerPropertyHandler(h)
        handler = h
    }

    override fun close() {
        handler?.let { h ->
            try { dm.unRegisterPropertyHandler(h) } catch (e: Exception) { Log.w("bluez", "unregister: ${e.message}") }
        }
        handler = null
    }
}
