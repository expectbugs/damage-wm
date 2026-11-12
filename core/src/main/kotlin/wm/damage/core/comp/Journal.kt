package wm.damage.core.comp

import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import wm.damage.core.transport.DisplayOp
import wm.damage.core.util.Log

/**
 * Deterministic frame journal (DESIGN.md §9.2): every flush with its rects,
 * bytes, fids and ack latency, one JSON object per line — replayable into the
 * simulator, and how you debug something you cannot attach a debugger to.
 */
class Journal(private val path: Path?) : AutoCloseable {
    private var out: Writer? = open()
    private var dead = false

    private fun open(): Writer? = path?.let {
        // from here on a writer holds this path in this process: [rotateAtStart] refuses after it
        touched.putIfAbsent(keyOf(it), OPENED)
        it.parent?.let { p -> Files.createDirectories(p) }
        Files.newBufferedWriter(it, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /**
     * One line per submitted flush. [via] is the transport's name at submit
     * time (2026-09-05: the journal's two latency regimes turned out to be two
     * RADIO PATHS — PC-direct BlueZ and the phone — and nothing in the file
     * said which; `HANDOFF.md` §32). [handleMs] is the shell loop's time from
     * taking the message to reaching the flush (every paint the message
     * caused); [assembleMs] is the compositor's diff + partition + compress.
     * Both are host CPU, on the loop, and neither had ever been measured.
     */
    /** The host's time per flush, split (2026-09-05, `HANDOFF.md` §34/§35):
     *  [handleMs] = message taken → submit, the WHOLE host time INCLUDING the
     *  assemble (⚠ §33.3 and §34.2 read it as excluding it and so counted the
     *  assemble twice — corrected in §35); inside it [handlerMs] = the
     *  message's own handler (the paints it caused), [mirrorMs] = the
     *  belief-vs-glass scan a completion runs, and [assembleMs] = the
     *  compositor, inside which [truthMs] = the per-lens truth render and
     *  [compressMs] over [compressN] memo-missing compressions — the rest of
     *  the assemble is the diff and the plan. */
    data class Timing(val handleMs: Long = -1, val handlerMs: Long = -1, val mirrorMs: Long = -1,
        val assembleMs: Long = -1, val truthMs: Long = -1, val compressMs: Long = -1, val compressN: Int = -1,
        /** Inside the pump before the assemble: the slide steps, the chrome
         *  sync, the overlays (wheel, notification); and across the whole
         *  message, the time inside the rasterizer's draw (§34, second cut). */
        val slidesMs: Long = -1, val chromeMs: Long = -1, val overlaysMs: Long = -1, val textMs: Long = -1,
        /** §41: rects this flush shipped as cached draws, and why the others
         *  did not ("reason=count,…" — empty when every rect was pixels for
         *  want of records, or the cache is off). */
        val cached: Int = -1, val cacheMiss: String = "",
        /** §66: rects shipped as staged draws (mode 17 under a clip) — -1 when the assemble did not say. */
        val staged: Int = -1,
        /** §73: characters the host drew since the last flush that a cached run could have held
         *  had the face been packed with them — what a first-seen character costs, counted. */
        val unpacked: Long = -1,
        /** §73: 1 when the pump that assembled this flush followed a ring event or a typed line —
         *  a gesture's own flush — 0 for an idle tick, a push or an animation frame; -1 unsaid.
         *  The report's per-gesture table was 91 % idle chrome ticks without it (2026-11-09). */
        val input: Int = -1)

    fun flushSubmitted(id: Long, a: Compositor.Assembled, label: String,
        via: String = "?", timing: Timing = Timing()) =
        flushSubmitted(id, a.epoch, a.ops, label, via, timing)

    /** The same line for a flush the compositor did not assemble — an atlas
     *  chunk (§42: those rode the journal as `atlas` notes only, so the report
     *  counted them as `via ?` with no label). */
    fun flushSubmitted(id: Long, epoch: Long, opList: List<DisplayOp>, label: String,
        via: String = "?", timing: Timing = Timing()) {
        val ops = opList.joinToString(",") { op ->
            when (op) {
                is DisplayOp.Keyframe -> """{"op":"keyframe","bytes":${op.payload.size}}"""
                is DisplayOp.Delta ->
                    """{"op":"delta","box":"${op.box}","bytes":${op.payload.size},"d":${op.disparity}}"""
                is DisplayOp.Copy -> """{"op":"copy","src":"${op.src}","dst":"${op.dst}","d":${op.disparity}}"""
                is DisplayOp.CopyPair -> """{"op":"copypair","sl":"${op.srcL}","dl":"${op.dstL}","sr":"${op.srcR}","dr":"${op.dstR}"}"""
                is DisplayOp.StereoPair ->
                    """{"op":"stereopair","l":"${op.left}","r":"${op.right}","bytes":${op.payload.size}}"""
                is DisplayOp.CacheWrite -> """{"op":"cachewrite","bytes":${op.payload.size}}"""
                is DisplayOp.DrawText -> """{"op":"drawtext","x":${op.x},"y":${op.y},"chars":${op.text.size}}"""
                is DisplayOp.DrawImage -> """{"op":"drawimage","x":${op.x},"y":${op.y}}"""
                is DisplayOp.DrawText2 -> """{"op":"drawtext2","xl":${op.xL},"xr":${op.xR},"y":${op.y},"chars":${op.text.size}}"""
                is DisplayOp.DrawImage2 -> """{"op":"drawimage2","xl":${op.xL},"xr":${op.xR},"y":${op.y},"w":${op.w},"h":${op.h}}"""
                is DisplayOp.Fill -> """{"op":"fill","l":"${op.left}","r":"${op.right}","level":${op.level}}"""
                is DisplayOp.Clip -> """{"op":"clip","l":"${op.left}","r":"${op.right}"}"""
                is DisplayOp.PresentHint -> """{"op":"hint","y0":${op.y0},"y1":${op.y1}}"""
                is DisplayOp.CacheWrite2 -> """{"op":"cachewrite2","bytes":${op.payload.size}}"""
            }
        }
        val tm = timing
        write("""{"t":${System.currentTimeMillis()},"ev":"submit","id":$id,"epoch":$epoch,"label":${json(label)},""" +
            """"via":${json(via)},"handleMs":${tm.handleMs},"handlerMs":${tm.handlerMs},"mirrorMs":${tm.mirrorMs},""" +
            """"assembleMs":${tm.assembleMs},"truthMs":${tm.truthMs},"compressMs":${tm.compressMs},"compressN":${tm.compressN},""" +
            """"slidesMs":${tm.slidesMs},"chromeMs":${tm.chromeMs},"overlaysMs":${tm.overlaysMs},"textMs":${tm.textMs},""" +
            """"cached":${tm.cached},"cacheMiss":${json(tm.cacheMiss)},"staged":${tm.staged},"unpacked":${tm.unpacked},"in":${tm.input},"ops":[$ops]}""")
    }

    fun flushDone(id: Long, ok: Boolean, ackMs: Long, bytes: Int, error: String?) {
        write("""{"t":${System.currentTimeMillis()},"ev":"done","id":$id,"ok":$ok,"ackMs":$ackMs,"bytes":$bytes""" +
            (error?.let { ""","error":${json(it)}""" } ?: "") + "}")
    }

    /** `FIRMWARE.md` §3 (F1.3): one panel transfer of a Damage frame, as the glasses report
     *  it while the PRESENTED flag is armed — the number the eye waits for, which the ack
     *  never carried (the ack precedes the transfer). One line per present; the feature is
     *  armed for measurement sessions, not all day, so the journal stays small. */
    fun present(seq: Long, workerUs: Long, copyUs: Long, transferUs: Long, path: Long = 0) {
        write("""{"t":${System.currentTimeMillis()},"ev":"present","seq":$seq,"workerUs":$workerUs,"copyUs":$copyUs,"transferUs":$transferUs,"path":$path}""")
    }

    fun note(kind: String, detail: String) {
        write(noteLine(kind, detail))
    }

    @Synchronized
    private fun write(line: String) {
        if (path == null || dead) return
        try {
            // The keeper restarts sessions forever, and a restart used to write into
            // the stream a previous stop() had closed -- which failed on EVERY line
            // and logged EVERY time, filling the log at exactly the moment the
            // journal existed to explain (first light, 2026-08-30). Reopen instead:
            // the file is opened APPEND, so journaling simply continues across a
            // reconnect, which is when it is most worth having.
            val o = out ?: open()?.also { out = it } ?: return
            o.write(line)
            o.write("\n")
            o.flush()
        } catch (e: java.io.IOException) {
            // Loud, and once. Repeating an unrecoverable failure per line is not
            // "loud and proud", it is a denial of service against the operator's
            // ability to read the errors that matter.
            dead = true
            out = null
            Log.e("journal", "write failed; JOURNALLING IS OFF for the rest of this run", e)
        }
    }

    /** Closes the underlying stream. The journal stays usable: the next write
     *  reopens it in append mode, so a session restart keeps journalling. */
    @Synchronized
    override fun close() {
        try {
            out?.close()
        } catch (e: java.io.IOException) {
            Log.w("journal", "close failed: ${e.message}")
        }
        out = null
    }

    companion object {
        /** `HANDOFF.md` §75.1 (Adam's ruling; the figure is the lead's): a journal past this size at a
         *  process start is set aside under a dated name and a fresh file is started. */
        const val ROTATE_BYTES = 64L * 1024 * 1024

        private const val EXT = ".jsonl"
        private val ARCHIVE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val ARCHIVE_MID = Regex("""\d{8}-\d{6}""")

        /** What this process has done with a journal path, keyed by [keyOf]: [ASKED] = [rotateAtStart]
         *  ran for it first (once per process), [OPENED] = a writer opened it first. One map and one
         *  key function for both sites, so the two cannot disagree about which file a path names. */
        private const val ASKED = 1
        private const val OPENED = 2
        private val touched = ConcurrentHashMap<Path, Int>()
        private fun keyOf(p: Path): Path = p.toAbsolutePath().normalize()

        /** One `note` line (no newline) — the one definition of its form, for [note] and [appendNote]. */
        private fun noteLine(kind: String, detail: String): String =
            """{"t":${System.currentTimeMillis()},"ev":"note","kind":${json(kind)},"detail":${json(detail)}}"""

        /** RFC 8259: ALL C0 controls must be escaped — exception messages carry
         *  \r and \t, and one bad line would break the replay tooling exactly at
         *  the failure record it exists to explain. */
        private fun json(s: String): String {
            val b = StringBuilder(s.length + 2)
            b.append('"')
            for (ch in s) when {
                ch == '\\' -> b.append("\\\\")
                ch == '"' -> b.append("\\\"")
                ch == '\n' -> b.append("\\n")
                ch == '\r' -> b.append("\\r")
                ch == '\t' -> b.append("\\t")
                ch.code < 0x20 -> b.append("\\u%04x".format(ch.code))
                else -> b.append(ch)
            }
            b.append('"')
            return b.toString()
        }

        private fun stemOf(live: Path): String = live.fileName.toString().removeSuffix(EXT)

        /** The name a journal set aside at [at] takes: `journal-20261110-213045.jsonl` for `journal.jsonl`. */
        fun archiveName(live: Path, at: LocalDateTime): String = stemOf(live) + "-" + at.format(ARCHIVE_STAMP) + EXT

        /** Is [name] an archive name of [live]? A FULL match of `<stem>-<8 digits>-<6 digits>.jsonl`:
         *  the replica serves an archive by a name from the request, and nothing with a separator,
         *  a dot segment or another file's name passes this. */
        fun isArchiveOf(live: Path, name: String): Boolean {
            val stem = stemOf(live)
            if (!name.startsWith("$stem-") || !name.endsWith(EXT)) return false
            return ARCHIVE_MID.matches(name.substring(stem.length + 1, name.length - EXT.length))
        }

        /** The archives beside [live], oldest first (the name carries the date, so by name). */
        fun archives(live: Path): List<Path> {
            val dir = keyOf(live).parent ?: return emptyList()
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.newDirectoryStream(dir).use { ds ->
                ds.filter { isArchiveOf(live, it.fileName.toString()) && Files.isRegularFile(it) }
                    .sortedBy { it.fileName.toString() }
            }
        }

        /** How many archives [live] has beside it and their total bytes; throws what listing them throws. */
        private fun archivesCounted(live: Path): Pair<Int, Long> {
            val old = archives(live)
            var total = 0L
            for (a in old) total += Files.size(a)
            return old.size to total
        }

        /** "N archive(s) beside it, T B" for [live] (R2 item 26): the start's `process` note carries it
         *  on both hosts, so the archives' growth is read in the journal itself, not only in a log line
         *  (the phone's log ring is 4,000 lines). It never throws: a count that fails says so instead. */
        fun archivesSaid(live: Path): String = try {
            val (n, total) = archivesCounted(live)
            "$n archive(s) beside it, $total B"
        } catch (e: Exception) {
            "its archives could not be counted (${e::class.simpleName}: ${e.message})"
        }

        /**
         * One note appended to the journal at [live] by a caller that holds no [Journal] (the start's
         * rotation, the pickup of the last run's uncaught-error record, the phone's read of why its
         * last process ended). Returns whether the line LANDED: a caller that would otherwise mark
         * something as said (rename a pending record, advance a "seen" stamp) keeps it for the next
         * start when this is false. The line goes out in one write of an append-mode file, so a
         * [Journal] writing the same file cannot land inside it. A failure is one ERROR line.
         */
        fun appendNote(live: Path, kind: String, detail: String): Boolean {
            touched.putIfAbsent(keyOf(live), OPENED)
            return try {
                live.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(live, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use {
                    it.write((noteLine(kind, detail) + "\n").toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: java.io.IOException) {
                Log.e("journal", "a $kind note could not be appended to ${live.fileName}", e)
                false
            }
        }

        /**
         * The journal's rotation, at a PROCESS START (`HANDOFF.md` §75.1): a file past [limit] is
         * renamed to [archiveName] beside it and a fresh file is started whose first line names the
         * archive. Nothing is ever deleted; the archives are served by name (`ReplicaServer`) and
         * copied off the phone by `tools/journal_pull.py`. Returns the archive, or null when nothing
         * was set aside. It never throws.
         *
         * Called from the two process starts only — the phone's `ShellService.onCreate` and the
         * desktop's `runShell` — before any [Journal] or server exists in the process. NOT from the
         * constructor: a `Journal` is built at every stack rebuild, with a fetch possibly in flight
         * and the old stack's stop still writing. NOT from a tool: `--selfcheck`, `--snapshot` and
         * the `--*-check` entries must never rename the service's file.
         *  - Once per process and path; a call after a writer opened the path is refused and said
         *    (a rename under a live writer is what the placement avoids). There is no retry inside
         *    a run: a rename that failed is asked again by the next process start.
         *  - `Files.move` with NO options: it refuses an existing target, and in one directory it is
         *    a rename. (`ATOMIC_MOVE` would replace an existing target on Linux.) A taken name is
         *    therefore a failed rotation, said, and the next start tries under its own second.
         *  - ANOTHER process holding the file open (a second JVM on the same data directory, which
         *    the daily setup never runs) keeps appending to the ARCHIVE: a `Journal` until its next
         *    `close()`, and a writer that is never closed (the PC's `standbyJournal`) for the life of
         *    that process. No line is lost; an archive can grow after its date, which is why the
         *    pull compares sizes and never takes an archive as final.
         *  - Every start says the live size and the archives' count and total: nothing deletes them,
         *    so that line is where their growth is read; the start's `process` note carries the count
         *    and total too ([archivesSaid], R2), so the journal holds them.
         */
        fun rotateAtStart(live: Path, limit: Long = ROTATE_BYTES, now: LocalDateTime = LocalDateTime.now(),
                          move: (Path, Path) -> Unit = { a, b -> Files.move(a, b) }): Path? {
            try {
                when (touched.putIfAbsent(keyOf(live), ASKED)) {
                    null -> {}
                    ASKED -> return null
                    else -> {
                        Log.w("journal", "rotation asked after ${live.fileName} was opened in this process: " +
                            "not done, it runs at the next process start")
                        return null
                    }
                }
                val size = if (Files.isRegularFile(live)) Files.size(live) else -1L      // read once
                val (count, total) = archivesCounted(live)
                Log.i("journal", (if (size < 0) "no ${live.fileName} yet" else "${live.fileName} is $size B") +
                    "; $count archive(s) beside it, $total B (set aside past $limit B at a process start, never deleted)")
                if (size <= limit) return null
                val target = live.resolveSibling(archiveName(live, now))
                try {
                    move(live, target)
                } catch (e: Exception) {
                    Log.e("journal", "the journal is $size B and could not be set aside as ${target.fileName} — it keeps growing", e)
                    appendNote(live, "journal", "rotation FAILED: $size B, wanted ${target.fileName}: ${e::class.simpleName}: ${e.message}")
                    return null
                }
                // the fresh file exists from this line on, and its first line names the archive
                val said = appendNote(live, "journal", "rotated at this process start: the $size B before this line are in ${target.fileName}")
                Log.i("journal", "rotated: $size B set aside as ${target.fileName}" +
                    (if (said) "" else "; the fresh file's first line was not written (the error above)"))
                return target
            } catch (e: Exception) {
                Log.e("journal", "the rotation check of ${live.fileName} did not finish — the journal is left as it is", e)
                return null
            }
        }
    }
}
