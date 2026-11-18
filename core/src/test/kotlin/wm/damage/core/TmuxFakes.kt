package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Contracts
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.DevicesCore
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.BlobSource
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.WebServer
import wm.damage.core.windows.tmux.LocalTmuxProvider
import wm.damage.core.windows.tmux.PaneFrame
import wm.damage.core.windows.tmux.TmuxConfig
import wm.damage.core.windows.tmux.TmuxCore
import wm.damage.core.windows.tmux.TmuxHostHealth
import wm.damage.core.windows.tmux.TmuxProvider
import wm.damage.core.windows.tmux.TmuxScopes
import wm.damage.core.windows.tmux.TmuxSessionInfo
import wm.damage.core.windows.tmux.TmuxStream
import wm.damage.core.windows.tmux.TmuxTarget
import wm.damage.core.windows.tmux.TmuxWinInfo

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3 (B9–B11): a scripted tmux provider — made-up sessions and frames, every send recorded, a raw op as its
 * literal and hex runs — and the lane rig that serves Tmux on a PC record over loopback, as `Main.tmuxParts` serves
 * it. No tmux, no ssh.
 */
internal class TestTmux : TmuxProvider {
    val listeners = CopyOnWriteArrayList<TmuxProvider.Listener>()
    val subs = ConcurrentHashMap<TmuxProvider.Listener, TmuxTarget>()
    val sent = CopyOnWriteArrayList<String>()
    private val now = System.currentTimeMillis() / 1000
    @Volatile var sessions = listOf(
        TmuxSessionInfo("beardos", "claude", 2, attached = true, activity = now - 90, waiting = false, lastLine = "\$ made-up prompt"),
        TmuxSessionInfo("beardos", "build", 1, attached = false, activity = now - 3600, waiting = false, lastLine = "BUILD SUCCESSFUL"),
    )
    @Volatile var line = "\u001b[32m+ made-up output\u001b[0m"

    fun frame(@Suppress("UNUSED_PARAMETER") t: TmuxTarget) =
        PaneFrame(listOf(line, "\$ "), 80, 24, 2, 1, true, false, 1_700_000_000_000L)

    override fun addListener(l: TmuxProvider.Listener) {
        listeners += l
        l.status(sessions, TmuxConfig())
        l.state("")
    }

    override fun removeListener(l: TmuxProvider.Listener) {
        listeners -= l
        subs.remove(l)
    }

    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) {
        if (target == null) subs.remove(l) else {
            subs[l] = target
            l.frame(target, frame(target))
        }
    }

    /** A capture that changed: each subscriber of [t] gets it. */
    fun push(t: TmuxTarget) {
        for ((l, s) in subs) if (s == t) l.frame(t, frame(t))
    }

    fun status() {
        for (l in listeners) l.status(sessions, TmuxConfig())
    }

    fun alert(s: TmuxSessionInfo) {
        for (l in listeners) l.alert(s)
    }

    override fun sendKeys(target: TmuxTarget, keys: List<String>) { sent += "keys:${target.label}:${keys.joinToString("+")}" }
    override fun sendLiteral(target: TmuxTarget, text: String) { sent += "lit:${target.label}:$text" }
    override fun sendRaw(target: TmuxTarget, data: String) {
        sent += "raw:${target.label}:" + LocalTmuxProvider.rawRuns(data).joinToString("|") { r ->
            if (r.literal) "-l ${r.text}" else "-H " + r.text.toByteArray().joinToString(" ") { "%02x".format(it) }
        }
    }
    override fun history(target: TmuxTarget, lines: Int): List<String> = listOf("\u001b[1mold\u001b[0m line", "newer line")
    override fun windows(target: TmuxTarget): List<TmuxWinInfo> = listOf(TmuxWinInfo(0, "zsh", true, false), TmuxWinInfo(1, "claude", false, true))
    override fun newSession(host: String): String { sent += "new:$host"; return "g2-1" }
    override fun killSession(target: TmuxTarget) { sent += "kill:${target.label}" }
    override fun renameSession(target: TmuxTarget, newName: String) { sent += "ren:${target.label}:$newName" }
    override fun selectWindow(target: TmuxTarget, idx: Int) { sent += "sel:${target.label}:$idx" }
    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) { sent += "fit:${target.label}" }
    override fun capture(target: TmuxTarget): PaneFrame = frame(target)
    override fun health(): List<TmuxHostHealth> = listOf(TmuxHostHealth("beardos", "ok", null, 1_700_000_000_000L))
    override fun close() {}
}

/** Tmux on a PC record behind the web listener on loopback; [stream] the control-mode stream (a scripted one or none). */
internal class TmuxLaneRig(val provider: TestTmux = TestTmux(), stream: TmuxStream? = null) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("damage-tmux-lane")
    val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-tmux-test")).also { it.open() }
    val devices = Devices(dir.resolve(Devices.FILE)).load()
    val net = AppNet(host, devices = DevicesCore(devices))
    val scopes = TmuxScopes(host, provider, stream).also { it.start() }

    init {
        net.serve(TmuxCore.WINDOW, scopes.table, scopes.program, scopes::card, pool = scopes.pool, live = scopes.live)
    }

    val server = WebServer(0, ReachGate(Side.PC), Access(listOf("localhost", "127.0.0.1")), devices, net,
        Bundle(mapOf("index.html" to "<!doctype html><p>fixture</p>".toByteArray())), BlobSource { null },
        bindAddress = InetAddress.getLoopbackAddress()).also { it.start() }

    fun page(level: String): Pair<Page, Long> {
        val w = WebRig.Waiting()
        val p = devices.request("127.0.0.1", null, "a made-up $level page", "browser", null, w)
        assertEquals(null, devices.approve(p.code, level, "local"))
        val r = devices.redeem(w.claims.poll(10, TimeUnit.SECONDS)!!)!!
        val page = Page(server.boundPort, r.secret)
        assertEquals(101, page.upgrade.code)
        page.send(AppWire.hello("s-$level", Contracts.MINE))
        return page to page.next("welcome")["conn"]!!.jsonPrimitive.content.toLong()
    }

    override fun close() {
        server.close()
        net.close()
        scopes.close()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    companion object {
        fun sub(scope: String, need: String? = "focus") = buildJsonObject {
            put("t", "sub"); put("scope", scope)
            need?.let { put("need", buildJsonObject { put("kind", it) }) }
        }

        fun op(conn: Long, k: Long, action: String, target: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            put("t", "op")
            put("op", buildJsonObject {
                put("device", ""); put("conn", conn); put("k", k); put("window", TmuxCore.WINDOW); put("action", action)
                putJsonArray("targets") { add(kotlinx.serialization.json.JsonPrimitive(target)) }
                put("result", JsonNull as JsonElement); put("params", params)
            })
        }
    }
}

/**
 * A scripted tmux control-mode client for [TmuxStream]'s process seam (the lane tests, `LookServer`): it answers the
 * stream's init commands as tmux does — one `%begin … 1` / `%end … 1` block per command, the display line, the
 * capture's lines — and [output] writes a `%output` of the active pane. No tmux.
 */
internal class FakeControl(@Volatile var screen: List<String> = listOf("\u001b[32m\$\u001b[0m made-up", "")) : TmuxStream.Proc {
    private val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile var closed = false
    val commands = CopyOnWriteArrayList<String>()
    private var n = 100

    override val stdout: java.io.InputStream = object : java.io.InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        private var eof = false
        override fun read(): Int {
            while (true) {
                if (eof) return -1
                val c = cur
                if (c != null && pos < c.size) return c[pos++].toInt() and 0xFF
                val next = q.take()
                if (next.isEmpty()) { eof = true; return -1 }
                cur = next; pos = 0
            }
        }
    }

    override val stdin: java.io.OutputStream = object : java.io.OutputStream() {
        private val line = java.io.ByteArrayOutputStream()
        override fun write(b: Int) {
            if (b == '\n'.code) { handle(String(line.toByteArray())); line.reset() } else line.write(b)
        }
        override fun close() {
            if (closed) return
            closed = true
            emit("%exit")
            q.put(ByteArray(0))
        }
    }

    init {
        emit("%begin 1 99 0"); emit("%end 1 99 0"); emit("%session-changed \$1 claude")
    }

    @Synchronized private fun handle(cmd: String) {
        commands += cmd
        n++
        emit("%begin 1 $n 1")
        if (cmd.startsWith("display-message")) emit("%1 2 1 80 24 0 1")
        else if (cmd.startsWith("capture-pane")) screen.forEach { emit(it) }
        emit("%end 1 $n 1")
    }

    /** The active pane wrote [s]: a `%output` line, its control bytes and backslashes escaped as tmux does. */
    @Synchronized fun output(s: String) {
        val sb = StringBuilder("%output %1 ")
        for (b in s.toByteArray()) {
            val v = b.toInt() and 0xFF
            if (v < 32 || v == '\\'.code) sb.append("\\%03o".format(v)) else sb.append(v.toChar())
        }
        emit(sb.toString())
    }

    @Synchronized fun line(s: String) = emit(s)

    /** The session ended under it: `%exit`, then the end of its output (as tmux does). */
    @Synchronized fun exit() {
        emit("%exit")
        closed = true
        q.put(ByteArray(0))
    }

    private fun emit(s: String) {
        if (!closed || s == "%exit") q.put((s + "\n").toByteArray(Charsets.ISO_8859_1))
    }

    override fun stderr(): String = ""
    override fun exited(ms: Long): Boolean = closed
    override fun exitCode(): Int = 0
    override val name: String = "scripted"
}

/** The next `live` message of [src] (others skipped), within a test's bound. */
internal fun Page.liveOf(src: String, ms: Long = 10_000): JsonObject {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
        val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
        if (AppWire.type(f) == "live" && f["src"]?.jsonPrimitive?.content == src) return f
    }
    throw AssertionError("no live message of $src within $ms ms")
}
