package wm.damage.desktop

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.DiscoveryFilter
import com.github.hypfvieh.bluetooth.DiscoveryTransport
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt16
import wm.damage.core.util.Log

/**
 * What the PC-direct transport needs from BlueZ, behind a seam so the glue is
 * unit-testable with a fake (HANDOFF.md §8.3 C2/C3) and so its first run on a
 * radio happens after Adam flashes, not during the build. Object paths are
 * BlueZ D-Bus paths (`/org/bluez/hci0/dev_XX_..`, `.../serviceNNNN/charNNNN`).
 */
interface BlueZLink {
    data class Adapter(val path: String, val name: String, val address: String, val powered: Boolean)

    data class Peer(
        val path: String, val address: String, val name: String,
        val connected: Boolean, val servicesResolved: Boolean, val rssi: Int?,
    )

    data class Chars(val writePath: String, val notifyPath: String)

    sealed class Event {
        /** A notification on a characteristic (`Value` changed). */
        data class Notification(val charPath: String, val value: ByteArray) : Event()

        /** A device's `Connected` property changed. */
        data class Connected(val devicePath: String, val connected: Boolean) : Event()
    }

    /** The default adapter; throws when there is none. */
    fun adapter(): Adapter

    fun startDiscovery()
    fun stopDiscovery()

    /** Devices BlueZ currently knows on the adapter (discovery adds to them). */
    fun peers(): List<Peer>

    /** Connect; returns when the link is up (BlueZ's `Connect`), throws with
     *  the reason otherwise. No deadline of our own. */
    fun connect(devicePath: String)
    fun disconnect(devicePath: String)

    /** Wait until GATT services are resolved; throws if the link ends first. */
    fun awaitServicesResolved(devicePath: String)

    /** The G2 service's write and notify characteristics; throws naming what
     *  was found when they are missing (firmware drift shows itself). */
    fun characteristics(devicePath: String): Chars

    fun startNotify(charPath: String)

    /** Write-without-response (`WriteValue` type=command); throws on failure. */
    fun write(charPath: String, value: ByteArray)

    /** The characteristic's negotiated MTU (BlueZ ≥ 5.62), or null if not exposed. */
    fun mtu(charPath: String): Int?

    fun rssi(devicePath: String): Int?

    /** Receive notifications and connection changes; one listener per link. */
    fun listen(l: (Event) -> Unit)

    fun close()

    companion object {
        const val SERVICE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5450"
        const val WRITE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5401"
        const val NOTIFY_UUID = "00002760-08c2-11e1-9073-0e8ac72e5402"
    }
}

/**
 * The real link: bluez-dbus (MIT) over dbus-java (MIT) on the system bus.
 * Reads of the library's source shaped three choices: `Device1.Connect` is
 * called RAW (the wrapper's `connect()` discards the failure reason), the MTU
 * comes from the characteristic's `Properties.Get`, and notifications arrive
 * as `PropertiesChanged(Value)` signals on the characteristic's path.
 */
class BlueZDbus : BlueZLink {
    private val dm: DeviceManager = try {
        DeviceManager.getInstance()
    } catch (e: IllegalStateException) {
        DeviceManager.createInstance(false)      // false = the SYSTEM bus
    }
    private val chars = HashMap<String, BluetoothGattCharacteristic>()

    private fun adapterObj() = dm.adapter ?: run {
        dm.scanForBluetoothAdapters()
        dm.adapter ?: throw IllegalStateException("no Bluetooth adapter on the system bus (is bluetoothd running?)")
    }

    override fun adapter(): BlueZLink.Adapter {
        val a = adapterObj()
        return BlueZLink.Adapter(a.dbusPath, a.deviceName ?: "?", a.address ?: "?", a.isPowered)
    }

    override fun startDiscovery() {
        val a = adapterObj()
        try {
            dm.setScanFilter(mapOf(DiscoveryFilter.Transport to DiscoveryTransport.LE))
        } catch (e: Exception) {
            Log.w("bluez", "LE discovery filter not applied: ${e.message} — scanning unfiltered")
        }
        if (!a.startDiscovery() && a.isDiscovering != true)
            throw IllegalStateException("StartDiscovery refused on ${a.deviceName}")
    }

    override fun stopDiscovery() {
        try { adapterObj().stopDiscovery() } catch (e: Exception) { Log.w("bluez", "StopDiscovery: ${e.message}") }
    }

    private fun devices(): List<BluetoothDevice> {
        val a = adapterObj()
        dm.findBtDevicesByIntrospection(a)
        return dm.getDevices(a.address, true)
    }

    private fun device(path: String): BluetoothDevice =
        devices().firstOrNull { it.dbusPath == path }
            ?: throw IllegalStateException("device $path is no longer known to BlueZ")

    override fun peers(): List<BlueZLink.Peer> = devices().map { d ->
        BlueZLink.Peer(d.dbusPath, d.address ?: "", d.name ?: d.alias ?: "",
            d.isConnected == true, d.isServicesResolved == true, d.rssi?.toInt())
    }

    override fun connect(devicePath: String) {
        val d = device(devicePath)
        try {
            d.rawDevice.Connect()        // raw: a refusal keeps its reason
        } catch (e: org.bluez.exceptions.BluezAlreadyConnectedException) {
            // fine — already up
        } catch (e: Exception) {
            throw IllegalStateException("connect $devicePath: ${e.message ?: e::class.simpleName}", e)
        }
        if (d.isConnected != true) throw IllegalStateException("connect $devicePath: not connected after Connect returned")
    }

    override fun disconnect(devicePath: String) {
        try { device(devicePath).rawDevice.Disconnect() } catch (e: Exception) { Log.w("bluez", "disconnect $devicePath: ${e.message}") }
    }

    override fun awaitServicesResolved(devicePath: String) {
        while (true) {
            val d = device(devicePath)
            if (d.isServicesResolved == true) return
            if (d.isConnected != true) throw IllegalStateException("$devicePath disconnected before services resolved")
            Thread.sleep(100)            // pacing; the wait itself has no deadline
        }
    }

    override fun characteristics(devicePath: String): BlueZLink.Chars {
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
        synchronized(chars) { chars[w.dbusPath] = w; chars[n.dbusPath] = n }
        return BlueZLink.Chars(w.dbusPath, n.dbusPath)
    }

    private fun char(path: String) = synchronized(chars) { chars[path] }
        ?: throw IllegalStateException("characteristic $path not resolved")

    override fun startNotify(charPath: String) = char(charPath).startNotify()

    override fun write(charPath: String, value: ByteArray) =
        char(charPath).writeValue(value, mapOf("type" to "command"))

    override fun mtu(charPath: String): Int? = try {
        val props = dm.dbusConnection.getRemoteObject("org.bluez", charPath, Properties::class.java)
        when (val v = props.Get<Any>("org.bluez.GattCharacteristic1", "MTU")) {
            is UInt16 -> v.toInt()
            is Number -> v.toInt()
            else -> null
        }
    } catch (e: Exception) {
        Log.w("bluez", "MTU property not readable on $charPath: ${e.message}")
        null
    }

    override fun rssi(devicePath: String): Int? = try { device(devicePath).rssi?.toInt() } catch (e: Exception) { null }

    override fun listen(l: (BlueZLink.Event) -> Unit) {
        dm.registerPropertyHandler(object : AbstractPropertiesChangedHandler() {
            override fun handle(s: Properties.PropertiesChanged) {
                val changed = s.propertiesChanged ?: return
                when (s.interfaceName) {
                    "org.bluez.GattCharacteristic1" -> {
                        val v = changed["Value"]?.value ?: return
                        val bytes: ByteArray = when (v) {
                            is ByteArray -> v
                            is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
                            else -> { Log.w("bluez", "Value of unexpected type ${v::class.simpleName}"); return }
                        }
                        l(BlueZLink.Event.Notification(s.path, bytes))
                    }
                    "org.bluez.Device1" -> {
                        val c = changed["Connected"]?.value as? Boolean ?: return
                        l(BlueZLink.Event.Connected(s.path, c))
                    }
                }
            }
        })
    }

    override fun close() {
        try { dm.closeConnection() } catch (e: Exception) { Log.w("bluez", "close: ${e.message}") }
    }
}
