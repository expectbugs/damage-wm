package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertTrue
import wm.damage.core.shell.TxBenchCheck

/**
 * C3 stage TXB: the probe `txbench`'s records for Settings' Global on the PC's own faces (measured on the PC; the phone's
 * faces are the same files, its rasterizer differs) — both phases inside his room: Main's staging ends at 158,432 and the
 * top 64 KiB starts at 196,608 on 256 KiB (M-glass, the TX spec §1.2). Context for the reader: Adam's own glasses on his
 * own firmware fork; display-rendering work.
 */
class TxBenchSizeTest {
    @Test
    fun globalsRecordsFitHisRoomOnThePcsFaces_TXB() {
        val at = 158_432; val end = 196_608
        val bad = ArrayList<String>()
        for (h in listOf(288, 352)) {
            val b = TxBenchCheck.global(AwtText(), h, at, 256 * 1024)
            println("TXB size (AwtText, height $h): ${b.cells} cells in ${b.faces} faces; phase A ${b.bytes(0)} B to ${b.end(0)}, " +
                "phase B ${b.bytes(1)} B to ${b.end(1)}; room ${end - at} B from $at")
            for (k in 0..1) if (b.end(k) > end) bad += "height $h phase ${"AB"[k]}: ${b.end(k) - at} B past the room's ${end - at} B"
        }
        assertTrue(bad.isEmpty(), "the probe's records do not fit his room: $bad")
    }
}
