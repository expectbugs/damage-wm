package wm.damage.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.64 T0 (2026-11-11). At 05:14 that day `desktop --help` was run to read the tool's options. The
 * program knew no `--help`: `main` picked a tool by `"--flag" in args` and sent every other list to
 * the shell, so a second shell started in `auto` with the preview window, on the real data
 * directory, beside the running service, and ran four minutes. The class is green-gate GG-8's: a
 * tool that runs something other than what was asked and exits 0.
 *
 * Everything here calls the pure [parseArgs], or [runCli] with a `start` that only records. Nothing
 * in this class runs the program, and the binary is never started with `--help` or an unknown
 * argument to check this, before the fix or after it: these tests are the gate.
 */
class CliTest {

    private fun parse(vararg a: String): Cli = parseArgs(arrayOf(*a))

    private fun refused(vararg a: String): String {
        val c = parse(*a)
        assertIs<Cli.Refused>(c, "${a.toList()} must be refused; it was read as $c")
        return c.what
    }

    /** What [runCli] did with a line: its exit status, both streams, and what it handed to `start`. */
    private class Ran(val code: Int, val out: String, val err: String, val started: List<Cli.Run>)

    private fun run(vararg a: String): Ran {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val started = ArrayList<Cli.Run>()
        val code = runCli(arrayOf(*a), PrintStream(out, true), PrintStream(err, true)) { started += it }
        return Ran(code, out.toString(), err.toString(), started)
    }

    private val nl = System.lineSeparator()
    private val nothing = emptyList<Cli.Run>()

    @Test
    fun helpIsTheUsageTextAndStartsNothing() {
        // the incident, in the old chain's own reading (the transcription below): no branch knew
        // --help, so it fell to the shell — auto, with the preview
        assertEquals<Cli>(Cli.Shell("auto", remoteHost = null, preview = true), before(listOf("--help")))
        assertEquals(Cli.Usage, parse("--help"))
        assertEquals(Cli.Usage, parse("-h"))
        // beside anything, known or not: asking how the program is used never runs it
        assertEquals(Cli.Usage, parse("--selfcheck", "--help"))
        assertEquals(Cli.Usage, parse("--transport", "sim", "-h"))
        assertEquals(Cli.Usage, parse("--bogus", "--help"))
        for (h in listOf("--help", "-h")) {
            val r = run(h)
            assertEquals(nothing, r.started, "$h starts nothing")
            assertEquals(0, r.code, "$h is not a failure")
            assertEquals(USAGE + nl, r.out, "$h prints the usage text on stdout")
            assertEquals("", r.err)
        }
    }

    @Test
    fun anArgumentItDoesNotKnowIsRefusedByNameAndStartsNothing() {
        // --selfchek is a tool flag mistyped; --version and -v are what one tries after --help;
        // the bare words are values with no flag in front of them
        for (bad in listOf("--bogus", "--selfchek", "--version", "-v", "help", "deep", "live", "sim", "")) {
            assertEquals<Cli>(Cli.Shell("auto", remoteHost = null, preview = true), before(listOf(bad)),
                "what the old chain did with '$bad'")
            assertEquals("unknown argument '$bad'", refused(bad))
            val r = run(bad)
            assertEquals(nothing, r.started, "'$bad' starts nothing")
            assertEquals(CLI_REFUSED, r.code)
            assertEquals(2, CLI_REFUSED)
            assertEquals("", r.out)
            assertEquals("desktop: unknown argument '$bad'$nl$USAGE$nl", r.err,
                "one line that names the argument, then the usage text, on stderr")
        }
        // among arguments it knows: the service's line with one flag mistyped must not become a
        // shell with a window
        assertEquals("unknown argument '--no-previw'", refused("--transport", "auto", "--no-previw"))
        assertEquals("unknown argument '--selfchek'", refused("--no-preview", "--selfchek"))
        assertEquals(nothing, run("--transport", "auto", "--no-previw").started)
        // a word where no value belongs
        assertEquals("unknown argument 'extra'", refused("--selfcheck", "extra"))
        assertEquals("unknown argument 'b'", refused("--snapshot", "a", "b"))
        assertEquals("unknown argument 'live'", refused("--games-check", "live"))
        assertEquals("unknown argument 'dir'", refused("--feed-check", "live", "dir"))
        // `deep` and `live` were read anywhere in the list; they follow their flag now
        assertEquals("unknown argument 'deep'", refused("deep", "--games-check"))
        assertEquals("unknown argument 'live'", refused("live", "--feed-check"))
    }

    @Test
    fun theServicesLineStartsTheShellHeadlessInAuto() {
        // /etc/init.d/damage: command_args="… -jar /home/user/.damage/damage.jar --transport auto --no-preview"
        // (DAILY.md). A flag of this line that parseArgs did not know would stop the service at
        // the next deploy.
        val line = arrayOf("--transport", "auto", "--no-preview")
        val shell = Cli.Shell("auto", remoteHost = null, preview = false)
        assertEquals<Cli>(shell, parseArgs(line))
        assertTrue(shell.headless, "no preview = the JVM is set headless before anything loads")
        val r = run(*line)
        assertEquals(listOf<Cli.Run>(shell), r.started)
        assertEquals(0, r.code)
        assertEquals("", r.out + r.err, "an accepted line prints nothing of its own")
    }

    @Test
    fun noArgumentStartsTheShellInAutoWithThePreview() {
        // `./gradlew :desktop:run` — the documented dev session
        val shell = Cli.Shell("auto", remoteHost = null, preview = true)
        assertEquals<Cli>(shell, parse())
        assertTrue(!shell.headless)
        assertEquals(listOf<Cli.Run>(shell), run().started)
    }

    @Test
    fun everyToolFlagReachesItsToolWithItsParameters() {
        assertEquals<Cli>(Cli.SelfCheck(), parse("--selfcheck"))
        assertEquals<Cli>(Cli.Snapshot("out"), parse("--snapshot", "out"))
        assertEquals<Cli>(Cli.Snapshot("snapshots"), parse("--snapshot"))
        // the directory is carried for GG-8's fix; null = none given
        assertEquals<Cli>(Cli.EpubCheck(null), parse("--epub-check"))
        assertEquals<Cli>(Cli.EpubCheck("/home/user/books"), parse("--epub-check", "/home/user/books"))
        assertEquals<Cli>(Cli.MusicCheck(), parse("--music-check"))
        assertEquals<Cli>(Cli.GamesCheck(deep = false), parse("--games-check"))
        assertEquals<Cli>(Cli.GamesCheck(deep = true), parse("--games-check", "deep"))
        assertEquals<Cli>(Cli.FeedCheck("core/src/test/resources/feed", live = false), parse("--feed-check"))
        assertEquals<Cli>(Cli.FeedCheck("core/src/test/resources/feed", live = true), parse("--feed-check", "live"))
        assertEquals<Cli>(Cli.FeedCheck("fx", live = false), parse("--feed-check", "fx"))
        assertEquals<Cli>(Cli.FeedCheck("fx", live = true), parse("--feed-check", "fx", "live"))
        assertEquals<Cli>(Cli.CardRender("design/shots/cards"), parse("--card-render"))
        assertEquals<Cli>(Cli.CardRender("sheets"), parse("--card-render", "sheets"))
        assertEquals<Cli>(Cli.HostOnly(), parse("--host-only"))
        assertEquals<Cli>(Cli.BleInfo(), parse("--ble-info"))
        // --no-preview beside a tool sets headless, as it did, on either side of the flag
        assertEquals<Cli>(Cli.SelfCheck(headless = true), parse("--selfcheck", "--no-preview"))
        assertEquals<Cli>(Cli.Snapshot("out", headless = true), parse("--no-preview", "--snapshot", "out"))
        assertEquals<Cli>(Cli.GamesCheck(deep = true, headless = true), parse("--games-check", "deep", "--no-preview"))
        // a flag is never a value: the old chain wrote these renders into a directory named --no-preview
        assertEquals<Cli>(Cli.Snapshot("snapshots", headless = true), parse("--snapshot", "--no-preview"))
        // and the tool is what start is handed, once
        val r = run("--snapshot", "out")
        assertEquals(listOf<Cli.Run>(Cli.Snapshot("out")), r.started)
        assertEquals(0, r.code)
    }

    @Test
    fun theShellsFlagsReadAsBefore() {
        assertEquals<Cli>(Cli.Shell("sim", null, preview = true), parse("--sim"))
        assertEquals<Cli>(Cli.Shell("ble", null, preview = true), parse("--ble"))
        assertEquals<Cli>(Cli.Shell("remote", "aphone", preview = true), parse("--remote", "aphone"))
        assertEquals(listOf("auto", "sim", "ble", "remote"), DesktopStack.MODES)
        for (m in DesktopStack.MODES) assertEquals<Cli>(Cli.Shell(m, null, preview = true), parse("--transport", m))
        // the simulator recipe of HANDOFF.md §28.2 and §73
        assertEquals<Cli>(Cli.Shell("sim", null, preview = false), parse("--transport", "sim", "--no-preview"))
        assertEquals<Cli>(Cli.Shell("sim", null, preview = false), parse("--no-preview", "--sim"))
        // --remote HOST beside --transport names the host and leaves the mode alone
        assertEquals<Cli>(Cli.Shell("auto", "100.64.0.9", preview = true), parse("--transport", "auto", "--remote", "100.64.0.9"))
        assertEquals<Cli>(Cli.Shell("sim", "h", preview = false), parse("--remote", "h", "--no-preview", "--transport", "sim"))
    }

    @Test
    fun aModeTheStackCannotBuildIsRefusedBeforeAnythingStarts() {
        // the old chain handed any word to runShell, which bound the ports and only then met
        // DesktopStack's throw; the refusal is the stack's own sentence, word for word
        assertEquals("unknown stack mode 'bogus' (sim | ble | remote; auto is the standby policy, not a stack)",
            refused("--transport", "bogus"))
        assertEquals(unknownStackMode("Auto"), refused("--transport", "Auto"))
        val r = run("--transport", "bogus")
        assertEquals(nothing, r.started)
        assertEquals(2, r.code)
        assertTrue(r.err.startsWith("desktop: unknown stack mode 'bogus' "), r.err)
        // a flag that needs a value and has none used to be read as absent
        assertEquals("--transport needs a mode (auto | sim | ble | remote)", refused("--transport"))
        assertEquals("--transport needs a mode (auto | sim | ble | remote)", refused("--transport", "--no-preview"))
        assertEquals("--remote needs a host", refused("--remote"))
        assertEquals("--remote needs a host", refused("--remote", "--sim"))
        assertEquals("--remote needs a host", refused("--remote", " "))
    }

    @Test
    fun aLineThatAsksTwoThingsIsRefused() {
        // the old chain ran the first tool in its own order and dropped the other with exit 0
        assertEquals("--selfcheck and --snapshot: one entry point at a time", refused("--selfcheck", "--snapshot", "d"))
        assertEquals("--snapshot and --selfcheck: one entry point at a time", refused("--snapshot", "d", "--selfcheck"))
        assertEquals("--host-only and --ble-info: one entry point at a time", refused("--host-only", "--ble-info"))
        // a shell flag beside a tool was dropped
        assertEquals("--sim does not apply to --selfcheck", refused("--selfcheck", "--sim"))
        assertEquals("--transport does not apply to --host-only", refused("--transport", "sim", "--host-only"))
        assertEquals("--remote does not apply to --snapshot", refused("--snapshot", "d", "--remote", "h"))
        // two statements of the mode: --transport won over --remote over --sim over --ble
        val once = ": the mode is said once (--transport MODE beside --remote HOST keeps the host)"
        assertEquals("--sim and --ble$once", refused("--sim", "--ble"))
        assertEquals("--sim and --ble$once", refused("--ble", "--sim"))
        assertEquals("--transport and --ble$once", refused("--transport", "sim", "--ble"))
        assertEquals("--transport and --sim$once", refused("--sim", "--transport", "sim"))
        assertEquals("--remote and --sim$once", refused("--remote", "h", "--sim"))
        // the same flag twice: the first was read
        assertEquals("--snapshot is given twice", refused("--snapshot", "a", "--snapshot", "b"))
        assertEquals("--transport is given twice", refused("--transport", "auto", "--transport", "sim"))
        assertEquals("--no-preview is given twice", refused("--no-preview", "--no-preview"))
        for (line in listOf(listOf("--selfcheck", "--snapshot", "d"), listOf("--selfcheck", "--sim"), listOf("--sim", "--ble")))
            assertEquals(nothing, run(*line.toTypedArray()).started, "$line starts nothing")
    }

    /**
     * `main`'s reading of an argument list before 0.64 T0: the `when` chain of Main.kt at 716da79,
     * line for line, with its answers written as [Cli] values. It accepted every list.
     *
     * One line differs from that chain on purpose, the one [parseArgs]' KDoc names: `after` does
     * not return a flag (the chain took `--snapshot --no-preview` as a directory called
     * `--no-preview`). And the chain read no directory after `--epub-check`, so that field is null.
     */
    private fun before(args: List<String>): Cli.Run {
        fun after(flag: String): String? = args.getOrNull(args.indexOf(flag) + 1)?.takeIf { !it.startsWith("-") }
        val headless = "--no-preview" in args
        return when {
            "--selfcheck" in args -> Cli.SelfCheck(headless)
            "--epub-check" in args -> Cli.EpubCheck(null, headless)
            "--music-check" in args -> Cli.MusicCheck(headless)
            "--games-check" in args -> Cli.GamesCheck(deep = "deep" in args, headless)
            "--feed-check" in args -> Cli.FeedCheck(
                after("--feed-check")?.takeIf { it != "live" } ?: "core/src/test/resources/feed", live = "live" in args, headless)
            "--card-render" in args -> Cli.CardRender(after("--card-render") ?: "design/shots/cards", headless)
            "--snapshot" in args -> Cli.Snapshot(after("--snapshot") ?: "snapshots", headless)
            "--ble-info" in args -> Cli.BleInfo(headless)
            "--host-only" in args -> Cli.HostOnly(headless)
            else -> {
                val remoteHost = if ("--remote" in args) after("--remote") else null
                val mode = when {
                    "--transport" in args -> after("--transport") ?: "auto"
                    remoteHost != null -> "remote"
                    "--sim" in args -> "sim"
                    "--ble" in args -> "ble"
                    else -> "auto"
                }
                Cli.Shell(mode, remoteHost, preview = !headless)
            }
        }
    }

    @Test
    fun anAcceptedLineMeansWhatItMeantBefore() {
        // every list of up to four of these: each flag, each word a flag takes, a directory, a
        // host, an unknown flag, a blank
        val words = CLI_FLAGS + listOf("auto", "sim", "ble", "remote", "deep", "live", "dir", "host", "--bogus", "")
        var lists = 0
        var accepted = 0
        fun walk(list: List<String>) {
            lists++
            val now = parseArgs(list.toTypedArray())
            if (now is Cli.Run) {
                accepted++
                assertEquals(before(list), if (now is Cli.EpubCheck) now.copy(dir = null) else now,
                    "$list is accepted and must mean what it meant")
            }
            if (list.size < 4) for (w in words) walk(list + w)
        }
        walk(emptyList())
        // a walk that accepts nothing would compare nothing
        // (255 of 475,255 when this was written)
        assertTrue(accepted >= 200, "$accepted of $lists lists accepted: the walk no longer reaches the accepted lines")
    }

    @Test
    fun theUsageTextTheFlagListAndTheParserAgree() {
        assertEquals(CLI_FLAGS.size, CLI_FLAGS.toSet().size, "a flag is listed once")
        // every flag the text names is one the list holds, and the other way round (a `-word`
        // inside a compound such as PC-direct is not a flag: the lookbehind)
        val named = Regex("""(?<![\w-])--?[a-z][a-z-]*""").findAll(USAGE).map { it.value }.toSet()
        assertEquals(CLI_FLAGS.toSet(), named)
        // every flag of the list has a branch in parseArgs: none is refused as unknown
        for (f in CLI_FLAGS) {
            val c = parse(f)
            assertTrue(c !is Cli.Refused || !c.what.startsWith("unknown argument"), "$f is in CLI_FLAGS and parseArgs answers $c")
        }
        // the modes and the three default directories are the ones the code uses
        for (m in DesktopStack.MODES) assertTrue(Regex("""^\s+(--transport MODE\s+)?$m\s""", RegexOption.MULTILINE).containsMatchIn(USAGE), "the mode $m is described")
        for (d in listOf("snapshots", "core/src/test/resources/feed", "design/shots/cards"))
            assertTrue("(default: $d)" in USAGE, "the default $d is named")
        // R2 (review 1b RE-7): every list the shell's synopsis line admits is one the parser runs. The
        // line read `[--transport MODE | --sim | --ble] [--remote HOST] [--no-preview]`, which admits
        // `--sim --remote h`, a list parseArgs refuses (the mode is said once)
        val synopsis = USAGE.lines().first().removePrefix("usage:").trim().removePrefix("desktop").trim()
        val lists = expand(synopsis)
        assertTrue(lists.size >= 8, "the synopsis expands to ${lists.size} lists: $lists")
        for (l in lists) {
            val c = parse(*l.toTypedArray())
            assertIs<Cli.Shell>(c, "the synopsis admits $l and parseArgs answers $c")
        }
    }

    /** Every argument list a synopsis line admits: `[ … ]` is optional, `|` separates its alternatives,
     *  groups nest; MODE stands for `sim`, HOST for `h`. */
    private fun expand(line: String): List<List<String>> {
        val toks = Regex("""\[|]|\||[^\s\[\]|]+""").findAll(line).map { it.value }.toList()
        var i = 0
        fun seq(): List<List<String>> {
            var acc = listOf(emptyList<String>())
            while (i < toks.size && toks[i] != "]" && toks[i] != "|") {
                val t = toks[i++]
                val opts: List<List<String>> = if (t == "[") {
                    val alts = mutableListOf<List<String>>(emptyList())        // the group may be left out
                    do { alts += seq() } while (i < toks.size && toks[i++] == "|")
                    alts
                } else listOf(listOf(when (t) { "MODE" -> "sim"; "HOST" -> "h"; else -> t }))
                acc = acc.flatMap { a -> opts.map { a + it } }
            }
            return acc
        }
        return seq().distinct()
    }
}
