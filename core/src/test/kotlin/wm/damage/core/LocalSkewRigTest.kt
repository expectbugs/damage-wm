package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertTrue
import wm.damage.core.local.Taps
import wm.damage.core.transport.Arm

/**
 * B083 (2d), the instrument for his "if I scroll very quickly, the left and right lenses get out of sync and the behavior
 * gets janky until I stop" (APK 0.81, 2026-11-19): [LocalHarness]'s rules, batch composer and the simulator's two lenses,
 * with the ring's event and each of the phone's messages delivered to each lens on its own — the later lens by a skew drawn
 * per notch and per message, 0..80 ms, LEFT later or RIGHT later — and fast bursts at 40–150 ms gaps. Sampled after every
 * event and every engine wake (8 ms): whether the two panels differ over the list's band, by a step (a motion still
 * drawing on either lens) or by a whole row (both at rest, showing different rows), for how long, and what ran when they
 * agreed again. The motion is his phone's (T 6 at P 46, 0.81's).
 *
 * Rule sets: 0.82's (an unreported scroll message applied at its arrival), 2b (held for its report). Each cell runs
 * [SEEDS] seeded bursts (seed = cell base + i, so a cell's numbers repeat exactly); his journals' share of stock messages
 * before their report (27 of 332) is the reversal rate. Printed as a table (`skew-rig:` lines); asserted: only that every
 * burst ended with both lenses equal to the phone's truth at rest, which the fuzz's invariants already say.
 */
class LocalSkewRigTest {
    private companion object {
        val SEEDS = System.getenv("SKEW_RIG_SEEDS")?.toInt() ?: 30
        const val NOTCHES = 6
        const val STEP = 1; const val ROW = 2
    }

    class Result(var msStep: Long = 0, var msRow: Long = 0, var longest: Long = 0, var stretches: Int = 0,
                 val endedBy: java.util.TreeMap<String, Int> = java.util.TreeMap(), var notAtRest: Int = 0, var bursts: Int = 0)

    /** One burst: [NOTCHES] notches down, [gap] ms apart, from row 0 of 12 rows ([wrap] false) or across the end of 8 rows
     *  (from row 5: the third notch is the edge, Main's wrap). */
    private fun burst(seed: Long, rule: Int, leftLate: Boolean, skewMax: Int, gap: Int, wrap: Boolean, into: Result, trace: Boolean = false) {
        val h = LocalHarness(seed, Taps.REPORT, n0 = if (wrap) 8 else 12, tScroll = 6, period = 46)
        if (trace) h.trace = { println("rule $rule $it") }
        h.rules.holdStock = rule >= 1
        if (wrap) { repeat(5) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }; h.rest() }
        h.stockFirst = 27.0 / 332; h.stockLeadMax = 60
        h.skewMax = skewMax; h.leftLate = leftLate
        var state = 0; var since = h.now; var stretchFrom = -1L
        // the band's pictures are solid rows (a step draws the new row's record): "a step apart" is the panels differing
        // while a motion still draws on either lens, "a row apart" differing with both at rest (or holding their last step)
        fun moving(a: Arm) = h.sim.motionForTest(a).slots.any { it != null && it.drawing && it.d < it.T }
        fun classify(): Int = if (!h.bandsDiffer()) 0 else if (moving(Arm.LEFT) || moving(Arm.RIGHT)) STEP else ROW
        h.sampler = {
            val s = classify()
            val dt = h.now - since
            if (state == STEP) into.msStep += dt else if (state == ROW) into.msRow += dt
            if (s != state) h.trace?.invoke("[${h.now}] panels ${listOf("agree", "a step apart", "a row apart")[s]} (L ${h.shownOn(Arm.LEFT)} R ${h.shownOn(Arm.RIGHT)}) after ${h.what}")
            if (s != 0 && stretchFrom < 0) stretchFrom = h.now
            if (s == 0 && stretchFrom >= 0) {
                into.longest = maxOf(into.longest, h.now - stretchFrom); into.stretches++
                val w = h.what
                val key = if (" gets " !in w) w else w.substringBefore(" gets ") + " gets " + w.substringAfter(" gets ").split(' ')
                    .filter { it in setOf("COMMIT", "DISCARD", "SETTLE", "PLAY", "CLOSE", "keyframe", "draw") }.distinct().joinToString("+").ifEmpty { "table" }
                into.endedBy.merge(key, 1, Int::plus)
                stretchFrom = -1
            }
            state = s; since = h.now
        }
        repeat(NOTCHES) { h.gesture(1, answer = true); h.runUntil(h.now + gap) }
        h.rest()
        h.sampler = null
        if (stretchFrom >= 0) { into.longest = maxOf(into.longest, h.now - stretchFrom); into.endedBy.merge("(still differing at rest)", 1, Int::plus) }
        into.bursts++
        if (h.truthMismatches().isNotEmpty()) into.notAtRest++
    }

    /** For reading a cell (`SKEW_RIG_CELL=wrap|mid,LEFT|RIGHT,<gap>`): each seed's numbers under both rule sets, and with
     *  `SKEW_RIG_TRACE=<seed>` that seed's whole record. Passes when the variable is unset. */
    @Test
    fun oneCellPerSeed() {
        val cell = System.getenv("SKEW_RIG_CELL")?.split(',') ?: return
        val wrap = cell[0] == "wrap"; val leftLate = cell[1] == "LEFT"; val gap = cell[2].toInt()
        val base = 9_000_000L + (if (wrap) 100_000 else 0) + gap * 1_000L + (if (leftLate) 0 else 500) + 80
        val trace = System.getenv("SKEW_RIG_TRACE")?.toLong()
        for (i in 0 until SEEDS) for (rule in listOf(0, 1)) {
            val r = Result()
            burst(base + i, rule, leftLate, 80, gap, wrap, r, trace == base + i)
            println("cell seed ${base + i} rule $rule: step ${r.msStep} row ${r.msRow} longest ${r.longest} ended ${r.endedBy}")
        }
    }

    @Test
    fun theTwoLensesUnderASkewedLink_2d() {
        val rules = (System.getenv("SKEW_RIG_RULES") ?: "0,1").split(',').map { it.trim().toInt() }
        val ruleName = mapOf(0 to "0.82", 1 to "2b")
        val out = StringBuilder()
        var notAtRest = 0
        for (wrap in listOf(false, true)) for (rule in rules) for ((skew, leftLate) in listOf(0 to true, 80 to true, 80 to false)) for (gap in listOf(40, 70, 100, 150)) {
            val r = Result()
            val base = 9_000_000L + (if (wrap) 100_000 else 0) + gap * 1_000L + (if (leftLate) 0 else 500) + skew
            for (i in 0 until SEEDS) burst(base + i, rule, leftLate, skew, gap, wrap, r)
            notAtRest += r.notAtRest
            val dir = if (skew == 0) "none (L first, 4-11)" else if (leftLate) "LEFT late 0-$skew" else "RIGHT late 0-$skew"
            out.append("skew-rig: ${if (wrap) "wrap" else "mid "} | rules ${ruleName[rule]} | skew $dir | gap $gap | per burst: step ${r.msStep / r.bursts} ms, " +
                "row ${r.msRow / r.bursts} ms | stretches ${r.stretches} | longest ${r.longest} ms | ended by ${r.endedBy} | not at rest ${r.notAtRest}/${r.bursts}\n")
        }
        println(out)
        assertTrue(notAtRest == 0, "bursts that did not end with both lenses equal to the truth: $notAtRest\n$out")
    }
}
