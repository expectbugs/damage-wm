package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.windows.tmux.LocalTmuxProvider
import wm.damage.core.windows.tmux.TmuxConfig
import wm.damage.core.windows.tmux.TmuxHostCfg
import wm.damage.core.windows.tmux.TmuxTarget

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * S1-3 fix A1: what the product types into a pane is what arrives, byte for byte. tmux reads an argument that ends in
 * `;` as a command separator (`man tmux` 3.5a, "a trailing semicolon is also interpreted as a command separator";
 * `cmd-parse.y` `cmd_parse_from_arguments`); before the fix a `;` typed alone at the desk, or at the end of a line or a
 * snippet, never arrived. Every key path of the provider (`sendRaw`: the desk terminal's bytes; `sendLiteral` then
 * Enter: a typed line or a snippet, from a page or the glasses; `sendKeys`: a quick key) types into a pane of a
 * THROWAWAY server of this test's own (`tmux -L damage-test-keys-<pid>-<n> -f /dev/null`, never the default socket or
 * any session of his; ended and its socket removed at the end). The pane runs `cat` on a raw terminal into a file, so
 * the file holds exactly the bytes the pane's program received. The scripts are the product's own, run by the
 * product's own `ProcessExec`; a shell function only puts the throwaway server's flags in front of `tmux`.
 */
class TmuxKeysTest {
    private val sock = "damage-test-keys-${ProcessHandle.current().pid()}-${System.nanoTime() % 100_000}"
    private val dir: Path = Files.createTempDirectory("damage-keys")
    private val out: Path = dir.resolve("pane.bin")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun tmux(vararg a: String): String {
        val p = ProcessBuilder(listOf("tmux", "-L", sock, "-f", "/dev/null") + a).redirectErrorStream(true)
            .also { it.environment().remove("TMUX") }.start()
        val o = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(10, TimeUnit.SECONDS)
        return o.trim()
    }

    private val socketFile = Path.of(System.getenv("TMUX_TMPDIR") ?: "/tmp",
        "tmux-${Files.getAttribute(Path.of("/proc/self"), "unix:uid")}", sock)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmux("kill-server")
        Files.deleteIfExists(socketFile)
        Files.deleteIfExists(out)
        Files.deleteIfExists(dir)
    }

    private fun show(b: ByteArray): String = b.joinToString("") { x ->
        val c = x.toInt() and 0xFF
        when {
            c == 0x5C -> "\\\\"
            c in 0x21..0x7E -> c.toChar().toString()
            else -> "\\x%02x".format(c)
        }
    }

    @Test
    fun everyPrintableCharacterAndTheSpecialSequencesArriveAsTyped() {
        tmux("new-session", "-d", "-s", "keys", "-x", "80", "-y", "24",
            "sh", "-c", "stty raw -echo -iexten; exec cat > '$out'")
        val until = System.currentTimeMillis() + 10_000
        while (!Files.exists(out) && System.currentTimeMillis() < until) Thread.sleep(20)
        check(Files.exists(out)) { "the pane's cat never started" }

        // the product's exec and scripts, aimed at this test's server only
        val exec = LocalTmuxProvider.TmuxExec { host, script ->
            LocalTmuxProvider.ProcessExec().run(host,
                "unset TMUX; tmux() { command tmux -L '$sock' -f /dev/null \"\$@\"; }; $script")
        }
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 60_000, capturePacingMs = 60_000)
        val t = TmuxTarget("local", "keys")

        val printable = (0x20..0x7E).map { it.toChar().toString() }
        val sequences = listOf(";", "a;", ";;", "a;;", ";a", "a;b", "a;;b;", "-", "-a", "--", "-l", "", "\\", "\\;",
            "a\\;", "\\\\;", "'", "\"", "'\"`", "\$HOME", "#x", "{a}", "~", "%1", "C-c", "Enter", "é→😀",
            printable.joinToString(""))
        val controls = ((0x00..0x1F) + 0x7F).map { it.toChar().toString() }

        // (path, what was asked, the bytes that must arrive)
        val cases = ArrayList<Triple<String, String, ByteArray>>()
        val sends = ArrayList<() -> Unit>()
        fun case(path: String, asked: String, expect: String, send: () -> Unit) {
            cases += Triple(path, asked, expect.toByteArray(Charsets.UTF_8)); sends += send
        }
        for (s in printable + sequences + controls + listOf("ls -la\r\u001b[A;", "a;\r;\u0003"))
            case("raw", s, s) { p.sendRaw(t, s) }
        for (s in printable + sequences) case("line", s, s + "\r") { p.sendLiteral(t, s); p.sendKeys(t, listOf("Enter")) }
        for ((k, b) in listOf("Enter" to "\r", "C-c" to "\u0003", "Escape" to "\u001b", "Tab" to "\t", "BSpace" to "\u007f",
            "y" to "y", "q" to "q", "1" to "1", ";" to ";", "a;" to "a;", "-" to "-", "-x" to "-x", "\\;" to "\\;"))
            case("key", k, b) { p.sendKeys(t, listOf(k)) }

        val got = ArrayList<ByteArray>()
        var at = 0L
        for ((i, c) in cases.withIndex()) {
            val failure = runCatching { sends[i]() }.exceptionOrNull()
            val want = at + c.third.size
            val deadline = System.currentTimeMillis() + 1_500
            while (Files.size(out) < want && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val all = Files.readAllBytes(out)
            got += all.copyOfRange(at.toInt(), all.size).also { at = all.size.toLong() }
            if (failure != null) got[i] = ("(refused: ${failure.message})").toByteArray()
        }
        Thread.sleep(300)
        val tail = Files.readAllBytes(out).copyOfRange(at.toInt(), Files.size(out).toInt())

        val rows = cases.indices.map { i -> Triple(cases[i], show(cases[i].third), show(got[i])) }
        println("A1 table (path | sent | arrived):")
        for ((c, sent, arrived) in rows) println("${c.first} | ${show(c.second.toByteArray())} | $arrived" +
            if (sent == arrived) "" else "   <-- expected $sent")
        val wrong = rows.filter { it.second != it.third }.map { "${it.first.first} '${show(it.first.second.toByteArray())}': sent ${it.second}, arrived ${it.third}" }
        assertEquals(emptyList(), wrong, "bytes that did not arrive as typed (${wrong.size} of ${rows.size})")
        assertEquals("", show(tail), "nothing arrived after the last case")
        p.close()
    }
}
