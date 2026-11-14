package wm.damage.core.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.web.LocalSecret

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (dec. A.6; `CONTRACT.md` §10.3, §10.6): the `damage` command line's device commands —
 * `--devices`, `--approve CODE LEVEL`, `--deny CODE`, `--revoke ID` — as a client of the RUNNING service: the content
 * port on loopback, the local secret from the data directory (device `local`, level full), the `app` lane's `devices`
 * scope and its never-queued actions. Nothing is started, bound or written here; no data directory is claimed. Plain
 * output, and an exit status: [OK], [REFUSED] (the service answered no), [UNREACHABLE] (no usable local secret, the
 * service not answering on the port, or the secret refused). A blocking client: it waits for the service's answers as
 * any command line does (Ctrl-C ends it); nothing here is a Bluetooth, render or input path.
 */
object DeviceCli {
    sealed class Command {
        object List : Command()
        data class Approve(val code: String, val level: String) : Command()
        data class Deny(val code: String) : Command()
        data class Revoke(val id: String) : Command()
    }

    const val OK = 0
    const val REFUSED = 1
    const val UNREACHABLE = 3

    /** Runs [cmd] against the service whose content port is [port] on [host] (loopback), with the local secret in
     *  [dataDir]; prints to [out] and [err]; returns the exit status. */
    fun run(cmd: Command, port: Int, dataDir: Path, out: PrintStream, err: PrintStream,
            host: InetAddress = InetAddress.getLoopbackAddress()): Int {
        val secret = LocalSecret.read(dataDir)
        if (secret == null) {
            err.println("damage: no usable local secret in $dataDir (${LocalSecret.FILE}); the service writes it at its start - " +
                "is it running on this machine, as this user?")
            return UNREACHABLE
        }
        val sock = try {
            Socket(host, port)
        } catch (e: Exception) {
            err.println("damage: the service is not answering on ${host.hostAddress}:$port (${e::class.simpleName})")
            return UNREACHABLE
        }
        return sock.use { s ->
            val inp = DataInputStream(s.getInputStream().buffered())
            val o = DataOutputStream(s.getOutputStream().buffered())
            try {
                talk(cmd, secret, inp, o, out, err)
            } catch (e: EOFException) {
                err.println("damage: the service closed the connection before answering")
                UNREACHABLE
            }
        }
    }

    private fun talk(cmd: Command, secret: String, inp: DataInputStream, o: DataOutputStream, out: PrintStream, err: PrintStream): Int {
        // the content port's hello (proto 1) with the local secret in its `local` field, then the lane's own hello
        AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "hello"); put("token", ""); put("local", secret); put("proto", 1) }))
        AppWire.write(o, AppWire.encode(AppWire.hello(OpQueue.newStream(), emptyMap())))
        val first = AppWire.read(inp)
        when (AppWire.type(first)) {
            "welcome" -> {}
            "err" -> {
                err.println("damage: the service refused this command line's credential (${str(first, "detail") ?: "no detail"})")
                return UNREACHABLE
            }
            "end" -> {
                err.println("damage: the service ended the lane (${str(first, "reason")}: ${str(first, "detail") ?: "no detail"})")
                return UNREACHABLE
            }
            else -> {
                err.println("damage: the service answered '${AppWire.type(first)}' where a welcome was due")
                return UNREACHABLE
            }
        }
        val w = AppWire.parseWelcome(first)
        if (w.level != Levels.FULL) {
            err.println("damage: this connection is at ${w.level}; the device table needs ${Levels.FULL}")
            return REFUSED
        }
        AppWire.write(o, AppWire.encode(AppWire.sub(DevicesCore.SCOPE, null)))
        val desc = untilScope(inp) ?: run {
            err.println("damage: the service does not serve the device table (an older build?)")
            return REFUSED
        }
        if (cmd is Command.List) {
            print(desc, out)
            AppWire.write(o, AppWire.encode(AppWire.end("done")))
            return OK
        }
        val (action, params) = when (cmd) {
            is Command.Approve -> DevicesCore.APPROVE to mapOf("code" to cmd.code, "level" to cmd.level)
            is Command.Deny -> DevicesCore.DENY to mapOf("code" to cmd.code)
            is Command.Revoke -> DevicesCore.REVOKE to mapOf("id" to cmd.id)
            Command.List -> error("handled above")
        }
        val op = Op(Peer.LOCAL.device, OpId.Conn(w.conn, 1), DevicesCore.SCOPE, action, emptyList(),
            params = buildJsonObject { for ((k, v) in params) put(k, v) })
        AppWire.write(o, AppWire.encode(AppWire.op(op)))
        while (true) {
            val f = AppWire.read(inp)
            if (AppWire.type(f) != "ans") continue
            val a = AppWire.parseAns(f)
            if (a.id != op.id) continue
            AppWire.write(o, AppWire.encode(AppWire.end("done")))
            return when (val ans = a.answer) {
                is Answer.Seen -> {
                    out.println(when (cmd) {
                        is Command.Approve -> "approved at ${cmd.level}: the waiting page takes the approval and reloads"
                        is Command.Deny -> "denied: the waiting page was told"
                        is Command.Revoke -> "removed ${cmd.id}: its connections were ended"
                        Command.List -> ""
                    })
                    OK
                }
                is Answer.Refused -> {
                    err.println("damage: refused: ${ans.reason}")
                    REFUSED
                }
                else -> {
                    err.println("damage: an answer this command line does not expect: $ans")
                    REFUSED
                }
            }
        }
    }

    /** The device table's description, or null when the service refused the subscription. */
    private fun untilScope(inp: DataInputStream): JsonObject? {
        while (true) {
            val f = AppWire.read(inp)
            val scope = str(f, "scope")
            if (scope != DevicesCore.SCOPE) continue
            when (AppWire.type(f)) {
                "snap" -> return f["desc"] as? JsonObject
                "ans" -> return null
            }
        }
    }

    /** The table as plain lines: devices, then the waiting pages with their codes and the command to approve one. */
    fun print(desc: JsonObject, out: PrintStream) {
        val rows = (desc["rows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val pending = (desc["pending"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        out.println("devices (${rows.size}):")
        if (rows.isEmpty()) out.println("  none approved yet")
        for (r in rows) out.println("  %-10s %-24s %-7s %-20s approved %s by %s, seen %s".format(
            str(r, "id"), str(r, "name"), str(r, "level"), str(r, "tailnetName") ?: str(r, "address") ?: "-",
            DevicesCore.at(num(r, "approvedAt")), str(r, "approvedBy"), DevicesCore.at(num(r, "lastSeen"))))
        out.println("waiting (${pending.size}):")
        if (pending.isEmpty()) out.println("  no page is waiting")
        for (p in pending) out.println("  %-6s from %-20s \"%s\" since %s%s%s".format(
            str(p, "code"), str(p, "tailnetName") ?: str(p, "address"), str(p, "name"), DevicesCore.at(num(p, "since")),
            str(p, "replaces")?.let { " (replaces $it)" } ?: "",
            str(p, "approved")?.let { " - approved at $it, not taken yet" } ?: ""))
        if (pending.isNotEmpty()) out.println("approve one: --approve CODE view|control|full · deny: --deny CODE")
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun num(o: JsonObject, k: String): Long = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
}
