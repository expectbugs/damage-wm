package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.Transitions
import wm.damage.core.motion.Transitions.Tier
import wm.damage.core.shell.Notifications
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.sim.PixelLoops
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes

/**
 * C3 ML1 (decisions BZ): the firmware model's fast pixel loops ([wm.damage.core.sim.FastPixelLoops]) against the plain
 * loops they replaced, kept here ([Plain]). Context for the reader: Adam's own glasses on his own firmware fork; the phone
 * keeps a private copy of this model as its mirror and applies every message it writes to it inside the write call — on
 * his phone those per-pixel loops held the image lane about 170 ms per motion PLAY (ML1, measured in his journal). Nothing
 * here contacts a device.
 *
 * The replay: a stage-5 session on the transition rig's tee'd transport (the phone's shape), every effect played as a
 * Select and a Back at 352 and 480, the silent clock both ways, Main's notches to its wrap, a notice — every packet the
 * transport wrote, replayed into two fresh models, one with the plain loops in force; after every packet both lenses'
 * shadow, framebuffer, panel and texture cache, their present bookkeeping, the engine's slots and counters and every
 * notify either model sent must be the same. The loops: each fast form against its plain one on seeded random buffers
 * and rects — odd and even edges, rects past the panel and past the buffer (both must fail at the same pixel).
 */
class MirrorLoopsReplayTest {

    /** The loops as they were before ML1, verbatim — the reference the fast forms must equal. */
    internal object Plain : PixelLoops {
        /** Calls taken — the replay checks the plain model really ran these (a seam that ignored them would compare the
         *  fast loops with themselves). */
        val calls = java.util.concurrent.atomic.AtomicLong()
        private fun nib(buf: ByteArray, stride: Int, x: Int, y: Int): Int {
            val b = buf[y * stride + (x shr 1)].toInt() and 0xFF
            return if (x and 1 == 0) b shr 4 else b and 0x0F
        }
        private fun setNib(buf: ByteArray, stride: Int, x: Int, y: Int, v: Int) {
            val i = y * stride + (x shr 1)
            val b = buf[i].toInt() and 0xFF
            buf[i] = (if (x and 1 == 0) (v shl 4) or (b and 0x0F) else (b and 0xF0) or v).toByte()
        }
        override fun copyOutside(shadow: ByteArray, fb: ByteArray, stride: Int, rects: List<Rect>) {
            calls.incrementAndGet()
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                if (rects.any { x >= it.x && x < it.right && y >= it.y && y < it.bottom }) continue
                setNib(fb, stride, x, y, nib(shadow, stride, x, y))
            }
        }
        override fun copy(from: ByteArray, to: ByteArray, stride: Int, r: Rect) {
            calls.incrementAndGet()
            for (yy in r.y until r.bottom) for (xx in r.x until r.right) setNib(to, stride, xx, yy, nib(from, stride, xx, yy))
        }
        override fun fill(buf: ByteArray, stride: Int, r: Rect, level: Int) {
            calls.incrementAndGet()
            for (yy in r.y until r.bottom) for (xx in r.x until r.right) setNib(buf, stride, xx, yy, level)
        }
        override fun grab(buf: ByteArray, stride: Int, r: Rect): ByteArray =
            ByteArray(r.w * r.h) { nib(buf, stride, r.x + it % r.w, r.y + it / r.w).toByte() }
        override fun tightPack(buf: ByteArray, stride: Int, r: Rect): ByteArray {
            calls.incrementAndGet()
            val rb = (r.w + 1) / 2
            val out = ByteArray(rb * r.h)
            for (yy in 0 until r.h) for (xx in 0 until r.w) {
                val v = nib(buf, stride, r.x + xx, r.y + yy)
                val i = yy * rb + xx / 2
                out[i] = (if (xx and 1 == 0) v shl 4 else (out[i].toInt() and 0xF0) or v).toByte()
            }
            return out
        }
        override fun unpack(cache: ByteArray, off: Int, w: Int, h: Int): ByteArray {
            val rb = (w + 1) / 2
            return ByteArray(w * h) { p ->
                val b = cache[off + (p / w) * rb + (p % w) / 2].toInt() and 0xFF
                (if ((p % w) and 1 == 0) b shr 4 else b and 0x0F).toByte()
            }
        }
        override fun blit(buf: ByteArray, stride: Int, px: ByteArray, w: Int, x0: Int, y0: Int, xA: Int, xB: Int, yA: Int, yB: Int, options: Int?) {
            val top = (options ?: 15) and 0x0F
            val inverse = options != null && options and 0x20 != 0
            val transparent = options != null && options and 0x10 != 0
            for (yy in yA until yB) for (xx in xA until xB) {
                val c = px[(yy - y0) * w + (xx - x0)].toInt()
                if (transparent && c == 0) continue
                setNib(buf, stride, xx, yy, if (options == null) c else ((if (inverse) 15 - c else c) * top) / 15)
            }
        }
        override fun levels(px: ByteArray, from: Int, count: Int, v: Byte) {
            var n = from
            repeat(count) { px[n++] = v }
        }
        override val rleRun = Zl.NibbleRun { dst, off, stride, rowNibbles, lastNibble, pos, v, n ->
            var p = pos
            var k = n
            var out = p
            while (k > 0) {
                if (p >= lastNibble) { out = -1L; break }
                val row = (p / rowNibbles).toInt()
                val inRow = (p - row * rowNibbles).toInt()
                val i = off + row * stride + (inRow shr 1)
                dst[i] = if (inRow and 1 == 0) (((v shl 4) or (dst[i].toInt() and 0x0F)).toByte())
                         else (((dst[i].toInt() and 0xF0) or v).toByte())
                p++; k--
                out = p
            }
            out
        }
    }

    // ------------------------------------------------------------------ the replay

    private class Rec(val arm: Arm, val p: ByteArray, val now: Long)

    private fun only(vararg ids: String): (wm.damage.core.shell.ShellSettings) -> wm.damage.core.shell.ShellSettings =
        { s -> Tier.entries.fold(s) { a, t -> a.withTier(t, effects = ids.toList()) } }

    /** One session at [height]: every packet the tee'd transport wrote, with the clock it wrote it on, and the private
     *  mirror's contract and features. */
    private suspend fun record(height: Int): Triple<List<Rec>, Int?, Int?> {
        val r = TransitionShellTest.Rig(stage = 5, kib256 = true, nRows = 9)
        val recs = java.util.Collections.synchronizedList(ArrayList<Rec>())
        try {
            (r.t as StartRig).onWrite = { a, p -> recs += Rec(a, p.copyOf(), System.currentTimeMillis()); p }
            r.upAt(height, only(Transitions.Effect.entries.first().id))
            r.shell.transitionRandom = java.util.Random(0x4D4C_3100L + height)
            suspend fun ends(what: String) = r.until(what) { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            suspend fun bound(what: String) = r.until(what) { r.onLoop { r.shell.mainBoundForTest().first } != null }
            for (e in Transitions.Effect.entries) {
                r.shell.updateSettings { only(e.id)(it) }
                r.settle("${e.id}: set")
                r.tap(); r.until("${e.id}: w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }; ends("${e.id}: Select ends"); r.settle("${e.id}: Select")
                r.doubleTap(); r.until("${e.id}: back on Main") { r.onLoop { r.shell.currentWindowId() } == null }; ends("${e.id}: Back ends")
                r.settle("${e.id}: Back"); bound("${e.id}: Main's list bound")
            }
            r.doubleTap(); r.until("the clock's ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 1 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("the clock")
            r.doubleTap(); r.until("Main's ends") { r.notes("transition").count { it.startsWith("owed: Lock") } == 2 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("Main"); bound("Main's list bound for the notches")
            repeat(11) { r.fire(CfwModes.EV_UPROLL); delay(450); r.settle("notch $it") }
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            delay(1200); r.settle("the notice")
            val m = r.t.mirror as GlassFirmwareSim
            return Triple(recs.toList(), m.damageContract, m.damageFeatures)
        } finally { r.close() }
    }

    private class Model(contract: Int?, features: Int?) {
        val sim = GlassFirmwareSim()
        val notifies = ArrayList<String>()
        var image: ByteArray? = null
        var now = 0L
        init {
            sim.damageContract = contract; sim.damageFeatures = features
            sim.motionClock = { now }
            sim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { notifies += "$arm " + packet.joinToString("") { "%02x".format(it) } }
                override fun panelChanged(arm: Arm) {}
            })
            sim.imageObserver = { a, img -> if (a == Arm.LEFT) image = img }
        }
        fun write(rec: Rec) { now = rec.now; image = null; sim.write(rec.arm, rec.p, rec.now) }
    }

    private fun engine(m: MotionSim): String = buildString {
        for (o in m.slots) {
            if (o == null) { append("-;"); continue }
            append("${o.slot} ${o.tag} ${o.period} ${o.prog4} ${o.t0} ${o.d} ${o.drawing} ${o.ordinal} ${o.endCrc} ${o.drawn} ${o.folded} ")
            append("${o.local} ${o.owns} ${o.after} ${o.rec} ${o.cost} ${o.last.toList()} ${o.from.toList()} caps ")
            for (c in o.caps) append(c?.let { "${it.w}x${it.h}:" + java.util.zip.CRC32().also { k -> k.update(it.px) }.value } ?: "none").append(',')
            append(';')
        }
        append(" thread ${m.threadExists} seq ${m.sequence} frames ${m.engineFrames} folds ${m.foldsSinceBoot} out ${m.outstanding} ")
        append("commit ${m.lastCommitCrc} sent ${m.sent.size}")
        for ((f, b) in m.sent) append(" $f:" + b.joinToString("") { "%02x".format(it) })
    }

    private fun lens(c: GlassFirmwareSim.LensCtx): String =
        "stale ${c.panelStale} rows ${c.changedLo}..${c.changedHi} seq ${c.presentSeq} presented ${c.presented} path ${c.lastPath} " +
            "direct ${c.directActive} flags ${c.damageFlags} gen ${c.cacheGen} ref ${c.refSeen}/${c.refMode}/${c.refReason}/${c.refSeq} " +
            "seeded ${c.seeded} fids ${c.lastFid}/${c.highFid} cache ${c.textureCache?.size}"

    /** The first difference between the two models, or null. */
    private fun diff(a: Model, b: Model): String? {
        for (arm in Arm.entries) {
            val x = if (arm == Arm.LEFT) a.sim.left else a.sim.right
            val y = if (arm == Arm.LEFT) b.sim.left else b.sim.right
            fun first(p: ByteArray?, q: ByteArray?): Int = if (p == null || q == null) (if (p === q) -1 else 0) else java.util.Arrays.mismatch(p, q)
            for ((name, p, q) in listOf(Triple("shadow", x.shadow, y.shadow), Triple("framebuffer", x.fb, y.fb),
                    Triple("panel", x.panel, y.panel), Triple("texture cache", x.textureCache, y.textureCache))) {
                val i = first(p, q)
                if (i >= 0) return "$arm $name differs at byte $i"
            }
            if (lens(x) != lens(y)) return "$arm bookkeeping: fast ${lens(x)} / plain ${lens(y)}"
            val e = engine(x.motion); val f = engine(y.motion)
            if (e != f) return "$arm engine: fast $e / plain $f"
        }
        if (a.notifies != b.notifies) return "notifies: fast ${a.notifies.size}, plain ${b.notifies.size}, first differing " +
            a.notifies.indices.firstOrNull { it >= b.notifies.size || a.notifies[it] != b.notifies[it] }
        return null
    }

    private fun subs(img: ByteArray): List<ByteArray> {
        if ((img[0].toInt() and 0x7F) != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }

    @Test
    fun theFastLoopsLeaveTheMirrorAsThePlainOnesDoOverASession_ML1(): Unit = runBlocking {
        for (height in listOf(352, 480)) {
            val (recs, contract, features) = record(height)
            val fast = Model(contract, features)
            val plain = Model(contract, features)
            val calls0 = Plain.calls.get()
            val kinds = java.util.TreeMap<String, Int>()
            for ((i, rec) in recs.withIndex()) {
                // what the message does, from the models' state before it: an owner open, a slot drawing
                val ownerOpen = Arm.entries.any { fast.sim.motionForTest(it).anyOwns }
                fast.write(rec)
                PixelLoops.withPlain(Plain) { plain.write(rec) }
                val img = fast.image
                if (img != null) for (s in subs(img)) {
                    val mode = s[0].toInt() and 0x7F
                    val k = if (mode == 26) when (s[1].toInt()) { 0 -> "PLAY slot ${s[2]}"; 2 -> "CLOSE" + (if (ownerOpen) " with an owner open" else ""); 3 -> "COMMIT"; 8 -> "PLAY-AFTER"; else -> "26/${s[1]}" }
                            else if (ownerOpen) "mode $mode with an owner open" else "mode $mode"
                    kinds.merge(k, 1, Int::plus)
                }
                diff(fast, plain)?.let { fail("height $height, packet $i of ${recs.size} (image ${img?.let { s -> subs(s).joinToString(" ") { "${it[0].toInt() and 0x7F}/${it.getOrNull(1)}" } }}): $it") }
            }
            println("ML1 replay at $height: ${recs.size} packets, the messages' kinds $kinds; the plain loops called ${Plain.calls.get() - calls0} times")
            assertTrue(Plain.calls.get() - calls0 > 0, "height $height: the plain model ran the plain loops (${Plain.calls.get() - calls0} calls)")
            // (Main's wrap fires on the glasses since the ring list, DW-0: no wrap PLAY reaches the mirror at stage 5)
            for (k in listOf("PLAY slot 2", "PLAY-AFTER", "COMMIT", "CLOSE with an owner open", "mode 3 with an owner open"))
                assertTrue((kinds[k] ?: 0) > 0, "height $height: the session holds no $k — $kinds")
        }
    }

    // ------------------------------------------------------------------ the loops one by one

    private inline fun <T> both(crossinline call: (PixelLoops) -> T): Pair<Result<T>, Result<T>> =
        runCatching { call(wm.damage.core.sim.FastPixelLoops) } to runCatching { call(Plain) }

    private fun <T> same(what: String, r: Pair<Result<T>, Result<T>>, eq: (T, T) -> Boolean = { a, b -> a == b }) {
        val (f, p) = r
        if (f.isFailure || p.isFailure) {
            assertEquals(p.exceptionOrNull()?.javaClass, f.exceptionOrNull()?.javaClass, "$what: the plain form ${p.exceptionOrNull()} / the fast form ${f.exceptionOrNull()}")
            return
        }
        assertTrue(eq(f.getOrThrow(), p.getOrThrow()), what)
    }

    @Test
    fun eachFastLoopWritesWhatThePlainOneWrites_ML1() {
        val rnd = java.util.Random(0x4D4C31L)
        val stride = (Geometry.PANEL_W + 1) / 2
        val size = stride * Geometry.PANEL_H
        fun buf() = ByteArray(size).also { rnd.nextBytes(it) }
        fun coord(lo: Int, hi: Int) = lo + rnd.nextInt(hi - lo + 1)
        fun rect(): Rect = when (rnd.nextInt(8)) {
            0 -> Rect(coord(-4, 650), coord(-2, 482), coord(-2, 40), coord(-2, 6))            // anywhere, past the edges too
            1 -> Rect(coord(0, 1), coord(0, 2), Geometry.PANEL_W - coord(0, 1), coord(1, 40))  // whole rows, an odd edge
            else -> Rect(coord(0, 600), coord(0, 470), coord(1, 40), coord(1, 10))
        }
        var n = 0
        repeat(3000) { k ->
            val r = rect()
            val src = buf()
            val a0 = buf()
            // copy, fill, grab, tightPack: each on its own copy of the same buffers
            run {
                val x = a0.copyOf(); val y = a0.copyOf()
                val res = runCatching { wm.damage.core.sim.FastPixelLoops.copy(src, x, stride, r) } to runCatching { Plain.copy(src, y, stride, r) }
                same("copy $r", res.first.map { 0 } to res.second.map { 0 })
                assertTrue(x.contentEquals(y), "copy $r (case $k): the buffers differ at ${java.util.Arrays.mismatch(x, y)}")
            }
            run {
                val level = if (rnd.nextInt(10) == 0) coord(16, 300) else coord(0, 15)
                val x = a0.copyOf(); val y = a0.copyOf()
                val res = runCatching { wm.damage.core.sim.FastPixelLoops.fill(x, stride, r, level) } to runCatching { Plain.fill(y, stride, r, level) }
                same("fill $r $level", res.first.map { 0 } to res.second.map { 0 })
                assertTrue(x.contentEquals(y), "fill $r level $level (case $k): the buffers differ at ${java.util.Arrays.mismatch(x, y)}")
            }
            same("grab $r", both { it.grab(src, stride, r) }) { p, q -> p.contentEquals(q) }
            same("tightPack $r", both { it.tightPack(src, stride, r) }) { p, q -> p.contentEquals(q) }
            // unpack: tight rows of a w×h somewhere in the buffer
            run {
                val w = coord(1, 50); val h = coord(1, 8); val off = coord(0, size - ((w + 1) / 2) * h)
                same("unpack $w x $h at $off", both { it.unpack(src, off, w, h) }) { p, q -> p.contentEquals(q) }
            }
            // copyOutside: up to three rects, every fourth case
            if (k % 4 == 0) {
                val rects = List(rnd.nextInt(4)) { if (rnd.nextBoolean()) rect() else Rect(coord(-3, 600), coord(-3, 470), coord(0, 400), coord(0, 300)) }
                val x = a0.copyOf(); val y = a0.copyOf()
                wm.damage.core.sim.FastPixelLoops.copyOutside(src, x, stride, rects)
                Plain.copyOutside(src, y, stride, rects)
                assertTrue(x.contentEquals(y), "copyOutside $rects (case $k): the buffers differ at ${java.util.Arrays.mismatch(x, y)}")
            }
            // blit: a source placed at (x0, y0), clipped as MotionSim.blit clips it
            run {
                val w = coord(1, 60); val h = coord(1, 12)
                val px = ByteArray(w * h) { (if (rnd.nextInt(20) == 0) coord(16, 255) else coord(0, 15)).toByte() }
                val x0 = coord(-8, 640); val y0 = coord(-4, 478)
                val clip = if (rnd.nextInt(6) == 0) Rect(coord(-4, 640), coord(-4, 480), coord(0, 200), coord(0, 40)) else Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)
                val yA = maxOf(clip.y, y0); val yB = minOf(clip.bottom, y0 + h)
                val xA = maxOf(clip.x, x0); val xB = minOf(clip.right, x0 + w)
                val options = when (rnd.nextInt(4)) { 0 -> null; 1 -> 15; else -> coord(0, 63) }
                val x = a0.copyOf(); val y = a0.copyOf()
                val res = runCatching { wm.damage.core.sim.FastPixelLoops.blit(x, stride, px, w, x0, y0, xA, xB, yA, yB, options) } to
                    runCatching { Plain.blit(y, stride, px, w, x0, y0, xA, xB, yA, yB, options) }
                same("blit ${w}x$h at ($x0,$y0) clip $clip options $options", res.first.map { 0 } to res.second.map { 0 })
                assertTrue(x.contentEquals(y), "blit ${w}x$h at ($x0,$y0) clip $clip options $options (case $k): the buffers differ at ${java.util.Arrays.mismatch(x, y)}")
            }
            // levels
            run {
                val px = ByteArray(coord(1, 300)); val from = coord(0, px.size - 1); val count = coord(0, px.size - from)
                val x = px.copyOf(); val y = px.copyOf(); val v = coord(0, 15).toByte()
                wm.damage.core.sim.FastPixelLoops.levels(x, from, count, v); Plain.levels(y, from, count, v)
                assertTrue(x.contentEquals(y), "levels $from+$count (case $k)")
            }
            // an RLE run inside a box of a frame (mode 3's shape) or the whole frame (mode 6's)
            run {
                val whole = rnd.nextInt(5) == 0
                val rowBytes = if (whole) stride else coord(1, 40)
                val rows = if (whole) Geometry.PANEL_H else coord(1, 30)
                val off = if (whole) 0 else coord(0, 470 - rows) * stride + coord(0, stride - rowBytes)
                val rowNibbles = rowBytes.toLong() * 2
                val last = rowNibbles * rows
                val pos = (rnd.nextLong() and Long.MAX_VALUE) % (last + 3)
                val v = coord(0, 15); val len = coord(1, if (rnd.nextBoolean()) 9 else 700)
                val x = a0.copyOf(); val y = a0.copyOf()
                val res = runCatching { wm.damage.core.sim.FastPixelLoops.rleRun.write(x, off, stride, rowNibbles, last, pos, v, len) } to
                    runCatching { Plain.rleRun.write(y, off, stride, rowNibbles, last, pos, v, len) }
                same("rle run of $len from $pos in $rowBytes x $rows", res)
                assertTrue(x.contentEquals(y), "rle run of $len from $pos in $rowBytes x $rows at $off (case $k): the buffers differ at ${java.util.Arrays.mismatch(x, y)}")
            }
            n++
        }
        assertEquals(3000, n)
    }
}
