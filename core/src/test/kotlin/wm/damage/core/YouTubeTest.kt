package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import wm.damage.core.windows.music.YouTube

/**
 * YOUTUBE (`MUSIC.md` §3.9 / §9.6, verdict 7): the yt-dlp client against a
 * FAKE yt-dlp — a shell script in a disposable temp dir that inspects its own
 * argv and prints canned output. Nothing here reaches the network, a real
 * yt-dlp, ffmpeg, a database or the phone; the only side effect is a file the
 * fake writes inside its own temp dir, removed again after each test.
 *
 * Covered: search parsing incl. the channel/url/duration fallbacks and a junk
 * line that is skipped rather than losing the batch; a failed search carrying
 * stderr into the message; grab progress callbacks (monotonic, from stdout and
 * from stderr) and the returned path; a failed grab; a grab that printed a
 * path it never wrote; an absent binary; the argv itself — the url built from
 * the id, passed after `--`, and the `-o` template under the YouTube dir.
 */
class YouTubeTest {

    private val temps = CopyOnWriteArrayList<Path>()

    @AfterTest
    fun cleanup() {
        for (t in temps) {
            if (!Files.exists(t)) continue
            Files.walk(t).use { w ->
                w.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        temps.clear()
    }

    private fun tmp(name: String): Path =
        Files.createTempDirectory("damage-yt-$name").also { temps.add(it) }

    /** The canned search rows the fake prints. Row 1 exercises a long unicode
     *  title (nothing is shortened anywhere), row 2 the uploader/url/duration
     *  fallbacks with `duration` arriving as a STRING. Two of the five lines
     *  are unusable on purpose. */
    private val searchStdout = listOf(
        """{"id":"aaaaaaaaaaa","title":"Ångström — 日本語 — a deliberately long title that must survive the round trip intact, every word of it","channel":"Chan One","duration":213,"url":"https://www.youtube.com/watch?v=aaaaaaaaaaa"}""",
        "this line is not json at all",
        """{"id":"bbbbbbbbbbb","title":"Second Song","uploader":"Up Two","duration":"185.4"}""",
        """{"title":"no id on this one"}""",
        "",
    )

    /**
     * Writes an executable fake yt-dlp. It records its argv one-per-line into
     * [rec], then branches: `--dump-json` in the args = a search, `--no-simulate`
     * = a grab. [failCode] non-zero makes it print to stderr and exit with it.
     * [progressOnStderr] moves the progress lines to stderr (a build or a
     * config could; the client reads either pipe). [writeFile] false makes it
     * print a path it never created.
     */
    private fun fakeYtDlp(
        home: Path,
        rec: Path,
        failCode: Int = 0,
        progressOnStderr: Boolean = false,
        writeFile: Boolean = true,
        ansi: Boolean = false,
    ): String {
        val script = home.resolve("yt-dlp")
        val pstream = if (progressOnStderr) ">&2" else ""
        val body = buildString {
            append("#!/bin/sh\n")
            append(": > '").append(rec).append("'\n")
            append("for a in \"\$@\"; do printf '%s\\n' \"\$a\" >> '").append(rec).append("'; done\n")
            append("mode=none\n")
            append("for a in \"\$@\"; do\n")
            append("  case \"\$a\" in --dump-json) mode=search ;; --no-simulate) mode=grab ;; esac\n")
            append("done\n")
            if (failCode != 0) {
                append("echo 'WARNING: [youtube] falling back' >&2\n")
                append("echo 'ERROR: [youtube] xxxxxxxxxxx: Video unavailable' >&2\n")
                append("exit ").append(failCode).append("\n")
            }
            append("if [ \"\$mode\" = search ]; then\n")
            for (l in searchStdout) append("  printf '%s\\n' ").append(shq(l)).append("\n")
            append("  exit 0\n")
            append("fi\n")
            append("if [ \"\$mode\" = grab ]; then\n")
            append("  out=''; prev=''\n")
            append("  for a in \"\$@\"; do if [ \"\$prev\" = '-o' ]; then out=\"\$a\"; fi; prev=\"\$a\"; done\n")
            append("  dir=`dirname \"\$out\"`\n")
            append("  url=''\n")
            append("  for a in \"\$@\"; do url=\"\$a\"; done\n")
            append("  vid=\"\${url##*v=}\"\n")
            append("  echo 'unrelated notice on stdout' \n")
            // Real yt-dlp colours the percentage itself when colour is on, and
            // the 42 % after the 50 % is there to prove the report is monotonic.
            for (pct in listOf("  0.0%", " 50.0%", " 42.0%", "100%")) {
                if (ansi) {
                    append("  printf '[download] \\033[0;94m")
                        .append(pct.replace("%", "%%"))
                        .append("\\033[0m of ~   3.00MiB at 1.00MiB/s\\n' ")
                        .append(pstream).append("\n")
                } else {
                    append("  echo '[download]  ").append(pct)
                        .append(" of ~   3.00MiB at    1.00MiB/s ETA 00:03' ")
                        .append(pstream).append("\n")
                }
            }
            append("  echo '[ExtractAudio] Destination: ok' >&2\n")
            append("  f=\"\$dir/Fake Song [\$vid].opus\"\n")
            if (writeFile) {
                append("  mkdir -p \"\$dir\"\n")
                append("  printf 'fake audio bytes' > \"\$f\"\n")
            }
            append("  printf '%s\\n' \"\$f\"\n")
            append("  exit 0\n")
            append("fi\n")
            append("echo 'fake yt-dlp: no mode matched' >&2\n")
            append("exit 2\n")
        }
        Files.writeString(script, body)
        assertTrue(script.toFile().setExecutable(true), "could not make the fake yt-dlp executable")
        return script.toString()
    }

    /** Single-quote for /bin/sh. */
    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun argvOf(rec: Path): List<String> =
        Files.readAllLines(rec).let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }

    // ------------------------------------------------------------------ search

    @Test
    fun `search parses every usable line and skips the rest`() {
        val home = tmp("search")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec), home.resolve("YouTube"))

        val hits = yt.search("boards of canada", 10)

        assertEquals(2, hits.size, "the junk line and the id-less line are skipped, the rest kept")
        assertEquals("aaaaaaaaaaa", hits[0].id)
        assertTrue(
            hits[0].title.endsWith("every word of it") && hits[0].title.startsWith("Ångström"),
            "the whole title survives, unicode and all: ${hits[0].title}",
        )
        assertEquals("Chan One", hits[0].channel)
        assertEquals(213, hits[0].durS)
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa", hits[0].url)

        // row 2: uploader fills channel, the url is built from the id, and a
        // duration that arrived as a STRING is still an Int here
        assertEquals("bbbbbbbbbbb", hits[1].id)
        assertEquals("Up Two", hits[1].channel)
        assertEquals(185, hits[1].durS)
        assertEquals("https://www.youtube.com/watch?v=bbbbbbbbbbb", hits[1].url)

        val argv = argvOf(rec)
        assertEquals(listOf("--no-download", "--flat-playlist", "--dump-json", "ytsearch10:boards of canada"), argv)
    }

    @Test
    fun `search asks for exactly n and trims the query`() {
        val home = tmp("searchn")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec), home.resolve("YouTube"))
        yt.search("  aphex twin  ", 3)
        assertEquals("ytsearch3:aphex twin", argvOf(rec).last())
    }

    @Test
    fun `search refuses an empty query and a nonsense n before running anything`() {
        val home = tmp("searchbad")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec), home.resolve("YouTube"))
        assertFailsWith<IllegalArgumentException> { yt.search("   ", 10) }
        assertFailsWith<IllegalArgumentException> { yt.search("ok", 0) }
        assertTrue(Files.notExists(rec), "nothing was run")
    }

    @Test
    fun `a failed search throws carrying the stderr`() {
        val home = tmp("searchfail")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec, failCode = 1), home.resolve("YouTube"))
        val e = assertFailsWith<IllegalStateException> { yt.search("whatever", 10) }
        val msg = assertNotNull(e.message)
        assertTrue("exited 1" in msg, msg)
        assertTrue("Video unavailable" in msg, msg)
    }

    @Test
    fun `an absent yt-dlp is loud on both calls`() {
        val home = tmp("nobinary")
        val missing = home.resolve("not-installed/yt-dlp").toString()
        val yt = YouTube(missing, home.resolve("YouTube"))
        val s = assertFailsWith<IllegalStateException> { yt.search("anything", 5) }
        assertTrue("not runnable" in (s.message ?: ""), s.message ?: "")
        val g = assertFailsWith<IllegalStateException> { yt.grab("aaaaaaaaaaa") {} }
        assertTrue("not runnable" in (g.message ?: ""), g.message ?: "")
    }

    // -------------------------------------------------------------------- grab

    @Test
    fun `grab reports monotonic progress and returns the file it wrote`() {
        val home = tmp("grab")
        val rec = home.resolve("argv.txt")
        val dir = home.resolve("Music/YouTube")
        val yt = YouTube(fakeYtDlp(home, rec), dir)

        val seen = ArrayList<Int>()
        val path = yt.grab("ccccccccccc") { seen.add(it) }

        // 0 → 50 → (a 42 that arrived late is not reported) → 100
        assertEquals(listOf(0, 50, 100), seen)
        assertTrue(Files.isRegularFile(path), "$path")
        assertEquals("Fake Song [ccccccccccc].opus", path.fileName.toString())
        assertTrue(path.startsWith(dir.toAbsolutePath().normalize()), "$path is under $dir")
        assertEquals("fake audio bytes", Files.readString(path))
        assertTrue(Files.isDirectory(dir), "the YouTube dir is created")
    }

    @Test
    fun `grab reads progress off stderr too`() {
        val home = tmp("grabstderr")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec, progressOnStderr = true), home.resolve("YouTube"))
        val seen = ArrayList<Int>()
        val path = yt.grab("ddddddddddd") { seen.add(it) }
        assertEquals(listOf(0, 50, 100), seen)
        assertTrue(Files.isRegularFile(path), "$path")
    }

    @Test
    fun `grab reads a coloured progress line`() {
        val home = tmp("grabansi")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec, ansi = true), home.resolve("YouTube"))
        val seen = ArrayList<Int>()
        val path = yt.grab("iiiiiiiiiii") { seen.add(it) }
        assertEquals(listOf(0, 50, 100), seen, "the escapes are stripped before the percentage is read")
        assertTrue(Files.isRegularFile(path), "$path")
    }

    @Test
    fun `the grab argv builds the url from the id and passes it after --`() {
        val home = tmp("grabargv")
        val rec = home.resolve("argv.txt")
        val dir = home.resolve("Music/YouTube")
        val yt = YouTube(fakeYtDlp(home, rec), dir)
        yt.grab("eeeeeeeeeee") {}

        val argv = argvOf(rec)
        assertEquals(
            listOf("--", "https://www.youtube.com/watch?v=eeeeeeeeeee"),
            argv.takeLast(2),
            "the url is last and separated by --, so it can never read as a flag",
        )
        assertEquals(1, argv.count { it == "--" }, "exactly one separator")
        // audio-only, one video, indexable container, streaming progress
        for (f in listOf(
            "-f", "bestaudio", "-x", "--audio-format", "opus", "--embed-metadata",
            "--no-playlist", "--max-filesize", "300m", "--no-simulate", "--newline",
            "--progress", "--print", "after_move:filepath", "-o",
        )) assertTrue(f in argv, "missing $f in $argv")
        val template = argv[argv.indexOf("-o") + 1]
        assertEquals(
            dir.toAbsolutePath().normalize().resolve("%(title)s [%(id)s].%(ext)s").toString(),
            template,
        )
    }

    @Test
    fun `a failed grab throws carrying the stderr`() {
        val home = tmp("grabfail")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec, failCode = 3), home.resolve("YouTube"))
        val seen = ArrayList<Int>()
        val e = assertFailsWith<IllegalStateException> { yt.grab("fffffffffff") { seen.add(it) } }
        val msg = assertNotNull(e.message)
        assertTrue("exited 3" in msg, msg)
        assertTrue("Video unavailable" in msg, msg)
        assertTrue(seen.isEmpty(), "no progress was reported for a run that never downloaded")
    }

    @Test
    fun `a grab that wrote no file is loud rather than returning a path`() {
        val home = tmp("grabnofile")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec, writeFile = false), home.resolve("YouTube"))
        val e = assertFailsWith<IllegalStateException> { yt.grab("ggggggggggg") {} }
        assertTrue("no output file" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `grab refuses an id that is not one, before starting anything`() {
        val home = tmp("grabbadid")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec), home.resolve("YouTube"))
        for (bad in listOf("", "   ", "a b", "id;rm -rf /", "https://x/y", "ab\ncd", "sp/ash")) {
            assertFailsWith<IllegalArgumentException>("accepted \"$bad\"") { yt.grab(bad) {} }
        }
        assertTrue(Files.notExists(rec), "nothing was run")
    }

    @Test
    fun `an id that begins with a dash is kept and still rides after --`() {
        // Real YouTube ids do start with '-', so the id rule must not refuse
        // one; the `--` separator is what keeps it from reading as a flag.
        val home = tmp("grabdashid")
        val rec = home.resolve("argv.txt")
        val yt = YouTube(fakeYtDlp(home, rec), home.resolve("YouTube"))
        val path = yt.grab("-hhhhhhhhhh") {}
        assertEquals(
            listOf("--", "https://www.youtube.com/watch?v=-hhhhhhhhhh"),
            argvOf(rec).takeLast(2),
        )
        assertEquals("Fake Song [-hhhhhhhhhh].opus", path.fileName.toString())
    }
}
