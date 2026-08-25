package wm.damage.core

import kotlin.test.Test
import wm.damage.core.transport.Arm
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.Emit
import wm.damage.core.transport.FlushRequest
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.SettingsMsg

/**
 * The whole pipeline, byte-exact: compose -> pack -> rle -> deflate -> mode
 * payloads -> ImgRawMsg fragments -> AA packets -> sim reassembly -> firmware
 * dispatch -> per-lens shadow. What the lens holds must equal what we composed.
 */
class SimRoundTripTest {
    private class Harness {
        val events = ArrayList<Pair<String, String>>()
        val notifies = ArrayList<ByteArray>()
        val sim = GlassFirmwareSim()
        var msgId = 0
        var seq = 0
        var session = 1

        init {
            sim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) { events.add(kind to detail) }
                override fun notify(arm: Arm, packet: ByteArray) { notifies.add(packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** The connect prelude, then the carrier CREATE — the order every
         *  working implementation uses (LaunchMsg). */
        fun create() {
            prelude()
            createWithoutPrelude()
        }

        fun prelude() {
            for (p in AaFrame.frame(++seq and 0xFF, LaunchMsg.SID, LaunchMsg.FLAG_REQUEST,
                    LaunchMsg.prelude(++msgId))) {
                sim.write(Arm.RIGHT, p, 0)
            }
        }

        fun createWithoutPrelude() {
            write(EvenHubMsg.carrierCreate(++msgId))
        }

        fun write(payload: ByteArray, arm: Arm = Arm.RIGHT) {
            for (p in AaFrame.frame(++seq and 0xFF, EvenHubMsg.SID, EvenHubMsg.FLAG_REQUEST, payload)) {
                sim.write(arm, p, 0)
            }
        }

        fun sendImage(image: ByteArray) {
            val s = ++session
            val chunks = image.toList().chunked(Geometry.MAX_IMAGE_FRAGMENT)
            for ((i, c) in chunks.withIndex()) {
                write(EvenHubMsg.imageFragment(++msgId % 250, s, image.size, i, c.toByteArray()),
                    Arm.LEFT)
            }
        }

        fun lease(now: Long) {
            for (arm in Arm.entries) {
                for (p in AaFrame.frame(++seq and 0xFF, SettingsMsg.SID, SettingsMsg.FLAG_REQUEST,
                        SettingsMsg.fbAcquire(1))) {
                    sim.write(arm, p, now)
                }
            }
        }
    }

    private fun frame(fill: Int = 0): Gray8 {
        val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        g.clear(fill)
        return g
    }

    private fun keyframeOp(g: Gray8) = DisplayOp.Keyframe(
        Zl.encodeCfw(Pack.rect(g, Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))),
    )

    @Test
    fun keyframeAndDeltaLandExactly() {
        val h = Harness()
        h.create()
        h.sendImage(byteArrayOf(6, 0, 0))   // warmup — silently dropped by design

        val composed = frame()
        composed.fillRect(100, 100, 200, 50, 8 * 17)
        val fids = FidAllocator()
        val tracker = FidTracker()

        val kf = Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3)
        h.sendImage(kf.image)
        assertContentEquals(
            Pack.rect(composed, Rect(0, 0, 640, 480)),
            h.sim.left.shadow,
            "keyframe should seed the left shadow exactly",
        )

        // a delta: change a box, emit only that box
        composed.fillRect(104, 120, 64, 10, 12 * 17)
        val box = Rect(104, 120, 64, 10)
        val delta = Emit.encode(
            FlushRequest(listOf(DisplayOp.Delta(box, Zl.encodeCfw(Pack.rect(composed, box)))), 2L),
            fids, tracker, 3,
        )
        h.sendImage(delta.image)
        assertContentEquals(Pack.rect(composed, Rect(0, 0, 640, 480)), h.sim.left.shadow)
        assertContentEquals(Pack.rect(composed, Rect(0, 0, 640, 480)), h.sim.right.shadow)
        assertEquals(listOf(1), delta.fids)
        assertFalse(h.sim.flags(Arm.LEFT).values.any { it }, "no sticky flags")
    }

    @Test
    fun createWithoutThePreludeNeverActivatesThePage() {
        val h = Harness()
        h.createWithoutPrelude()
        h.lease(0)
        h.sendImage(byteArrayOf(6, 0, 0))              // would be the warmup
        val composed = frame(); composed.fillRect(0, 0, 640, 480, 9 * 17)
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L),
            FidAllocator(), FidTracker(), 3).image)
        assertFalse(h.sim.layoutCreated, "no prelude: the page stays inactive")
        assertTrue(h.sim.left.panel.all { it.toInt() == 0 }, "no prelude: nothing paints")
        assertTrue(h.events.any { it.first == "launch" }, "the model reports the missing prelude")
    }

    @Test
    fun warmupIsDroppedAndSecondFramePaints() {
        val h = Harness()
        h.create()
        val composed = frame(3 * 17)
        val fids = FidAllocator(); val tracker = FidTracker()
        val kf = Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3)
        h.sendImage(kf.image)     // this is the sacrificial one
        assertTrue(h.events.any { it.first == "warmup" })
        assertFalse(h.sim.left.seeded)
        h.sendImage(kf.image)     // frame 2 = first real one... but fid discipline:
        assertTrue(h.sim.left.seeded)
    }

    @Test
    fun stereoDeltaDivergesPerLens() {
        val h = Harness()
        h.create()
        h.sendImage(byteArrayOf(6, 0, 0))
        val composed = frame()
        val fids = FidAllocator(); val tracker = FidTracker()
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3).image)

        val box = Rect(300, 200, 40, 20)
        val payloadSrc = Gray8(40, 20).also { it.clear(15 * 17) }
        val payload = Zl.encodeCfw(Pack.rect(payloadSrc, Rect(0, 0, 40, 20)))
        val d = 8
        h.sendImage(
            Emit.encode(
                FlushRequest(listOf(DisplayOp.Delta(box, payload, disparity = d)), 2L),
                fids, tracker, 3,
            ).image,
        )
        // far plane: left lens draws at x-d, right lens at x+d (overview.md §7 / DESIGN §3)
        fun litAt(shadow: ByteArray, x: Int, y: Int): Boolean {
            val b = shadow[y * 320 + (x shr 1)].toInt() and 0xFF
            return (if (x % 2 == 0) b shr 4 else b and 0xF) == 15
        }
        assertTrue(litAt(h.sim.left.shadow, 300 - d, 200))
        assertFalse(litAt(h.sim.left.shadow, 300 + d + 39, 200))
        assertTrue(litAt(h.sim.right.shadow, 300 + d, 200))
        assertFalse(litAt(h.sim.right.shadow, 300 - d, 200))
    }

    @Test
    fun scrollBatchShiftsAndFills() {
        val h = Harness()
        h.create()
        h.sendImage(byteArrayOf(6, 0, 0))
        val composed = frame()
        // rows of distinct levels so a shift is verifiable
        for (y in 0 until 480) composed.fillRect(0, y, 640, 1, (y % 16) * 17)
        val fids = FidAllocator(); val tracker = FidTracker()
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3).image)

        // scroll the 100..300 band up by 32: copy (100+32..300) -> (100..268), fill the exposed strip
        val newStrip = Gray8(640, 32).also { it.clear(9 * 17) }
        val batch = Emit.encode(
            FlushRequest(
                listOf(
                    DisplayOp.Copy(Rect(0, 132, 640, 168), Rect(0, 100, 640, 168)),
                    DisplayOp.Delta(Rect(0, 268, 640, 32), Zl.encodeCfw(Pack.rect(newStrip, Rect(0, 0, 640, 32)))),
                ),
                3L,
            ),
            fids, tracker, 3,
        )
        h.sendImage(batch.image)

        // model the same ops on the composed frame
        val expect = composed.copy()
        for (y in 100 until 268) for (x in 0 until 640) expect[x, y] = composed[x, y + 32]
        expect.fillRect(0, 268, 640, 32, 9 * 17)
        assertContentEquals(Pack.rect(expect, Rect(0, 0, 640, 480)), h.sim.left.shadow)
    }

    @Test
    fun duplicateFidIsSkippedByFirmware() {
        val h = Harness()
        h.create()
        h.sendImage(byteArrayOf(6, 0, 0))
        val composed = frame()
        val fids = FidAllocator(); val tracker = FidTracker()
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3).image)

        val box = Rect(0, 0, 8, 2)
        val p = Zl.encodeCfw(Pack.rect(Gray8(8, 2).also { it.clear(255) }, Rect(0, 0, 8, 2)))
        // handcraft two deltas with the SAME fid — the tracker would forbid it,
        // so build the second directly
        h.sendImage(wm.damage.core.wire.CfwModes.delta(box, p, 7))
        h.sendImage(wm.damage.core.wire.CfwModes.delta(box, wm.damage.core.gfx.Zl.encodeCfw(
            Pack.rect(Gray8(8, 2).also { it.clear(0) }, Rect(0, 0, 8, 2))), 7))
        assertTrue(h.sim.flags(Arm.LEFT).getValue("f_dup"))
        // the second delta was SKIPPED: pixels still at 15
        assertEquals(0xFF, h.sim.left.shadow[0].toInt() and 0xFF)
        assertTrue(h.events.any { it.first == "fid" && "SKIPPED" in it.second })
    }

    @Test
    fun msgIdOver255IsSilentlyDropped() {
        val h = Harness()
        val before = h.notifies.size
        // handcraft a Cmd-12 keepalive with msgId 300 (2-byte varint)
        val bad = wm.damage.core.wire.Pb.cat(
            wm.damage.core.wire.Pb.v(1, 12),
            wm.damage.core.wire.Pb.v(2, 300),
            wm.damage.core.wire.Pb.l(14, ByteArray(0)),
        )
        h.write(bad)
        assertEquals(before, h.notifies.size, "no ack — the frame vanishes in silence")
        assertTrue(h.events.any { it.first == "msgid" })
    }

    @Test
    fun leaseExpiryFailsOpen() {
        val h = Harness()
        h.create()
        h.lease(now = 0)
        h.sendImage(byteArrayOf(6, 0, 0))
        val composed = frame(5 * 17)
        val fids = FidAllocator(); val tracker = FidTracker()
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3).image)
        assertTrue(h.sim.leaseHeld(Arm.LEFT, 1000))
        h.sim.tick(91_000)
        assertFalse(h.sim.leaseHeld(Arm.LEFT, 91_000))
        assertTrue(h.events.any { it.first == "lease" && "EXPIRED" in it.second })
    }

    @Test
    fun imageWithoutLayoutNeverPaints() {
        val h = Harness()
        // no CREATE at all
        val composed = frame(4 * 17)
        val fids = FidAllocator(); val tracker = FidTracker()
        h.sendImage(Emit.encode(FlushRequest(listOf(keyframeOp(composed)), 1L), fids, tracker, 3).image)
        assertFalse(h.sim.left.seeded)
        assertTrue(h.events.any { "NO layout" in it.second })
    }

    @Test
    fun outOfOrderFragmentAborts() {
        val h = Harness()
        h.create()
        h.sendImage(byteArrayOf(6, 0, 0))
        // fragment 1 without fragment 0
        h.write(EvenHubMsg.imageFragment(40, 99, 8000, 1, ByteArray(100)), Arm.LEFT)
        assertTrue(h.events.any { it.first == "image" && "abort" in it.second })
        // and the session (and its neighbours) are now stuck — the g2-kit trap
        h.write(EvenHubMsg.imageFragment(41, 100, 100, 0, ByteArray(100)), Arm.LEFT)
        assertTrue(h.events.any { it.first == "session" })
    }
}
