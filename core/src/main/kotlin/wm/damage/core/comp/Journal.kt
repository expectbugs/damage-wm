package wm.damage.core.comp

import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        it.parent?.let { p -> Files.createDirectories(p) }
        Files.newBufferedWriter(it, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun flushSubmitted(id: Long, a: Compositor.Assembled, label: String) {
        val ops = a.ops.joinToString(",") { op ->
            when (op) {
                is DisplayOp.Keyframe -> """{"op":"keyframe","bytes":${op.payload.size}}"""
                is DisplayOp.Delta ->
                    """{"op":"delta","box":"${op.box}","bytes":${op.payload.size},"d":${op.disparity}}"""
                is DisplayOp.Copy -> """{"op":"copy","src":"${op.src}","dst":"${op.dst}","d":${op.disparity}}"""
                is DisplayOp.StereoPair ->
                    """{"op":"stereopair","l":"${op.left}","r":"${op.right}","bytes":${op.payload.size}}"""
            }
        }
        write("""{"t":${System.currentTimeMillis()},"ev":"submit","id":$id,"epoch":${a.epoch},"label":${json(label)},"ops":[$ops]}""")
    }

    fun flushDone(id: Long, ok: Boolean, ackMs: Long, bytes: Int, error: String?) {
        write("""{"t":${System.currentTimeMillis()},"ev":"done","id":$id,"ok":$ok,"ackMs":$ackMs,"bytes":$bytes""" +
            (error?.let { ""","error":${json(it)}""" } ?: "") + "}")
    }

    fun note(kind: String, detail: String) {
        write("""{"t":${System.currentTimeMillis()},"ev":"note","kind":${json(kind)},"detail":${json(detail)}}""")
    }

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

    @Synchronized
    private fun write(line: String) {
        if (path == null || dead) return
        try {
            // The keeper restarts sessions forever, and a restart used to write into
            // the stream a previous stop() had closed -- which failed on EVERY line
            // and logged EVERY time, flooding the log at exactly the moment the
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
}
