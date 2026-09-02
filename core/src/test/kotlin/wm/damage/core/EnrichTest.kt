package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.util.Log
import wm.damage.core.windows.music.Enrich
import wm.damage.core.windows.music.MusicDb
import wm.damage.core.windows.music.VizData

/**
 * The ingest bridge (`MUSIC.md` §9.5). Nothing here runs a real interpreter,
 * touches the database, or reaches the network: `python` is a small shell
 * script in a disposable temp directory that records how it was called and
 * answers from control files, and the DVIZ bytes it prints are written by
 * `VizData.encode()` in this test, so the round trip is byte-exact.
 *
 * What is pinned: the pass order and the phase callbacks, the
 * continue-past-a-failing-pass rule, the "everything failed" escalation, the
 * per-track `FAILED` lines the runner prints on stdout while still exiting 0,
 * the working directory, and viz's bytes / null-with-a-loud-log behaviour.
 */
class EnrichTest {

    // ------------------------------------------------------------- fixture

    private class Fake(val dir: Path) {
        val python: Path = dir.resolve("python")
        val log: Path = dir.resolve("calls.log")
        val audio: Path = dir.resolve("audio dir")   // a space: args must stay separate

        /** One recorded invocation: the child's working directory and argv. */
        class Call(val cwd: String, val args: List<String>)

        fun calls(): List<Call> {
            if (!Files.exists(log)) return emptyList()
            val out = ArrayList<Call>()
            var cwd = ""
            var args = ArrayList<String>()
            for (line in Files.readAllLines(log)) when {
                line.startsWith("CWD ") -> { cwd = line.removePrefix("CWD "); args = ArrayList() }
                line.startsWith("ARG ") -> args.add(line.removePrefix("ARG "))
                line == "END" -> out.add(Call(cwd, args))
            }
            return out
        }

        fun mark(name: String) { Files.writeString(dir.resolve(name), "x") }
        fun unmark(name: String) { Files.deleteIfExists(dir.resolve(name)) }
    }

    private fun canned(): VizData {
        val bands = 24
        val frames = 3
        val packed = ByteArray((frames * bands + 1) / 2)
        for (i in 0 until frames * bands) {
            val n = (i * 5 + 1) % 16
            if (i and 1 == 0) packed[i shr 1] = (n shl 4).toByte()
            else packed[i shr 1] = (packed[i shr 1].toInt() or n).toByte()
        }
        val rms = byteArrayOf(0x0F, 0xF0.toByte(), 0x33)
        return VizData(20, bands, frames, packed, 5, rms, intArrayOf(0, 500, 1000))
    }

    private fun fixture(): Fake {
        val dir = Files.createTempDirectory("damage-enrich-test-")
        val f = Fake(dir)
        Files.createDirectories(f.audio)
        Files.write(dir.resolve("canned.viz"), canned().encode())
        val ctl = dir.toString()
        Files.writeString(f.python, """
            |#!/bin/sh
            |{
            |  printf 'CWD %s\n' "${'$'}PWD"
            |  for a in "${'$'}@"; do printf 'ARG %s\n' "${'$'}a"; done
            |  printf 'END\n'
            |} >> "$ctl/calls.log"
            |
            |if [ "${'$'}2" = "viz" ]; then
            |  if [ -f "$ctl/viz-fail" ]; then
            |    echo "ModuleNotFoundError: No module named 'librosa'" >&2
            |    exit 1
            |  fi
            |  if [ -f "$ctl/viz-empty" ]; then exit 0; fi
            |  cat "$ctl/canned.viz"
            |  echo "viz: canned.flac - 0.2 s, 3 frames" >&2
            |  exit 0
            |fi
            |
            |pass="${'$'}3"
            |if [ -f "$ctl/fail-all" ] || [ -f "$ctl/fail-${'$'}pass" ]; then
            |  echo "Traceback (most recent call last): RuntimeError: ${'$'}pass could not reach the service" >&2
            |  exit 2
            |fi
            |if [ -f "$ctl/pertrack-${'$'}pass" ]; then
            |  echo "[${'$'}pass] FAILED #7 /music/x.flac: ffprobe rc=1"
            |fi
            |echo "[${'$'}pass] done: 1 ok, 0 failed"
            |exit 0
            |""".trimMargin())
        f.python.toFile().setExecutable(true)
        return f
    }

    private fun clean(f: Fake) = f.dir.toFile().deleteRecursively()

    private fun track(id: Int = 7, path: String = "/music/x.flac") =
        MusicDb.TrackFile(id, path, 1_700_000_000_000L, "X", "A", "Al", 200_000)

    /** Collects everything this module logs, so the loud-failure rules can be
     *  asserted instead of taken on trust. */
    private fun <T> withLog(block: (List<String>) -> T): T {
        val lines = CopyOnWriteArrayList<String>()
        val sink = Log.Sink { level, tag, msg -> if (tag == "music-enrich") lines.add("$level $msg") }
        Log.addSink(sink)
        try {
            return block(lines)
        } finally {
            Log.removeSink(sink)
        }
    }

    // ------------------------------------------------------------- the chain

    @Test
    fun enrichRunsEveryPassInOrderAnnouncingEachOne() {
        val f = fixture()
        try {
            val phases = ArrayList<String>()
            Enrich(f.python.toString(), f.audio).enrich(7) { phases.add(it) }

            assertEquals(
                listOf("tags", "musicbrainz", "lyrics", "audio", "profile", "embed", "dedupe"),
                Enrich.PASSES, "the ingest chain is the MUSIC.md §9.5 order")
            assertEquals(Enrich.PASSES, phases, "phase() is told each pass, in order, before it runs")

            val calls = f.calls()
            assertEquals(Enrich.PASSES.size, calls.size)
            calls.forEachIndexed { i, c ->
                assertEquals(
                    listOf("-m", "enrich.run_enrichment", Enrich.PASSES[i], "--track-id", "7"),
                    c.args, "pass ${Enrich.PASSES[i]} argv")
                assertEquals(f.audio.toRealPath().toString(), Path.of(c.cwd).toRealPath().toString(),
                    "the runner runs with audioDir as its working directory")
            }
        } finally { clean(f) }
    }

    @Test
    fun aFailingPassIsLoggedWithItsStderrAndTheChainKeepsGoing() {
        val f = fixture()
        try {
            f.mark("fail-musicbrainz")
            f.mark("fail-embed")
            val phases = ArrayList<String>()
            val logged = withLog { lines ->
                Enrich(f.python.toString(), f.audio).enrich(7) { phases.add(it) }
                lines.toList()
            }
            assertEquals(Enrich.PASSES, phases, "every later pass still ran")
            assertEquals(Enrich.PASSES.size, f.calls().size)

            val errs = logged.filter { it.startsWith("ERROR") }
            assertEquals(2, errs.size, "one loud line per failing pass: $logged")
            assertTrue(errs.any { it.contains("'musicbrainz'") && it.contains("exited 2") &&
                it.contains("could not reach the service") },
                "the failing pass, its exit code and the head of its stderr: $errs")
            assertTrue(logged.any { it.startsWith("WARN") && it.contains("2 of 7") &&
                it.contains("musicbrainz embed") },
                "a summary naming what to re-run: $logged")
        } finally { clean(f) }
    }

    @Test
    fun everyPassFailingIsRaisedToTheCallerAfterTheWholeChainRan() {
        val f = fixture()
        try {
            f.mark("fail-all")
            val phases = ArrayList<String>()
            val e = assertFailsWith<IllegalStateException> {
                Enrich(f.python.toString(), f.audio).enrich(7) { phases.add(it) }
            }
            assertEquals(Enrich.PASSES, phases, "the chain still ran end to end before raising")
            assertContains(e.message!!, "every enrichment pass failed for track 7")
            assertContains(e.message!!, "still plays")
        } finally { clean(f) }
    }

    @Test
    fun perTrackFailuresPrintedOnStdoutAreSurfacedEvenThoughTheRunnerExitsZero() {
        val f = fixture()
        try {
            f.mark("pertrack-audio")
            val logged = withLog { lines ->
                Enrich(f.python.toString(), f.audio).enrich(7) { }
                lines.toList()
            }
            assertTrue(logged.none { it.startsWith("ERROR") }, "exit 0 is not an error: $logged")
            assertTrue(logged.any { it.startsWith("WARN") && it.contains("pass 'audio'") &&
                it.contains("FAILED #7") && it.contains("ffprobe rc=1") },
                "the runner's own FAILED line reaches the log: $logged")
        } finally { clean(f) }
    }

    @Test
    fun anAbsentInterpreterIsRaisedOnceWithWhatToFix() {
        val f = fixture()
        try {
            val missing = f.dir.resolve("no-such-python").toString()
            val phases = ArrayList<String>()
            val e = assertFailsWith<IllegalStateException> {
                Enrich(missing, f.audio).enrich(7) { phases.add(it) }
            }
            assertEquals(listOf("tags"), phases, "it stops at the first pass, not seven times")
            assertContains(e.message!!, "could not start")
            assertContains(e.message!!, "musicPython")
        } finally { clean(f) }
    }

    @Test
    fun enrichRefusesAnImpossibleTrackId() {
        val f = fixture()
        try {
            assertFailsWith<IllegalArgumentException> { Enrich(f.python.toString(), f.audio).enrich(0) { } }
            assertTrue(f.calls().isEmpty(), "nothing was run")
        } finally { clean(f) }
    }

    // --------------------------------------------------------------- viz

    @Test
    fun vizReturnsTheBlobItsStdoutCarriedAndItDecodes() {
        val f = fixture()
        try {
            val bytes = Enrich(f.python.toString(), f.audio).viz(track())
            assertNotNull(bytes)
            assertContentEqualsViz(canned(), VizData.decode(bytes))

            val calls = f.calls()
            assertEquals(1, calls.size)
            assertEquals(listOf("-m", "viz", "/music/x.flac"), calls[0].args,
                "the path is one argument, even with a space in the working directory")
            assertEquals(f.audio.toRealPath().toString(), Path.of(calls[0].cwd).toRealPath().toString())
        } finally { clean(f) }
    }

    @Test
    fun vizIsNullAndLoudWhenLibrosaIsMissing() {
        val f = fixture()
        try {
            f.mark("viz-fail")
            val logged = withLog { lines ->
                assertNull(Enrich(f.python.toString(), f.audio).viz(track()))
                lines.toList()
            }
            assertTrue(logged.any { it.startsWith("ERROR") && it.contains("exited 1") &&
                it.contains("track 7") && it.contains("No module named 'librosa'") },
                "the exit code and the head of stderr are logged: $logged")
        } finally { clean(f) }
    }

    @Test
    fun vizIsNullAndLoudWhenItWritesNothing() {
        val f = fixture()
        try {
            f.mark("viz-empty")
            val logged = withLog { lines ->
                assertNull(Enrich(f.python.toString(), f.audio).viz(track()))
                lines.toList()
            }
            assertTrue(logged.any { it.startsWith("ERROR") && it.contains("wrote no blob") },
                "exit 0 with an empty blob is still a failure: $logged")
        } finally { clean(f) }
    }

    @Test
    fun vizIsNullAndLoudWhenThereIsNoInterpreter() {
        val f = fixture()
        try {
            val logged = withLog { lines ->
                assertNull(Enrich(f.dir.resolve("no-such-python").toString(), f.audio).viz(track()))
                lines.toList()
            }
            assertTrue(logged.any { it.startsWith("ERROR") && it.contains("could not start") &&
                it.contains("musicPython") }, "it says what to fix: $logged")
        } finally { clean(f) }
    }

    // -------------------------------------------------------------- detail

    @Test
    fun theStderrHeadSaysHowMuchItLeftOut() {
        assertEquals("short", Enrich.head("  short \n"))
        val long = "e".repeat(500)
        val h = Enrich.head(long, 100)
        assertTrue(h.startsWith("e".repeat(100)))
        assertContains(h, "+400 more chars", message = "nothing is dropped without saying so")
    }

    private fun assertContentEqualsViz(want: VizData, got: VizData) {
        assertEquals(want.fps, got.fps)
        assertEquals(want.bands, got.bands)
        assertEquals(want.frameCount, got.frameCount)
        assertEquals(want.rmsCount, got.rmsCount)
        assertTrue(want.beatsMs.contentEquals(got.beatsMs))
        for (fr in 0 until want.frameCount) for (b in 0 until want.bands) {
            assertEquals(want.level(fr, b), got.level(fr, b), "frame $fr band $b")
        }
        for (i in 0 until want.rmsCount) {
            assertEquals(want.rmsAt(i * 20L), got.rmsAt(i * 20L), "rms slot $i")
        }
    }
}
