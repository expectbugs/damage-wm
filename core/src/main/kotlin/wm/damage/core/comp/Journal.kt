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
class Journal(path: Path?) : AutoCloseable {
    private val out: Writer? = path?.let {
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
        val o = out ?: return
        try {
            o.write(line)
            o.write("\n")
            o.flush()
        } catch (e: java.io.IOException) {
            Log.e("journal", "write failed", e)
        }
    }

    override fun close() {
        out?.close()
    }
}
