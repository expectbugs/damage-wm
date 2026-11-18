package wm.damage.core.web

import java.net.InetAddress
import java.nio.file.Path
import wm.damage.core.app.AppNet
import wm.damage.core.app.DevicesCore
import wm.damage.core.net.ReachGate

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6: the PC's access state for one process — the local secret file and the device table in the
 * data directory — opened at a PROCESS START only ([atProcessStart], called by the PC's two host builders, `runShell`
 * and `hostOnly`; `tools/check_apk_wiring.py` holds the list), never from a tool flag: a tool must not write the local
 * secret or read the device table of the service's data directory.
 */
class WebAccess private constructor(val dir: Path, val devices: Devices) {
    /** The device table as the `app` lane serves it (level full). */
    val core = DevicesCore(devices)

    /** The local secret, read once per connection (the content host's `localSecret` provider). */
    fun localSecret(): String? = LocalSecret.read(dir)

    /** The web interface listener on [port] (not started), behind the process's reach gate. */
    fun server(port: Int, reach: ReachGate, hosts: Collection<String>, app: AppNet, blobs: BlobSource,
               tailnetName: (InetAddress) -> String?, fonts: FontSource = FontSource { null },
               uploads: Uploads? = null, vendor: VendorSource = VendorSource { null }): WebServer =
        WebServer(port, reach, Access(hosts + Access.LOOPBACK_NAMES, ownBrowserFull = true), devices, app,
            Bundle.fromResources(), blobs, tailnetName, fonts, uploads = uploads, vendor = vendor)

    companion object {
        /** At a process start: the local secret written when missing, the device table read. [say] takes each line
         *  the start says (the service's journal; host-only's log); none carries a secret or a code. */
        fun atProcessStart(dir: Path, say: (String) -> Unit): WebAccess {
            // neither failure ends the start: without the file the command line is refused (said); an unreadable
            // table is set aside and starts empty (said)
            say(try {
                LocalSecret.ensure(dir)
            } catch (e: Exception) {
                ("the local secret (${LocalSecret.FILE}) could not be written (${e::class.simpleName}: ${e.message}); the " +
                    "device commands are refused until a start writes it").also { wm.damage.core.util.Log.e("web", it) }
            })
            val d = Devices(dir.resolve(Devices.FILE)).load()
            d.problem?.let(say)
            val live = d.rows().count { !it.revoked }
            say("device table: $live approved device(s) in ${Devices.FILE}")
            return WebAccess(dir, d)
        }
    }
}
