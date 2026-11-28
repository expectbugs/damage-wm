package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.gfx.Zl
import wm.damage.core.local.Taps
import wm.damage.core.motion.PopupPrograms
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.Transitions
import wm.damage.core.shell.ListStaging
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb

/**
 * Build C3, stage X3D (`C3-decisions.md` AP (1)): the programs the phone ships, as conformance-vector INPUTS
 * (`FIRMWARE.md` §9, §7.13) — `firmware/vectors/v5-ship-*.json`, expectations empty until the fork's host runner fills
 * them. Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work; nothing here
 * contacts a device.
 *
 * Each vector is ONE act played by the shell itself — [TransitionShellTest.Rig]: the Shell over SimTransport on the
 * instant link, a bit-11 build (DamageCaps 0xdff) at ladder stage 5 — and holds every image the lenses were handed,
 * in order, so the programs are `Transitions.build`'s and the messages are the shell's, not a second builder's:
 *  - step 0: the session from its start — the lease, the cache size and the flags the transport put in force, the
 *    page, then every image and input before the act — then a wake at every due tick until the engine is idle;
 *  - the act: every image and input from the moment it is made — a Select's tap through the input hook (a double tap
 *    reaches only the phone), the records' write, the out PLAY's image, the arriving frame's images (the last carrying
 *    the PLAY-AFTER), whatever else the shell sent under the run (a rules' answer, an atlas patch, his scroll's frame),
 *    and the CLOSE after the in half's body — each its own step, placed at the engine progress (the act's steps drawn,
 *    as RIGHT's model held them) at which it reached RIGHT in the rig; between them a wake at every due tick of the
 *    model's plan, so every step's present of both halves is a step of the vector (none folded: checked), and the
 *    CLOSE's present is the one after LAND.
 * Each lens is handed the same images in the same order (checked); the act starts at tick 2000. Where an image lands
 * between two steps follows the rig's run, so two dumps can place one a step apart; the programs are the same.
 *
 * [ConformanceVectorTest.theShipVectorsHoldTheProgramsTheShellSends_X3D] re-runs every act and compares the program
 * bytes — the records' write and every PLAY and PLAY-AFTER of the act — with the committed file's: a changed effect
 * cannot keep a stale vector. The rest of a file (the session's atlas chunks, a chrome tick's throughput text) follows
 * the process's history and is not compared. `DAMAGE_SHIP_DUMP=DIR` writes the regenerated files into DIR, keeping a
 * file's expectations for the steps whose inputs did not change (`make_vectors.py`'s rule). Since X3D's comparison the
 * committed files hold the firmware's answers to one run's placement: regenerate only a file whose program changed, and
 * have the fork's runner fill its new inputs (a regenerated step after a moved image keeps no expectation).
 *
 * APK 0.83, stage SV: the same for the programs 0.83 ships beside the transitions ([Act]) — Main's scroll effects
 * ([scrollSpecs]: a notch each way, the wrap's record, a burst, every length and frame count that changes a program's
 * shape) and the notice box's programs ([noticeSpecs]: the timer, its two arrivals, a notch under the box, a withdrawal),
 * played on his glasses' session (stage 4, 256 KiB with BIND) with Main's eight rows; the re-play row compares their
 * programs through [namedPrograms] (version-1 records, named from the session's table too).
 *
 * C3 stage SVR: Main's list as a ring list ([ringSpecs]: LIST-RING where Main's paint wraps — his eight rows at 288 and
 * 352 — at ladder stage 5 with EFFECTS in force), played on the same session at stage 5; the re-play row compares their
 * programs and their ring form through [ringForms].
 */
internal object ShipVectors {
    const val PREFIX = "v5-ship-"
    private const val FEATURES = 0xDFF
    private const val ACT_TICK = 2000L

    /** C3 SV2: LATE — the window's frame takes several flushes and the link has no room from its first one until the out
     *  half has drawn its last step on both lenses, so the rest of the frame and the PLAY-AFTER riding its last flush land
     *  after the out half's end (the in half then starts from the out half's last pose). */
    enum class Form { WHOLE, CUT, WRITE, LATE }

    /** C3 X3T, X4T: the window's labelled rows for a glyph effect ([labelsOf]) — a typical window, a page dense enough for
     *  two cell lists, or (APART) one too dense for the records and both halves' lists in one compressed message, so the
     *  in lists go in a second write under the out half (the shell's not-compressed order). */
    enum class Page { TYPICAL, DENSE, APART }

    /** One act: [tier]'s [effect] at layout height [height], Global Depth [depth]; [form] WHOLE (the act alone), CUT (a
     *  second Select inside the in half) or WRITE (his scroll's frame written under the in half). C3 X3T: Encode and
     *  Matrix ([Transitions.Effect.glyphs]) play on the rig's 256 KiB session with BIND ([kib256]: the session stage 5
     *  has on his glasses, the only one Encode plays on), over the window's labelled rows ([labelsOf], [page]), every
     *  screen's text packed into the atlas before the act. C3 SV2: [length] and [frames] the tier's `Length` and `Frames`
     *  when not the default (450 ms, 10 frames: 5 steps a half). */
    data class Spec(override val name: String, val tier: Transitions.Tier, val effect: Transitions.Effect, val reversed: Boolean,
                    val height: Int, val depth: Int = 8, val form: Form = Form.WHOLE, val page: Page = Page.TYPICAL,
                    val length: Int? = null, val frames: Int? = null) : Act {
        val kib256: Boolean get() = effect.glyphs
    }

    /** C3 X3T: the glyph effects whose programs differ between Global Depth 8 and 12 (Encode: each cell track takes its
     *  plane's disparity on each lens; Matrix's rain is one rect on both lenses, its programs the same at both — measured
     *  on the first dump, `C3-X3T-built.md`). C3 SV2: since X1MX2 Matrix's grid sits at the act's plane (the nearer of the
     *  two screens' content planes), so its Select and Back differ at 12 — their acts are [sv2Acts]'s; the Lock Screen's
     *  plane is 0 at every Global Depth, so its acts do not differ and none is added. */
    private val GLYPHS_OFF4 = setOf(Transitions.Effect.ENCODE)

    /** Every effect of every tier (Back reversed; the Lock Screen entered forward and left reversed) at 352 and 480;
     *  Depth's per-lens offset at both values it takes (8 px at Global Depth 0..8, 4 px at 12 and 16); one act cut by a
     *  second Select inside its in half; one with a write under its in half. X3D's 46 first, in X3D's order; then C3 X3T's
     *  Encode and Matrix — every tier that offers them at 352 and 480, at Global Depth 12 too where the program differs
     *  ([GLYPHS_OFF4]), Encode at 480 into and out of a page dense enough for two cell lists, and one Encode act with a
     *  write under its in half; then C3 X4T's: one Encode Select into a page too dense for one compressed message (the in
     *  lists apart, under the out half), and one Encode and one Matrix Select cut by a second Select inside their in half
     *  (as `cut-select-warp-352`). */
    fun specs(): List<Spec> {
        val out = ArrayList<Spec>()
        fun n(tier: String, e: Transitions.Effect, rev: Boolean, h: Int, extra: String = "") =
            "$PREFIX$tier-${e.id}${if (rev) "-rev" else ""}$extra-$h"
        fun of(tier: Transitions.Tier, glyphs: Boolean) = Transitions.effectsOf(tier).filter { it.glyphs == glyphs }
        for (h in listOf(352, 480)) {
            for (e in of(Transitions.Tier.SELECT, false)) out += Spec(n("select", e, false, h), Transitions.Tier.SELECT, e, false, h)
            for (e in of(Transitions.Tier.BACK, false)) out += Spec(n("back", e, true, h), Transitions.Tier.BACK, e, true, h)
            for (e in of(Transitions.Tier.LOCK, false)) for (rev in listOf(false, true)) out += Spec(n("lock", e, rev, h), Transitions.Tier.LOCK, e, rev, h)
            out += Spec(n("select", Transitions.Effect.DEPTH, false, h, "-off4"), Transitions.Tier.SELECT, Transitions.Effect.DEPTH, false, h, depth = 12)
            out += Spec(n("back", Transitions.Effect.DEPTH, true, h, "-off4"), Transitions.Tier.BACK, Transitions.Effect.DEPTH, true, h, depth = 12)
        }
        out += Spec("${PREFIX}cut-select-warp-352", Transitions.Tier.SELECT, Transitions.Effect.WARP, false, 352, form = Form.CUT)
        out += Spec("${PREFIX}write-select-depth-352", Transitions.Tier.SELECT, Transitions.Effect.DEPTH, false, 352, form = Form.WRITE)
        // C3 X3T: Encode and Matrix
        for (h in listOf(352, 480)) {
            for (depth in listOf(8, 12)) {
                val extra = if (depth == 8) "" else "-off4"
                fun want(e: Transitions.Effect) = depth == 8 || e in GLYPHS_OFF4
                for (e in of(Transitions.Tier.SELECT, true).filter { want(it) }) out += Spec(n("select", e, false, h, extra), Transitions.Tier.SELECT, e, false, h, depth)
                for (e in of(Transitions.Tier.BACK, true).filter { want(it) }) out += Spec(n("back", e, true, h, extra), Transitions.Tier.BACK, e, true, h, depth)
                for (e in of(Transitions.Tier.LOCK, true).filter { want(it) }) for (rev in listOf(false, true)) out += Spec(n("lock", e, rev, h, extra), Transitions.Tier.LOCK, e, rev, h, depth)
            }
            if (h == 480) {
                out += Spec(n("select", Transitions.Effect.ENCODE, false, h, "-dense"), Transitions.Tier.SELECT, Transitions.Effect.ENCODE, false, h, page = Page.DENSE)
                out += Spec(n("back", Transitions.Effect.ENCODE, true, h, "-dense"), Transitions.Tier.BACK, Transitions.Effect.ENCODE, true, h, page = Page.DENSE)
            }
        }
        out += Spec("${PREFIX}write-select-encode-352", Transitions.Tier.SELECT, Transitions.Effect.ENCODE, false, 352, form = Form.WRITE)
        // C3 X4T (the coordinator's additions to item 6)
        out += Spec(n("select", Transitions.Effect.ENCODE, false, 480, "-apart"), Transitions.Tier.SELECT, Transitions.Effect.ENCODE, false, 480, page = Page.APART)
        for (e in listOf(Transitions.Effect.ENCODE, Transitions.Effect.MATRIX))
            out += Spec("${PREFIX}cut-select-${e.id}-352", Transitions.Tier.SELECT, e, false, 352, form = Form.CUT)
        return out
    }

    /** C3 X3T: the window's labelled rows (its text through the shell's cached text: Encode's cells) — a typical window
     *  (X2T's "typical at 352": one line), or a page dense enough for two cell lists at 480 (X2T's "dense at 480": 14
     *  lines of 60 letters at the rows' 26-px pitch); C3 X4T: a page too dense for one compressed message (X2T's "too dense
     *  at 480": 23 lines of 70 letters at a 16-px pitch, [gapOf]). */
    private fun labelsOf(spec: Spec): List<Pair<String, wm.damage.core.text.FontSpec>> {
        val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        val prose = "the quick brown fox jumps over the lazy dog while five wizards box and jump quickly at dawn "
        return when (spec.page) {
            Page.TYPICAL -> listOf("Window zero" to f)
            Page.DENSE -> (0 until 14).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 60) to f }
            Page.APART -> (0 until 23).map { i -> (prose + prose).substring(i * 3 % 40, i * 3 % 40 + 70) to f }
        }
    }

    /** The labelled rows' line pitch for [spec]'s page. */
    private fun gapOf(spec: Spec): Int = if (spec.page == Page.APART) 16 else 26

    // ------------------------------------------------------------------------------------------------- capture
    /** C3 B2X: [at] RIGHT's counted slots as the event was recorded ([slotWords]) — the evidence a failed placement
     *  reports. An input's [progress] is read in the same hold of the model's lock as its inputs ([TransitionShellTest.Rig.inputs]). */
    private class Event(val arm: Arm?, val msg: ByteArray?, val input: LongArray?, val progress: Int, val act: Boolean, val at: String = "")

    /** C3 B2X: [arm]'s instances in [slots] as `slot:tag d/T drawing|done`, for a failure's evidence. */
    private fun slotWords(sim: GlassFirmwareSim, arm: Arm, slots: List<Int>): String = slots.joinToString(" ") { s ->
        sim.motionForTest(arm).slots[s]?.let { i -> "$s:%04x %d/%d %s".format(i.tag, i.d, i.T, if (i.drawing) "drawing" else "done") } ?: "$s:-" }

    /** C3 LO-1X: [m]'s slot [s] as `tag t d/T drawing|done [local]`, or `empty` — called inside the model's lock. */
    private fun slotState(m: MotionSim, s: Int): String = m.slots[s]?.let { i ->
        "tag %04x %d/%d %s%s".format(i.tag, i.d, i.T, if (i.drawing) "drawing" else "done", if (i.local) " local" else "") } ?: "empty"

    /** C3 LO-1X: [b]'s list [l] and the entries naming it, as the model holds them — called inside the model's lock. */
    private fun bindState(b: wm.damage.core.sim.BindSim, l: Int): String {
        val x = b.lists[l]
        val entries = b.entries.filterNotNull().filter { it.list == l }.joinToString(" ") { "id ${it.eventId} slot ${it.slot} prog %04x".format(it.prog) }
        return "bind live ${b.live} seq ${b.seq}; list $l items %04x count %d cursor %d pitch %d mark %d ring %s newest %s; entries [%s]"
            .format(x.items, x.count, x.cursor, x.pitch, x.mark, x.ring, x.newest, entries)
    }

    /** C3 B2X: an event in a few words — an input's event id, an image's sub-messages as mode/sub. */
    private fun eventWords(e: Event): String = if (e.msg == null) "input ${e.input!!.joinToString(",")}" else
        "image " + subs(e.msg).joinToString(",") { if (it.size >= 2) "${it[0].toInt() and 0x7F}/${it[1].toInt() and 0xFF}" else "${it[0].toInt() and 0x7F}" }

    /** One act as the rig played it, and the vector made of it; [halves] (C3 X3T): the model's own §7.12 C of each
     *  half it opened in slots 2 and 3 on RIGHT, as the vector is laid out (for the report). */
    class Captured(val act: Act, val vector: JsonObject, val owed: List<String>, val halves: List<String> = emptyList())

    /** The transitions' instances: slots 2 and 3, the tags 0xC000.. and 0xD000.. (a notice's 0xE000.. stays out). */
    private val TRANSITION_SLOTS = listOf(2, 3)
    private val TRANSITION_TAG: (Int) -> Boolean = { it and 0xE000 == 0xC000 }

    /** The act's engine progress on [arm]: the steps drawn by the transitions' instances (slots 2 and 3, the tags
     *  0xC000.. and 0xD000..), each instance counted at the largest d [seen] for it — a closed or emptied one keeps its
     *  count. Monotone; the rig's and the vector's model are read the same way. SV-083: another act's instances
     *  ([slots], the tags [accept] takes). */
    private fun progress(sim: GlassFirmwareSim, arm: Arm, seen: MutableMap<Int, Int>, slots: List<Int> = TRANSITION_SLOTS,
                         accept: (Int) -> Boolean = TRANSITION_TAG): Int {
        for (s in slots) {
            val i = sim.motionForTest(arm).slots[s] ?: continue
            if (!accept(i.tag)) continue
            val key = (s shl 16) or i.tag
            seen[key] = maxOf(seen[key] ?: 0, i.d)
        }
        return seen.values.sum()
    }

    suspend fun capture(spec: Spec): Captured {
        val r = TransitionShellTest.Rig(stage = 5, kib256 = spec.kib256)
        val glyphs = spec.effect.glyphs
        if (glyphs) { r.rows[0].labels = labelsOf(spec); r.rows[0].gap = gapOf(spec) }
        try {
            val events = java.util.Collections.synchronizedList(ArrayList<Event>())
            val act = java.util.concurrent.atomic.AtomicBoolean(false)
            val seenL = HashMap<Int, Int>(); val seenR = HashMap<Int, Int>()
            // C3 SV2, LATE: the link is held from the out PLAY — set as the first lens is handed it, before its ack can let the
            // next pump go (the pump that sent the out PLAY sent the frame's first flush beside it) — and RIGHT's out half as
            // the PLAY-AFTER reaches it (read inside the model's dispatch)
            val heldAt = java.util.concurrent.atomic.AtomicBoolean(false)
            val afterOnRight = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val afterSeen = java.util.concurrent.atomic.AtomicBoolean(false)
            /** RIGHT's frame flushes between the out PLAY and the PLAY-AFTER (under the drawing out half). */
            val framesEarly = java.util.concurrent.atomic.AtomicInteger(0)
            fun drawsPixels(ss: List<ByteArray>) = ss.any { (it[0].toInt() and 0x7F) !in setOf(19, 25, 26, 27) }
            fun lateSpy(a: Arm, img: ByteArray) {
                val ss = subs(img)
                if (ss.any { isSub(it, 26, CfwModes.MOTION_PLAY_AFTER) }) afterSeen.set(true)
                if (ss.any { playIn(it, 2) } && heldAt.compareAndSet(false, true)) r.shell.pumpHeldForTest = true
                else if (a == Arm.RIGHT && heldAt.get() && !afterSeen.get() && drawsPixels(ss)) framesEarly.incrementAndGet()
                if (a == Arm.RIGHT && ss.any { isSub(it, 26, CfwModes.MOTION_PLAY_AFTER) })
                    afterOnRight.set(r.sim.motionForTest(Arm.RIGHT).slots[2]?.let { "${if (it.drawing) "drawing" else "done"} d ${it.d} of T ${it.T}; " +
                        "the PLAY-AFTER's image ${if (ss.any { s -> (s[0].toInt() and 0x7F) !in setOf(19, 25, 26, 27) }) "carries" else "carries no"} drawing sub" } ?: "slot 2 empty")
            }
            // inside the model's own lock (the observer runs in its dispatch): the progress as the message arrives
            r.onImage = { a, img -> events += Event(a, img.copyOf(), null, progress(r.sim, a, if (a == Arm.LEFT) seenL else seenR), act.get(),
                    at = if (a == Arm.RIGHT && act.get()) slotWords(r.sim, a, TRANSITION_SLOTS) else "")
                if (spec.form == Form.LATE && act.get()) lateSpy(a, img) }
            // and every millisecond between, so a step drawn with no message after it (the out half's last, before the
            // PLAY-AFTER's deferred empty takes the slot) is counted: each instance holds its d for a period (~45 ms)
            r.scope.launch { while (true) { synchronized(r.sim) { progress(r.sim, Arm.LEFT, seenL); progress(r.sim, Arm.RIGHT, seenR) }; delay(1) } }
            fun fire(eventId: Int) = r.fire(eventId) {
                events += Event(null, null, longArrayOf(CfwModes.DEV_RING.toLong(), eventId.toLong(), 0), progress(r.sim, Arm.RIGHT, seenR), act.get(),
                    at = slotWords(r.sim, Arm.RIGHT, TRANSITION_SLOTS))
            }
            suspend fun select(what: String, window: String?) {
                fire(CfwModes.EV_TAP)
                if (window != null) r.until("$what: $window opens") { r.onLoop { r.shell.currentWindowId() } == window }
            }
            /** A double tap: a Back in a window (back on Main), the silent clock entered at Main and left there. */
            suspend fun doubleTap(what: String, toMain: Boolean) {
                r.doubleTap()
                if (toMain) r.until("$what: back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            }
            fun effects(tier: Transitions.Tier, on: Boolean) =
                r.shell.updateSettings { it.withTier(tier, effects = if (on) listOf(spec.effect.id) else emptyList()) }
            /** C3 X3T: a double tap onto the silent clock ([on]) or off it, seen in the shell's power note. */
            suspend fun clock(on: Boolean, what: String) {
                r.doubleTap()
                r.until(what) { r.notes("power").lastOrNull()?.startsWith("shell-silent") == on }
            }
            // the tiers of the acts before the one under test play no transition (None): they are part of the session
            // (X3T: a glyph effect's tier is checked only just before the act, below)
            r.up { s -> s.copy(heightMode = spec.height, depth = spec.depth)
                .withTier(Transitions.Tier.SELECT, effects = if (!glyphs && spec.tier == Transitions.Tier.SELECT) listOf(spec.effect.id) else emptyList())
                .withTier(Transitions.Tier.BACK, effects = if (!glyphs && spec.tier == Transitions.Tier.BACK) listOf(spec.effect.id) else emptyList())
                .withTier(Transitions.Tier.LOCK, effects = if (!glyphs && spec.tier == Transitions.Tier.LOCK && !spec.reversed) listOf(spec.effect.id) else emptyList())
                // C3 SV2: the tier's own length and frames when the act names them
                .let { if (spec.length == null) it else it.withTier(spec.tier, length = spec.length, frames = spec.frames ?: it.framesOf(spec.tier)) } }
            if (spec.form == Form.CUT) r.rows[0].levels = true
            if (glyphs) {
                // C3 X3T: both screens' text packed before the act, with no transition on the way — an Encode or Matrix that
                // meets an atlas chunk queued or in flight is left out of the act's pick, and Encode's cells come only from
                // faces the glasses hold — then the act's effect checked
                when {
                    spec.tier == Transitions.Tier.SELECT -> {
                        select("into w0 (its text packed)", "w0"); r.settle("in w0")
                        doubleTap("out of w0", toMain = true); r.settle("on Main")
                    }
                    spec.tier == Transitions.Tier.BACK -> { select("into w0", "w0"); r.settle("in w0") }
                    !spec.reversed -> {
                        clock(true, "onto the silent clock (its text packed)"); r.settle("on the silent clock")
                        clock(false, "back to Main"); r.settle("on Main")
                    }
                    else -> { clock(true, "onto the silent clock"); r.settle("on the silent clock") }
                }
                effects(spec.tier, true); r.settle("${spec.effect.label} checked in the ${spec.tier.title} tier")
            } else when {
                spec.tier == Transitions.Tier.BACK -> { select("into w0", "w0"); r.settle("in w0") }
                spec.tier == Transitions.Tier.LOCK && spec.reversed -> {
                    doubleTap("onto the silent clock", toMain = false); r.settle("on the silent clock")
                    effects(Transitions.Tier.LOCK, true); r.settle("the Lock tier's effect checked")
                }
            }
            val transitionsBefore = r.notes("transition").count { it.startsWith("owed: ") }
            // C3 SV2, LATE: the window's frame in several flushes — the batch cap lowered for the act (as X2M0's Back row lowers
            // it for Main's frame; every op of this window's frame is smaller), put back once the frame is in
            val batchMax = r.onLoop { r.shell.comp.batchMax }
            if (spec.form == Form.LATE) r.onLoop { r.shell.comp.batchMax = 400 }
            act.set(true)
            when (spec.tier) {
                Transitions.Tier.SELECT -> select("the act", "w0")
                Transitions.Tier.BACK -> doubleTap("the act", toMain = true)
                Transitions.Tier.LOCK -> doubleTap("the act", toMain = false)
            }
            when (spec.form) {
                Form.WHOLE -> {}
                Form.CUT, Form.WRITE -> {
                    // he acts as the window first shows: RIGHT's in half has drawn a step and is still drawing
                    r.until("RIGHT's in half drawing") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" &&
                        synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 && it.d < it.T } } == true }
                    if (spec.form == Form.CUT) fire(CfwModes.EV_TAP) else r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                }
                Form.LATE -> {
                    r.until("the out PLAY in, the link held") { heldAt.get() }
                    r.until("the out half's last step drawn on both lenses, the rest of the frame still held") {
                        synchronized(r.sim) { Arm.entries.all { a -> r.sim.motionForTest(a).slots[2]?.let { !it.drawing && it.d == it.T } == true } } &&
                            r.onLoop { r.shell.comp.hasPending && r.shell.transitionPhaseForTest() == "FRAME" } }
                    r.shell.pumpHeldForTest = false; r.shell.postIdleTick()
                    r.until("the rest of the frame and the PLAY-AFTER sent") { r.onLoop { r.shell.transitionPhaseForTest() != "FRAME" } }
                    r.onLoop { r.shell.comp.batchMax = batchMax }
                }
            }
            val acts = if (spec.form == Form.CUT) 2 else 1
            r.until("the act's transition ends") { r.notes("transition").count { it.startsWith("owed: ") } == transitionsBefore + acts &&
                r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.until("the CLOSE of slot 2 reaches RIGHT") { events.toList().any { it.act && it.arm == Arm.RIGHT && subs(it.msg!!).any { s -> closeOf(s, 2) } } }
            r.settle("after the act")
            r.until("the engine idle on both lenses") { synchronized(r.sim) { Arm.entries.all { r.sim.conformancePlan(it, System.currentTimeMillis()) == MotionSim.PLAN_FOREVER } } }
            r.settle("idle")
            act.set(false)
            val notes = r.notes("transition")
            val owed = notes.filter { it.startsWith("owed: ") }.drop(transitionsBefore)
            val word = "${spec.tier.word} ${spec.effect.label}${if (spec.reversed) " reversed" else ""}"
            check(owed.size == acts && owed.all { it.startsWith("owed: $word over") }) { "${spec.name}: the shell owed $owed, not $acts × $word" }
            check(notes.count { it.startsWith("in half's last step drawn on RIGHT") } == 1) { "${spec.name}: not one whole in half on RIGHT: $notes" }
            if (spec.form == Form.CUT) check(notes.any { it.startsWith("a new act replaces Select's (IN)") }) { "${spec.name}: the second Select did not cut the first in its in half: $notes" }
            if (spec.form == Form.WRITE) check(r.onLoop { r.rows[0].big }) { "${spec.name}: the scroll did not reach the window" }
            // C3 SV2, LATE: the PLAY-AFTER rode a frame flush that reached RIGHT after its out half's last step
            if (spec.form == Form.LATE) check(afterOnRight.get()?.let { it.startsWith("done d") && "carries drawing" in it } == true && framesEarly.get() >= 1) {
                "${spec.name}: not late — RIGHT's out half when the PLAY-AFTER reached it: ${afterOnRight.get()}; frame flushes before it ${framesEarly.get()}" }
            val st = r.t.state.value
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val all = events.toList()
            val halves = ArrayList<String>()
            val vec = vector(spec, all, st.cacheSize / 1024, st.flagsInForce, finalCrc, halves)
            if (spec.form == Form.LATE) halves += "late: ${framesEarly.get()} frame flush(es) under the drawing out half; RIGHT's out half when the PLAY-AFTER reached it ${afterOnRight.get()}"
            return Captured(spec, vec, owed, halves)
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------------------------------- SV-083
    /**
     * APK 0.83, stage SV (`private/build083/SV-083-brief.md`): a ship act. Beside the transitions ([Spec]) 0.83 ships two
     * kinds of program that no filled vector held — Main's scroll effects ([ScrollSpec], K1-1: played at every notch) and
     * the notice box's programs ([NoticeSpec], K1-4a: the focus timer and its two arrivals) — each played by the shell
     * itself on [TransitionShellTest.Rig] over the instant link, on his glasses' session as it is today: the C3 build
     * (DamageCaps 0xdff) at ladder stage 4 (flags 0x3c), 256 KiB with BIND.
     */
    sealed interface Act { val name: String }

    /** SV-083 item 1: how a scroll act moves Main's list. NOTCHES: a notch down and then a notch up from row 0, each fired on
     *  the hook (the input op) with its stock echo — the local pan in slot 0, the phone's COMMIT, the burst's SETTLE. WRAP:
     *  the list's two ends — from row 0 a notch up (to the last row), then from there a notch down (to row 0), each played
     *  by the phone from the wrap's record (a PLAY in slot 1 ahead of the DISCARD) — at 352 with his eight rows; at 480 they
     *  all fit (the staging's `wraps` is false, no wrap record: decisions AZ.4) and the end keeps today's edge, the phone's
     *  slide frames, no program (measured on this rig at SV's comparison). BURST: three notches down from row 0, the second and the third fired while the pan before them is
     *  drawing on RIGHT (its close set ends that pan, `FIRMWARE.md` §6.2 step 11). */
    enum class ScrollForm { NOTCHES, WRAP, BURST }

    /** SV-083 item 1: Main's scroll [effect] at layout height [height], `Scroll length` [lengthMs] and `Scroll frames`
     *  [frames] (0 = Auto), at ladder stage [stage] (4 as his glasses run today; 5 with the effects flag armed). [t] and
     *  [period] are the steps and the period Main's staging must hold there — `ScrollEffects.timing` at the rig's layout,
     *  checked at the capture (a changed fit fails the capture loudly rather than writing another program). */
    data class ScrollSpec(override val name: String, val effect: ScrollEffect, val height: Int, val t: Int, val period: Int,
                          val form: ScrollForm = ScrollForm.NOTCHES, val lengthMs: Int = 300, val frames: Int = 0, val stage: Int = 4) : Act

    /** SV-083 item 2: how a notice act runs. WHOLE: the box over Main, shown at once (TIMER: the timer's PLAY in the box's
     *  own batch, beside the cover's CLEARs) or by its arrival (FADE, SLIDE: the arrival's PLAY, its motion, then at the
     *  phone's tick the timer's PLAY, which closes it); the timer to its 17th step; the phone's 2.0 s tick (focus: the
     *  focus repaint's batch CLOSEs slot 2); the dismissal (a double tap on the focused box: the furl) and Main uncovered
     *  (its SETs, LIST, the DISCARD, the SETTLE). NOTCH: a notch on the ring while the timer draws — the cover has CLEARed
     *  Main's entries, so the hook passes it (no local pan) and its stock echo moves the list under the box: the phone's
     *  slide frames and the timer's restart PLAY, whose next frame CLOSEs the new timer (K1-4a fix B1/B-1: the phone's steps
     *  for the rest of the grace); then the phone's steps to focus, the dismissal, the uncover. WITHDRAW: a record notice
     *  read on another surface while its timer draws (S1-2 Q3): the box folds away unfocused — the furl's first batch
     *  CLOSEs the drawing timer — and Main is uncovered. */
    enum class NoticeForm { WHOLE, NOTCH, WITHDRAW }

    /** SV-083 item 2: the notice box's [style] program as the shell plays it at layout height [height]. */
    data class NoticeSpec(override val name: String, val style: PopupPrograms.Style, val height: Int, val form: NoticeForm = NoticeForm.WHOLE) : Act

    /**
     * SV-083 item 1: every effect at 352 and 480 (a notch down, a notch up); the wrap's record both ways for Depth and Fall
     * (at 352: at 480 his eight rows fit and do not wrap, [ScrollForm]); a burst of three for Depth and Fall at 480; every `Scroll length` and
     * `Scroll frames` value that changes a program's shape beyond its period, for Depth and Fall at 480; Depth at 480 at
     * stage 5. A program's shape is its steps T (Depth's swap, Fall's table and every segment's steps follow T); the
     * period alone changes no record. At 480 (Depth P 59, Slide and Fall P 65 at Global Depth 8): under Auto the length
     * gives T = ⌊length × 1.024 / P⌋ (225 → 3, 300 → 5 / 4, 450 → 7, 600 → 10 / 9, 800 → 13 / 12); a fixed count F gives
     * T = F and only the period follows the length — so the length under a fixed count is left out, and so are the
     * values whose T another file holds: Depth's frames 10 (= length 600 Auto, the same period too); Fall's frames 4
     * (T 4 = the default's, period 77 against 65) and frames 12 (= length 800 Auto, the same period). None is one step
     * (T 1) whatever the rows say.
     */
    fun scrollSpecs(): List<ScrollSpec> {
        val out = ArrayList<ScrollSpec>()
        fun n(e: ScrollEffect, h: Int, extra: String = "") = "${PREFIX}scroll-${e.id}$extra-$h"
        // (T, P) at 300 ms Auto, the rig's layout (K1-1 §2's table)
        val base = mapOf(
            352 to mapOf(ScrollEffect.NONE to (1 to 46), ScrollEffect.DEPTH to (6 to 46), ScrollEffect.SLIDE to (5 to 52), ScrollEffect.FALL to (5 to 52)),
            480 to mapOf(ScrollEffect.NONE to (1 to 59), ScrollEffect.DEPTH to (5 to 59), ScrollEffect.SLIDE to (4 to 65), ScrollEffect.FALL to (4 to 65)))
        fun at(e: ScrollEffect, h: Int) = base[h]!![e]!!
        for (h in listOf(352, 480)) for (e in base.getValue(h).keys) out += ScrollSpec(n(e, h), e, h, at(e, h).first, at(e, h).second)
        for (e in listOf(ScrollEffect.DEPTH, ScrollEffect.FALL)) out += ScrollSpec(n(e, 352, "-wrap"), e, 352, at(e, 352).first, at(e, 352).second, ScrollForm.WRAP)
        for (e in listOf(ScrollEffect.DEPTH, ScrollEffect.FALL)) out += ScrollSpec(n(e, 480, "-burst"), e, 480, at(e, 480).first, at(e, 480).second, ScrollForm.BURST)
        for ((len, t) in listOf(225 to 3, 450 to 7, 600 to 10, 800 to 13)) out += ScrollSpec(n(ScrollEffect.DEPTH, 480, "-len$len"), ScrollEffect.DEPTH, 480, t, 59, lengthMs = len)
        for ((f, p) in listOf(4 to 77, 6 to 59, 8 to 59, 12 to 59, 16 to 59)) out += ScrollSpec(n(ScrollEffect.DEPTH, 480, "-frames$f"), ScrollEffect.DEPTH, 480, f, p, frames = f)
        for ((len, t) in listOf(225 to 3, 450 to 7, 600 to 9, 800 to 12)) out += ScrollSpec(n(ScrollEffect.FALL, 480, "-len$len"), ScrollEffect.FALL, 480, t, 65, lengthMs = len)
        for (f in listOf(6, 8, 10, 16)) out += ScrollSpec(n(ScrollEffect.FALL, 480, "-frames$f"), ScrollEffect.FALL, 480, f, 65, frames = f)
        // C3 FF: at stage 5 the staging holds the fast DRAW path's fit — Depth at 480 P 16, T 19 (C2's P 59, T 5 above)
        out += ScrollSpec(n(ScrollEffect.DEPTH, 480, "-stage5"), ScrollEffect.DEPTH, 480, 19, 16, stage = 5)
        return out
    }

    /** SV-083 item 2: each of the three programs (the timer, the Fade and the Slide arrival) at 352 and 480, whole; at 480 a
     *  notch while the timer draws, and the withdrawal that uncovers Main with the timer still drawing. */
    fun noticeSpecs(): List<NoticeSpec> {
        val out = ArrayList<NoticeSpec>()
        for (h in listOf(352, 480)) for (s in PopupPrograms.Style.entries) out += NoticeSpec("${PREFIX}notice-${s.name.lowercase()}-$h", s, h)
        out += NoticeSpec("${PREFIX}notice-timer-notch-480", PopupPrograms.Style.TIMER, 480, NoticeForm.NOTCH)
        out += NoticeSpec("${PREFIX}notice-timer-withdraw-480", PopupPrograms.Style.TIMER, 480, NoticeForm.WITHDRAW)
        return out
    }

    // ------------------------------------------------------------------------------------------------- SVR
    /**
     * C3 stage SVR (`private/c3-design/briefs/C3-SVR-brief.md`; decisions AZ (8), BA (5)): how a ring act runs. Main's
     * list is a ring list (LIST-RING with flags 1, `FIRMWARE.md` §7.8, §7.11) where its paint wraps — his eight rows at 288
     * and 352 — at ladder stage 5 (flags 0x7c: EFFECTS in force) on the C3 build (DamageCaps 0xdff), 256 KiB with BIND;
     * step 0 of every ring act holds the session's bind of it (the CLEAR, the strip's and programs' writes, the SETs of
     * Main's pans, LIST-RING, in the batch order the shell sends). NOTCHES: from row 0 a notch down and a notch up in the
     * middle of the list (no field 18), then a wrap notch up from row 0 (to the last row) and a wrap notch down from there
     * (to row 0) — each on the hook (the input op) with its stock echo, the pan to its end, the phone's riding LIST-RING
     * and its COMMIT of the report's own parameters, the burst's SETTLE. TAP: the ring's tap with the cursor on row 0 or
     * on the last row (the walk there, one wrap notch up, is in the session), under `Taps · report` (the report-only
     * entry) or `Taps · glasses` (the press), and the act the phone makes of it: the row's window opens with the Select
     * tier's transition as set by default (Depth). Under `glasses` the phone's flushes wait for room on the link until the
     * press holds its last step on both lenses (the round trip in which the glasses draw it; on the instant link the act's
     * first flush would end the press before its first step), then the Select's out PLAY ends it; with the link full the
     * tap's click is handled before the pump tells the rules Main left the front, so the tap's DISCARD names no list. UNDER_BACK: a Back from w0 into Main's row 0 (the Back tier's Depth;
     * Select and the Lock Screen none) and, while RIGHT's in half draws, a wrap notch up — refused 19 on the lenses (a
     * transition's owner in its close set, §7.9, §7.14) and applied by the phone as today (its frames and the DISCARD
     * naming the last row behind the riding LIST-RING while both lenses' cursors are still on row 0) — then the run's end,
     * and after it a wrap notch down on the glasses.
     *
     * The second review pass's additions (decisions BB (6)): TABLE — the act begins at the session's start, so the start's
     * batch (CLEAR 0xFF and the idle SETTLE: Main is staged after the start, `FIRMWARE.md` §6.7 P9), the strip's and the
     * programs' writes and the offer's bind batch as `LocalRules.front` sends it ([LIST-RING with a fresh mark, the three
     * SETs, the DISCARD naming the row, the SETTLE]) are each a step of their own; then the whole table again as
     * `LocalRules.wholeTable` sends it ([CLEAR 0xFF, the SETs, LIST-RING with a fresh mark], with its one keyframe) — made
     * by the rig the one way the tree makes it with both lenses taking every message: RIGHT's telemetry reply edited on
     * its way back to name a mode 27 refused 14 (P1's record), the lenses having refused nothing — then a wrap notch up on
     * the re-sent table. BURST — from row 1 a notch up and, inside its motion, the wrap notch up (its fire closes the first
     * local instance on both lenses), the two reports handed to the phone after both fires as a round trip would (RIGHT's
     * 115s held back by the rig, then delivered one after the other) and the stock echoes after them as the rubber band
     * sends a fast burst's — with no room on the link while both are handled (the shell's loop pumps after every message,
     * so only a link with no room lets one flush answer two reports; the rig's `pumpHeldForTest`), then room: ONE batch
     * answers them, ONE COMMIT, the newest's. BURST_EACH — the same with room on the link, as the shell does on the instant
     * link by itself: each report answered by the flush after it, the first's COMMIT adopting nothing on the lenses (its
     * instance closed by the wrap's fire; the wrap's newer instance left drawing, §6.5) and writing its END under the
     * drawing pan, then the wrap's COMMIT. (SWITCH — AD.2's strip switch at 256 KiB — went with that switch at K1-2 stage 1,
     * decisions BC O1: a row form's change withdraws the list at every cache size; its file removed by C3 SV3.)
     *
     * C3 SV2 (DM-1's acts, decisions BN, BP (4), CA (1)): FAST — from row 0 three notches down about 120 ticks apart (each
     * fired when RIGHT's pan has drawn its step [FAST_STEP], so each fire closes the pan before it mid-program, §6.2 step
     * 11), each with its stock echo, the reports answered as the shell answers them; the last pan whole. PITCH — the
     * pitch switch (decisions AD's S6, the DM-1 report's §2 item 5) with Main in front: Depth → Crush → Depth, each change
     * K1-1's re-make (the free pan slot's write, then ONE batch [LIST-RING with the new pitch and a fresh mark, the SETs,
     * the DISCARD naming the row]); before each change a notch on the lenses whose report the rig holds on its way until
     * the switch's batch has landed (applied as today, `restaged`: no COMMIT), and after it a notch on the new pans
     * (COMMITted).
     *
     * C3 stage X2MX2 (decisions CM (2), `C3-FR-DM1.md` 2): LATE — from row 0 a notch down whose report (RIGHT's 115) the
     * rig holds on its way to the phone until the pan has walked to its END on both lenses, so its steps past n1 (the
     * last step of its shortest track) are walked with no answer and the shadow still the old picture — as on his
     * glasses, where the answer comes after phase 1; then the report delivered, answered (the COMMIT) and its stock echo.
     *
     * C3 stage SV3 (the lead's addition; K1-2 stage 1, decisions BC O1): WITHDRAW — at 256 KiB a row's form changes with
     * Main in front (row 3's line, as `MainLocalShellTest`'s O1 pin changes it): the list withdrawn (the rules' CLEAR of
     * its entries), the row's re-stage written, the list offered again (its SETs naming the same pans, the mark kept, the
     * return naming the phone's row); a notch fired as a lens is handed the re-stage's first cache write with no entry
     * live on RIGHT, so it matches none and the phone draws it; once bound again a notch down, a notch up, a notch up to
     * row 0 and a wrap up, each COMMITted.
     */
    enum class RingForm { NOTCHES, TAP, UNDER_BACK, TABLE, BURST, BURST_EACH, FAST, PITCH, LATE, WITHDRAW }

    /** C3 SV2: the step RIGHT's pan has drawn when FAST's next notch fires — at P 16 step 8 is drawn 112 ticks after its
     *  fire and step 9 falls due at 128, so the notches go about 120 ticks apart (the motion check's fast three, X1DM1). */
    private const val FAST_STEP = 8

    /** C3 SVR: a ring act — Main's scroll [effect] at layout height [height] in [form]; a TAP's [taps] value and whether
     *  the cursor is on the last row ([atLast]) or on row 0. The steps and the period are the staging's at stage 5, read
     *  at the capture and said (the fast-path fit changes them: the re-play row compares the programs). */
    data class RingSpec(override val name: String, val effect: ScrollEffect, val height: Int, val form: RingForm,
                        val taps: Taps = Taps.REPORT, val atLast: Boolean = false,
                        /** C3 DM-2aF (decisions FA 3): captured with [NoisyText], whose glyphs differ code by code, where a fill
                         *  must see which glyph the firmware draws (Matrix's noise); else [FakeText]. */
                        val noisy: Boolean = false) : Act

    /**
     * C3 SVR: at 288 and 352 (the heights where his eight rows wrap, so Main is a ring list), each Scroll effect's NOTCHES
     * act (None, Depth, Slide, Fall: their pans are four different programs at each height — no two give the same bytes);
     * the tap under `report` and under `glasses`, each on row 0 and on the last row, with Depth (the tap's press is one
     * program whatever the Scroll effect, `ListStaging.Press`); and one each of UNDER_BACK, TABLE, BURST and BURST_EACH
     * at 352 (his height) with Depth. A LIST-RING whose shift wraps is not here: the shell sends none (every LIST it writes has
     * shift 0 — `MainSurface.shift` is 0 — so the modular shift is `v5-ring`'s alone, the firmware's own vector).
     */
    fun ringSpecs(): List<RingSpec> {
        val out = ArrayList<RingSpec>()
        for (h in listOf(288, 352)) {
            // the four effects whose ring acts are shipped and filled (SVR); Crush's and Flip's are C3 SV2's ([sv2Acts]) —
            // iterating `ScrollEffect.entries` here would name them twice
            for (e in listOf(ScrollEffect.NONE, ScrollEffect.DEPTH, ScrollEffect.SLIDE, ScrollEffect.FALL)) out += RingSpec("${PREFIX}ring-${e.id}-$h", e, h, RingForm.NOTCHES)
            for (t in listOf(Taps.REPORT, Taps.GLASSES)) for (last in listOf(false, true))
                out += RingSpec("${PREFIX}ring-tap-${t.name.lowercase()}-${if (last) "last" else "row0"}-$h", ScrollEffect.DEPTH, h, RingForm.TAP, t, last)
        }
        out += RingSpec("${PREFIX}ring-back-wrap-352", ScrollEffect.DEPTH, 352, RingForm.UNDER_BACK)
        out += RingSpec("${PREFIX}ring-table-352", ScrollEffect.DEPTH, 352, RingForm.TABLE)
        out += RingSpec("${PREFIX}ring-burst-352", ScrollEffect.DEPTH, 352, RingForm.BURST)
        out += RingSpec("${PREFIX}ring-burst-each-352", ScrollEffect.DEPTH, 352, RingForm.BURST_EACH)
        return out
    }

    /**
     * C3 stage SV2 (`private/c3-design/briefs/C3-SV2-brief.md`): the program shapes no filled vector held after DM-1 and
     * MX2 with its fix stages, as the shell sends them —
     * - Matrix (MX2 with R1, X1MX2: the out half's drops with their bent last step, the in half's rain with reveal 1, one
     *   grid at the act's plane): at Global Depth 12 (the grid ±12 per lens) and 0 (one rect) for a Select and a Back
     *   (Back's records are Select's); 8 steps a half — a Select at 480 at 720 ms / 16 frames, and the Lock Screen both
     *   ways at his Lock tier, 800 ms / 16 frames (plane 0: one rect); a Select whose window's frame lands after the out
     *   half's end ([Form.LATE]). The default acts at 5 steps and Global Depth 8 are [specs]' own (written again here).
     * - Crush and Flip on Main at stage 5 (DM-1, X1DM1, X2DM1a): Crush's ring NOTCHES at 288 and 352 (its source windows
     *   read the ring's margins at v = −1 and n) and Flip's at 352 (its rows are Depth's; at 288 its T is the only
     *   change); both on a plain list at 480 (margins of blank rows); Flip's height turn at stage 4 (version 1, the
     *   fallback's bytes); Crush's FAST burst; the PITCH switch.
     * Not here: Crush at stage 4 (it plays Slide's bytes exactly — `v5-ship-scroll-slide-352`'s programs, pinned by DM-1),
     * a window refused 5 (the phone never stages it: every Crush and Flip program is checked at every cursor offline).
     */
    fun sv2Acts(): List<Act> {
        val m = Transitions.Effect.MATRIX
        val sel = Transitions.Tier.SELECT; val back = Transitions.Tier.BACK; val lock = Transitions.Tier.LOCK
        return listOf(
            Spec("${PREFIX}select-matrix-off4-352", sel, m, false, 352, depth = 12),
            Spec("${PREFIX}back-matrix-rev-off4-352", back, m, true, 352, depth = 12),
            Spec("${PREFIX}select-matrix-depth0-352", sel, m, false, 352, depth = 0),
            Spec("${PREFIX}back-matrix-rev-depth0-352", back, m, true, 352, depth = 0),
            Spec("${PREFIX}select-matrix-s8-480", sel, m, false, 480, length = 720, frames = 16),
            Spec("${PREFIX}lock-matrix-s8-352", lock, m, false, 352, length = 800, frames = 16),
            Spec("${PREFIX}lock-matrix-rev-s8-352", lock, m, true, 352, length = 800, frames = 16),
            Spec("${PREFIX}select-matrix-late-352", sel, m, false, 352, form = Form.LATE),
            RingSpec("${PREFIX}ring-crush-288", ScrollEffect.CRUSH, 288, RingForm.NOTCHES),
            RingSpec("${PREFIX}ring-crush-352", ScrollEffect.CRUSH, 352, RingForm.NOTCHES),
            RingSpec("${PREFIX}ring-flip-352", ScrollEffect.FLIP, 352, RingForm.NOTCHES),
            // (T, P) at 300 ms Auto, the fast path's fit at 480 (the DM-1 report's §3 table; checked at the capture)
            ScrollSpec("${PREFIX}scroll-crush-stage5-480", ScrollEffect.CRUSH, 480, 17, 18, stage = 5),
            ScrollSpec("${PREFIX}scroll-flip-stage5-480", ScrollEffect.FLIP, 480, 13, 22, stage = 5),
            // stage 4: Flip's height turn (version 1) at C2's fit, P 46 T 6 at 352
            ScrollSpec("${PREFIX}scroll-flip-352", ScrollEffect.FLIP, 352, 6, 46),
            RingSpec("${PREFIX}ring-crush-fast-352", ScrollEffect.CRUSH, 352, RingForm.FAST),
            RingSpec("${PREFIX}ring-pitch-352", ScrollEffect.DEPTH, 352, RingForm.PITCH))
    }

    /** C3 stage DM-2a (decisions DW 6): Matrix on Main at stage 5 — its ring NOTCHES at 352 (a notch down and up mid-list,
     *  then the two wraps), its rain records staged with the list; filled at DM-2aV's comparison. */
    fun dm2aActs(): List<Act> = listOf(RingSpec("${PREFIX}ring-matrix-352", ScrollEffect.MATRIX, 352, RingForm.NOTCHES, noisy = true))

    /** C3 stage DM-2b (decisions DW 6, EP 4): Scramble on Main at stage 5 — Matrix's twin: its ring NOTCHES at 352, its
     *  cells and private faces staged with the list from Main's runs through the atlas; filled at DM-2bL's comparison.
     *  C3 DM-2bF (decisions FA 3): captured with [NoisyText], so the fill sees which glyph and which
     *  private font each cell draws. */
    fun dm2bActs(): List<Act> = listOf(RingSpec("${PREFIX}ring-scramble-352", ScrollEffect.SCRAMBLE, 352, RingForm.NOTCHES, noisy = true))

    /** C3 stage X2MX2 (decisions CM (2)): Crush's notch on Main's ring list at 352 answered only after its END ([RingForm.LATE]). */
    fun x2mx2Acts(): List<Act> = listOf(RingSpec("${PREFIX}ring-crush-late-352", ScrollEffect.CRUSH, 352, RingForm.LATE))

    /** C3 stage SV3 (the lead's addition): the withdrawal at 256 KiB on Main's ring list at 352 ([RingForm.WITHDRAW]). */
    fun sv3Acts(): List<Act> = listOf(RingSpec("${PREFIX}ring-withdraw-352", ScrollEffect.DEPTH, 352, RingForm.WITHDRAW))

    /**
     * K1-2 stage 4f (decisions DA (b), (c) 1; DK item 1): a window's list bound on the glasses as list 1 (entries 3–5, slot
     * 0) at ladder stage 5, its window-form pans of [effect] at layout height [height], in [form] — the forms each act holds
     * are listed in `private/build083/K1-2-S4f-built.md` §1. Where no transition belongs to the act the Select, Back and
     * Lock tiers are None, so the session runs none.
     *  - NOTCHES: Settings, then Global (a ring list 1); a notch down, a notch up, a wrap notch up from row 0, a wrap notch
     *    down from the last row.
     *  - ADJUST: at Global's `Presence` (row 3) the adjust level entered (its CLEARs), a notch moving the value, the level
     *    left (the SETs and the DISCARD naming row 3), the row re-staged by withdrawal (P9); a notch down.
     *  - KEPT: Settings → Main (a Back, tier None) → a Select of Settings from Main (Depth): an arrival at the kept staging
     *    (pin 11 (ii)), a notch while RIGHT's in half draws (refused 19 on list 1, applied as today); a notch after the run.
     *  - SELECT_LEVEL: a Select from the bound categories into Global (Depth; a new level, pin 11 (i)), a notch while RIGHT's
     *    in half draws (no entry of list 1 held: the stock path, no report); Global staged after the run; a notch.
     *  - COUNT: a list window's rows from 6 to 7 (a new staging, a new mark); a notch.
     *  - APP_EFFECT: a list window named Files with its own Scroll effect ([effect]) while `Animations` is Depth; a notch
     *    down and a notch up.
     *  - MAIN_OVER: started on Settings (list 1 staged with no staging of Main's); Main's staging grown over list 1's records
     *    in the session (the rig's text drawn as noise, Main's rows made heavy one at a time, each staged whole by a Depth
     *    change — `WindowListShellTest`'s pin 5), list 1 dropped (Main wins), then Main one row lighter again; the act:
     *    Settings again, staged from Main's end, and a notch.
     */
    enum class ListForm { NOTCHES, ADJUST, KEPT, SELECT_LEVEL, COUNT, APP_EFFECT, MAIN_OVER }

    data class ListSpec(override val name: String, val effect: ScrollEffect, val height: Int, val form: ListForm) : Act

    /** K1-2 stage 4f: the list-1 acts — each form once; the effects and heights spread as Main's acts spread them. */
    fun list1Acts(): List<Act> = listOf(
        ListSpec("${PREFIX}list1-notches-depth-352", ScrollEffect.DEPTH, 352, ListForm.NOTCHES),
        ListSpec("${PREFIX}list1-adjust-none-288", ScrollEffect.NONE, 288, ListForm.ADJUST),
        ListSpec("${PREFIX}list1-kept-fall-352", ScrollEffect.FALL, 352, ListForm.KEPT),
        ListSpec("${PREFIX}list1-select-level-depth-480", ScrollEffect.DEPTH, 480, ListForm.SELECT_LEVEL),
        ListSpec("${PREFIX}list1-count-fall-288", ScrollEffect.FALL, 288, ListForm.COUNT),
        ListSpec("${PREFIX}list1-app-slide-352", ScrollEffect.SLIDE, 352, ListForm.APP_EFFECT),
        ListSpec("${PREFIX}list1-main-over-depth-352", ScrollEffect.DEPTH, 352, ListForm.MAIN_OVER))

    /**
     * C3 DW-3 (decisions DQ (1)–(3); the acts as listed before the capture: `private/c3-design/C3-DW3-built.md` §2): the
     * switcher's wheel bound on the glasses as list 2 (entries 6 and 7, the report-only tap at 0, slot 0, a ring list over
     * the block's item table) at ladder stage 5, at layout height [height] and Global `Depth` [depth], in [form]. Select,
     * Back and Lock are None: the wheel's commit and cancel run no transition (`C3-DW1-amendment.md` §4.1).
     *  - OPEN_NOTCHES: on Main, the chord (the open offered over Main); a notch up, a wrap notch up, a wrap notch down, a
     *    notch down.
     *  - YIELD_OPEN: on Global (list 1 laid flush below the block), one more window registered — the block placed again,
     *    larger, so the list yields and is staged again below it — then the chord over the window list.
     *  - LATE_MAIN: on Main, a window opened and the chord in one loop turn — not offered (the item table is the old
     *    order), bound mid-open once it is rewritten; two notches up to Main; the tap commits into Main.
     *  - COMMIT_WINDOW: the wheel bound over Main in the session (Settings visited last, its staging kept); the tap at the
     *    cursor commits into Settings, its list held bound.
     *  - CANCEL: the wheel bound over Main in the session; the double tap posted and at once a notch on the ring — the
     *    cancel's batch, then the notch's report after the close.
     */
    enum class WheelForm { OPEN_NOTCHES, YIELD_OPEN, LATE_MAIN, COMMIT_WINDOW, CANCEL }

    data class WheelSpec(override val name: String, val height: Int, val depth: Int, val form: WheelForm) : Act

    /** C3 DW-3: the wheel's acts — every wire form of DQ (1) in one act; 352 and 480, Depth 8 and 0 spread over them. */
    fun wheelActs(): List<Act> = listOf(
        WheelSpec("${PREFIX}wheel-open-notches-352", 352, 8, WheelForm.OPEN_NOTCHES),
        WheelSpec("${PREFIX}wheel-yield-480", 480, 0, WheelForm.YIELD_OPEN),
        WheelSpec("${PREFIX}wheel-late-main-352", 352, 0, WheelForm.LATE_MAIN),
        WheelSpec("${PREFIX}wheel-commit-window-480", 480, 8, WheelForm.COMMIT_WINDOW),
        WheelSpec("${PREFIX}wheel-cancel-352", 352, 8, WheelForm.CANCEL))

    /** Every ship act: the transitions ([specs]), then SV-083's scroll and notice acts, then SVR's ring acts, then SV2's,
     *  then X2MX2's, then SV3's, then K1-2 stage 4f's list-1 acts, then DW-3's wheel acts. */
    fun acts(): List<Act> = specs() + scrollSpecs() + noticeSpecs() + ringSpecs() + sv2Acts() + x2mx2Acts() + sv3Acts() + list1Acts() + wheelActs() + dm2aActs() + dm2bActs()

    /** [act] as the rig plays it, and its vector. */
    suspend fun captureAct(act: Act): Captured = when (act) {
        is Spec -> capture(act)
        is ScrollSpec -> captureScroll(act)
        is NoticeSpec -> captureNotice(act)
        is RingSpec -> captureRing(act)
        is ListSpec -> captureList(act)
        is WheelSpec -> captureWheel(act)
    }

    private val ANY_TAG: (Int) -> Boolean = { true }

    /** SV-083: the windows on Main in the scroll and notice acts — seven, and Settings: his Main's eight rows (Reader, Tmux,
     *  Files, Torrents, Music, Games, Feed, Settings), so the list wraps at 352 as his does (decisions AZ.4). */
    private const val MAIN_WINDOWS = 7

    /** SV-083: the rig's images and hook inputs in order, each with the engine progress on its lens — the steps drawn by the
     *  instances in [slots] (any tag; each instance counted at the largest d seen for it) — what [vector] places them by.
     *  Attached before the rig starts, so step 0 holds the whole session. */
    private class Recorder(val r: TransitionShellTest.Rig, val slots: List<Int>) {
        val events: MutableList<Event> = java.util.Collections.synchronizedList(ArrayList<Event>())
        val act = java.util.concurrent.atomic.AtomicBoolean(false)
        /** C3 SVR: called with each image as a lens is handed it — before that lens applies it — after it is recorded. */
        @Volatile var spy: ((Arm, ByteArray) -> Unit)? = null
        private val seenL = HashMap<Int, Int>()
        private val seenR = HashMap<Int, Int>()
        fun prog(a: Arm): Int = progress(r.sim, a, if (a == Arm.LEFT) seenL else seenR, slots, ANY_TAG)
        init {
            // inside the model's own dispatch: the progress as the message arrives; and every millisecond between
            r.onImage = { a, img -> events += Event(a, img.copyOf(), null, prog(a), act.get(), at = if (a == Arm.RIGHT && act.get()) slotWords(r.sim, a, slots) else "")
                spy?.invoke(a, img) }
            r.scope.launch { while (true) { synchronized(r.sim) { prog(Arm.LEFT); prog(Arm.RIGHT) }; delay(1) } }
        }
        /** A gesture on the ring: the hook on both lenses, then its stock echo ([TransitionShellTest.Rig.fire]). */
        fun fire(eventId: Int) = r.fire(eventId) { record(eventId) }
        /** C3 SVR: the hook alone on both lenses — the stock echo goes later ([echo]), as the rubber band sends a fast
         *  burst's queued scroll messages after it (`FIRMWARE.md` §6.1, §6.7 P5). */
        fun hook(eventId: Int, also: () -> Unit = {}) = r.inputs(eventId) { record(eventId); also() }
        /** C3 LO-1X: the largest d this recorder saw for each instance in [slot] on [arm] (tag to d); call it inside the
         *  model's lock (the poller writes these maps inside it). */
        fun seenIn(arm: Arm, slot: Int): Map<Int, Int> = (if (arm == Arm.LEFT) seenL else seenR)
            .filterKeys { it ushr 16 == slot }.mapKeys { it.key and 0xFFFF }
        /** Inside the model's lock, just before both lenses take the input: the input at the progress it lands at. */
        private fun record(eventId: Int) {
            events += Event(null, null, longArrayOf(CfwModes.DEV_RING.toLong(), eventId.toLong(), 0), prog(Arm.RIGHT), act.get(), at = slotWords(r.sim, Arm.RIGHT, slots))
        }
        /** C3 SVR: a gesture's stock echo alone (its hook went before, [hook]). */
        fun echo(eventId: Int) = r.t.ringStockForTest(wm.damage.core.local.BoundList.stockEvent(eventId)!!)
        /** RIGHT's images from [from] on (an index into [events]), each as its sub-messages. */
        fun rightFrom(from: Int): List<List<ByteArray>> = events.toList().drop(from).filter { it.arm == Arm.RIGHT }.map { subs(it.msg!!) }
        /** RIGHT's images of the act, each as its sub-messages. */
        fun rightOfAct(): List<List<ByteArray>> = events.toList().filter { it.arm == Arm.RIGHT && it.act }.map { subs(it.msg!!) }
    }

    private fun isSub(s: ByteArray, mode: Int, sub: Int) = s.size >= 2 && (s[0].toInt() and 0x7F) == mode && s[1].toInt() == sub
    private fun playIn(s: ByteArray, slot: Int) = isSub(s, 26, CfwModes.MOTION_PLAY) && s.size >= 3 && s[2].toInt() == slot

    /** Main's entries live on RIGHT (ids 4 and 5), the notice's lane done and no cache work on its way, settled. */
    private suspend fun ready(r: TransitionShellTest.Rig, name: String) {
        r.until("$name: Main's entries live on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30 }
        // C3 DW-1a: and the wheel's block placed and counted at stage 5 (its write is the session's, never the act's)
        r.until("$name: the notice's lane done, no cache work") { r.onLoop { r.shell.noticeLaneDoneForTest() && r.shell.wheelLaneDoneForTest() && !r.shell.cacheWorkInFlight } }
        r.settle("$name: ready")
    }

    /** The engine idle on both lenses, the shell settled. */
    private suspend fun idle(r: TransitionShellTest.Rig, name: String) {
        r.until("$name: the engine idle on both lenses") { synchronized(r.sim) { Arm.entries.all { r.sim.conformancePlan(it, System.currentTimeMillis()) == MotionSim.PLAN_FOREVER } } }
        r.settle("$name: idle")
    }

    /** RIGHT's bodies for [slots] in words: (slot, outcome, drawn, folded) each. */
    private fun said(bodies: List<DamageMsg.Motion>, slots: IntRange): String = bodies.filter { it.slot in slots }
        .joinToString(", ") { "slot ${it.slot} tag %04x outcome ${it.outcome} drawn ${it.drawn} folded ${it.folded}".format(it.tag ?: 0) }

    private suspend fun captureScroll(spec: ScrollSpec): Captured {
        val r = TransitionShellTest.Rig(stage = spec.stage, kib256 = true, nRows = MAIN_WINDOWS)
        try {
            val rec = Recorder(r, listOf(0, 1))
            r.upAt(spec.height) { it.copy(scrollEffect = spec.effect.id, scrollLength = spec.lengthMs, frames = spec.frames) }
            ready(r, spec.name)
            val staged = r.onLoop { r.shell.mainBinding.staging?.let { s -> listOf(s.motion?.effect, s.t, s.period, s.periodInForce) } }
            check(staged == listOf(spec.effect, spec.t, spec.period, spec.period)) {
                "${spec.name}: Main's staging holds (effect, T, P, P in force) $staged, not ${spec.effect.word} T ${spec.t} P ${spec.period}" }
            checkPlays(r, spec.name, spec.effect, spec.stage == 5)
            val count = r.onLoop { r.shell.mainBoundForTest().first?.count } ?: error("${spec.name}: Main's list not bound")
            check(count >= 4) { "${spec.name}: Main's list holds $count rows, a burst needs 4" }
            fun drawing(a: Arm, s: Int) = r.sim.motionForTest(a).slots[s]?.drawing == true
            /** One gesture's end: RIGHT has its burst's SETTLE, neither lens draws in slot 0 or 1, the row is [to]. */
            suspend fun ended(from: Int, to: Int, what: String) = r.until("${spec.name}: $what — its SETTLE on RIGHT, the engine idle, row $to") {
                rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_SETTLE) } } &&
                    synchronized(r.sim) { Arm.entries.none { a -> drawing(a, 0) || drawing(a, 1) } } &&
                    r.onLoop { r.shell.mainBoundForTest().second } == to && r.shell.isQuiescent()
            }
            suspend fun notch(eventId: Int, to: Int, what: String) { val from = rec.events.size; rec.fire(eventId); ended(from, to, what); r.settle("${spec.name}: $what") }
            check(r.row() == 0) { "${spec.name}: Main's row is ${r.row()} at the start, not 0" }
            rec.act.set(true)
            when (spec.form) {
                ScrollForm.NOTCHES -> {
                    notch(CfwModes.EV_UPROLL, 1, "a notch down")
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up")
                }
                ScrollForm.WRAP -> {
                    notch(CfwModes.EV_DOWNROLL, count - 1, "a notch up from row 0 (the wrap to the last row)")
                    notch(CfwModes.EV_UPROLL, 0, "a notch down from the last row (the wrap to row 0)")
                }
                ScrollForm.BURST -> {
                    val from = rec.events.size
                    for (k in 1..3) {
                        val tag0 = synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[0]?.tag }
                        rec.fire(CfwModes.EV_UPROLL)
                        // the next notch goes while this pan is in motion on RIGHT: its own instance, at step 2 or later, not done
                        if (k < 3) r.until("${spec.name}: pan $k drawing on RIGHT") { synchronized(r.sim) {
                            r.sim.motionForTest(Arm.RIGHT).slots[0]?.let { it.tag != tag0 && it.drawing && it.d >= minOf(2, it.T - 1) && it.d < it.T } == true } }
                    }
                    ended(from, 3, "a burst of three notches down")
                }
            }
            idle(r, spec.name)
            rec.act.set(false)
            val act = rec.rightOfAct()
            val wraps = act.sumOf { ss -> ss.count { playIn(it, ListStaging.WRAP_SLOT) } }
            if (spec.form == ScrollForm.WRAP) check(wraps == 2) { "${spec.name}: $wraps wrap PLAY(s) in slot ${ListStaging.WRAP_SLOT}, not one each way" }
            else check(wraps == 0) { "${spec.name}: a wrap PLAY where none was asked" }
            val commits = act.sumOf { ss -> ss.count { isSub(it, 26, CfwModes.MOTION_COMMIT) } }
            val st = r.t.state.value
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val words = ArrayList<String>()
            val vec = vector(spec.name, rec.events.toList(), st.cacheSize / 1024, st.flagsInForce, finalCrc, ArrayList(), listOf(0, 1), ANY_TAG) { _, bodies ->
                val local = bodies.filter { it.slot in 0..1 }
                check(bodies.none { it.outcome == DamageMsg.OUTCOME_REFUSED }) { "${spec.name}: a refusal on RIGHT: ${said(bodies, 0..3)}" }
                val drew = local.filter { it.outcome == DamageMsg.OUTCOME_DREW }
                check(drew.all { (it.folded ?: 0L) == 0L }) { "${spec.name}: a pan folded a step: ${said(drew, 0..1)}" }
                val closed = local.count { it.outcome == DamageMsg.OUTCOME_CLOSED }
                when (spec.form) {
                    // each pan to its last step: a DREW body at T, or (None, T 1) the COMMIT's body with the one step drawn
                    ScrollForm.NOTCHES -> check(closed == 0 && local.groupBy { it.tag }.let { g -> g.size == 2 && g.values.all { b -> b.maxOf { it.drawn ?: 0L } == spec.t.toLong() } }) {
                        "${spec.name}: not two pans whole on RIGHT: ${said(local, 0..1)}" }
                    ScrollForm.WRAP -> check(drew.count { it.slot == ListStaging.WRAP_SLOT } == 2) { "${spec.name}: not two wraps whole on RIGHT: ${said(local, 0..1)}" }
                    ScrollForm.BURST -> check(drew.size == 1 && closed == 2) { "${spec.name}: not two pans cut and the last whole: ${said(local, 0..1)}" }
                }
                words += "RIGHT's bodies: ${said(local, 0..1)}"
            }
            val t = spec.t
            return Captured(spec, vec, listOf("${spec.effect.word} T $t P ${spec.period} (${spec.lengthMs} ms ${if (spec.frames == 0) "Auto" else "${spec.frames} frames"}), " +
                "stage ${spec.stage}, ${spec.form.name.lowercase()}: $commits COMMIT(s), $wraps wrap PLAY(s); flags 0x${st.flagsInForce.toString(16)}") + words)
        } finally { r.close() }
    }

    private suspend fun captureNotice(spec: NoticeSpec): Captured {
        val r = TransitionShellTest.Rig(stage = 4, kib256 = true, nRows = MAIN_WINDOWS)
        try {
            val rec = Recorder(r, listOf(0, 1, 2))
            r.upAt(spec.height) { it.copy(popupUnfurl = false, popupFade = spec.style == PopupPrograms.Style.FADE, popupSlide = spec.style == PopupPrograms.Style.SLIDE) }
            ready(r, spec.name)
            r.until("${spec.name}: the notice's programs counted") { r.onLoop { r.shell.noticeProgramsForTest() }.let { p -> p.isNotEmpty() && p.all { it.endsWith("PROVEN") } } }
            r.settle("${spec.name}: the programs counted")
            r.shell.graceByHand = true                       // the phone's ticks by hand: each where the act names it
            fun slot2() = synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[PopupPrograms.SLOT] }
            fun notice(tag: Int) = (tag and 0xF000) == Shell.NOTICE_TAG
            val ref = if (spec.form == NoticeForm.WITHDRAW) wm.damage.core.app.NoticeRef("sms:sv083", "1") else null
            rec.act.set(true)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00", record = ref))
            r.until("${spec.name}: the first PLAY in slot ${PopupPrograms.SLOT} on RIGHT") { slot2()?.let { notice(it.tag) } == true }
            if (spec.style != PopupPrograms.Style.TIMER) {
                val arrival = slot2()!!
                val f = synchronized(r.sim) { arrival.prog.tracks[0].segs[0].steps }      // the arrival's motion; then its hold
                r.until("${spec.name}: the arrival's motion drawn on RIGHT (step $f)") { slot2()?.let { it.tag == arrival.tag && it.d >= f } == true }
                r.shell.graceStepByHand()                    // the phone's tick at the arrival's end: the timer's PLAY
                r.until("${spec.name}: the timer's PLAY on RIGHT") { slot2()?.let { notice(it.tag) && it.tag != arrival.tag } == true }
            }
            val timer = slot2()!!.tag
            when (spec.form) {
                NoticeForm.WHOLE -> {
                    r.until("${spec.name}: the timer's last step on RIGHT") { slot2()?.let { it.tag == timer && !it.drawing } == true }
                    r.shell.graceStepByHand()                // the 2.0 s tick: focus
                    r.until("${spec.name}: the box focused") { r.onLoop { r.shell.notifications.focused } }
                }
                NoticeForm.NOTCH -> {
                    r.until("${spec.name}: the timer drawing (step 3) on RIGHT") { slot2()?.let { it.tag == timer && it.drawing && it.d >= 3 } == true }
                    rec.fire(CfwModes.EV_UPROLL)
                    r.until("${spec.name}: Main's row moved under the box") { r.row() == 1 }
                    r.settle("${spec.name}: the notch under the box")
                    // the phone's steps take the rest of the grace (fix B1/B-1): each tick by hand, to focus
                    var ticks = 0
                    while (!r.onLoop { r.shell.notifications.focused }) {
                        check(++ticks <= 16) { "${spec.name}: the box never took focus on the phone's steps" }
                        r.shell.graceStepByHand(); r.settle("${spec.name}: the phone's step $ticks")
                    }
                }
                NoticeForm.WITHDRAW -> {
                    r.until("${spec.name}: the timer drawing (step 5) on RIGHT") { slot2()?.let { it.tag == timer && it.drawing && it.d >= 5 } == true }
                    r.shell.withdrawNotice(ref!!, queuedOnly = false)    // read on another surface: the box folds away unfocused
                }
            }
            if (spec.form != NoticeForm.WITHDRAW) { r.settle("${spec.name}: focused"); r.doubleTap() }  // the dismissal: a double tap on the focused box
            r.until("${spec.name}: the box gone, Main's entries live on RIGHT again") {
                !r.onLoop { r.shell.notifications.active } && r.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30 }
            idle(r, spec.name)
            rec.act.set(false)
            val act = rec.rightOfAct()
            val closes = act.indices.filter { i -> act[i].any { isSub(it, 26, CfwModes.MOTION_CLOSE) && it.size >= 3 && it[2].toInt() == PopupPrograms.SLOT } }
            check(closes.isNotEmpty()) { "${spec.name}: no CLOSE of slot ${PopupPrograms.SLOT} in the act" }
            val uncover = act.indexOfLast { ss -> ss.any { isSub(it, 27, CfwModes.BIND_SET) } && ss.any { isSub(it, 27, CfwModes.BIND_LIST) } }
            check(uncover > closes.last()) { "${spec.name}: no uncover (SETs and LIST) after the CLOSE" }
            val plays = act.sumOf { ss -> ss.count { playIn(it, PopupPrograms.SLOT) } }
            val st = r.t.state.value
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val words = ArrayList<String>()
            val vec = vector(spec.name, rec.events.toList(), st.cacheSize / 1024, st.flagsInForce, finalCrc, ArrayList(), listOf(0, 1, 2), ANY_TAG) { _, bodies ->
                check(bodies.none { it.outcome == DamageMsg.OUTCOME_REFUSED }) { "${spec.name}: a refusal on RIGHT: ${said(bodies, 0..3)}" }
                val mine = bodies.filter { it.slot == PopupPrograms.SLOT && notice(it.tag ?: 0) }
                val drew = mine.filter { it.outcome == DamageMsg.OUTCOME_DREW }
                val closed = mine.filter { it.outcome == DamageMsg.OUTCOME_CLOSED }
                check(drew.all { (it.folded ?: 0L) == 0L }) { "${spec.name}: a notice program folded a step: ${said(drew, 2..2)}" }
                when (spec.form) {
                    // the timer whole (its 17 steps); an arrival closed by the timer's PLAY
                    NoticeForm.WHOLE -> check(drew.size == 1 && drew[0].drawn == PopupPrograms.TIMER_STEPS.toLong() &&
                        closed.size == (if (spec.style == PopupPrograms.Style.TIMER) 0 else 1)) { "${spec.name}: ${said(mine, 2..2)}" }
                    // the first timer closed by the restart's PLAY, the restart's closed by the slide frame's CLOSE
                    NoticeForm.NOTCH -> check(drew.isEmpty() && closed.size == 2) { "${spec.name}: ${said(mine, 2..2)}" }
                    // the timer closed while drawing by the furl's CLOSE
                    NoticeForm.WITHDRAW -> check(drew.isEmpty() && closed.size == 1) { "${spec.name}: ${said(mine, 2..2)}" }
                }
                words += "RIGHT's bodies: ${said(mine, 2..2)}"
            }
            val closeBatches = closes.joinToString { i -> act[i].joinToString(" ") { s -> "${s[0].toInt() and 0x7F}/${if (s.size > 1) s[1].toInt() and 0xFF else -1}" } }
            return Captured(spec, vec, listOf("${spec.style.name.lowercase()} ${spec.form.name.lowercase()} at ${spec.height}: $plays PLAY(s) in slot ${PopupPrograms.SLOT}, " +
                "the CLOSE's batch(es) [$closeBatches]; flags 0x${st.flagsInForce.toString(16)}") + words)
        } finally { r.close() }
    }

    /** A batch's sub-messages as `mode/sub` words (a lone message is one). */
    private fun shape(ss: List<ByteArray>): String = ss.joinToString(" ") { s -> "${s[0].toInt() and 0x7F}/${if (s.size > 1) s[1].toInt() and 0xFF else -1}" }

    /** The list a COMMIT, DISCARD or SETTLE names (its fourth body byte; 0xFF none), or -1 for any other sub-message. */
    private fun answerList(s: ByteArray): Int =
        if ((isSub(s, 26, CfwModes.MOTION_COMMIT) || isSub(s, 26, CfwModes.MOTION_DISCARD) || isSub(s, 26, CfwModes.MOTION_SETTLE)) && s.size > 5) s[5].toInt() and 0xFF else -1

    private fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()

    /** C3 SV2: Crush and Flip play their own programs — version 2 at stage 5, Flip's height turn (version 1) below it —
     *  not a fallback (the staging keeps the asked effect in `motion` whatever plays). */
    private fun checkPlays(r: TransitionShellTest.Rig, name: String, effect: ScrollEffect, stage5: Boolean) {
        if (effect != ScrollEffect.CRUSH && effect != ScrollEffect.FLIP) return
        val plays = r.onLoop { r.shell.mainBinding.staging?.plays }
        check(plays != null && plays.effect == effect && plays.v2 == stage5) {
            "$name: Main's staging plays $plays, not ${effect.word} in version ${if (stage5) 2 else 1}" }
    }

    private suspend fun captureRing(spec: RingSpec): Captured {
        val r = TransitionShellTest.Rig(stage = 5, kib256 = true, taps = spec.taps, nRows = MAIN_WINDOWS, text = if (spec.noisy) NoisyText() else FakeText())
        try {
            // the slots whose instances place the act's events: Main's pans (0, 1); with a transition, its halves (2, 3) too
            val slots = if (spec.form == RingForm.TAP || spec.form == RingForm.UNDER_BACK) listOf(0, 1, 2, 3) else listOf(0, 1)
            val rec = Recorder(r, slots)
            if (spec.form == RingForm.TABLE) rec.act.set(true)          // the act from the session's start
            r.upAt(spec.height) { s -> s.copy(scrollEffect = spec.effect.id).let { if (spec.form != RingForm.UNDER_BACK) it else it
                .withTier(Transitions.Tier.SELECT, effects = emptyList()).withTier(Transitions.Tier.LOCK, effects = emptyList())
                .withTier(Transitions.Tier.BACK, effects = listOf(Transitions.Effect.DEPTH.id)) } }
            ready(r, spec.name)
            val staged = r.onLoop { r.shell.mainBinding.staging?.let { s -> listOf(s.ring, s.motion?.effect, s.t, s.period, s.periodInForce) } }
            check(staged != null && staged[0] == true && staged[1] == spec.effect && staged[3] == staged[4]) {
                "${spec.name}: Main's staging holds (ring, effect, T, P, P in force) $staged, not the ring form of ${spec.effect.word} with its period in force" }
            val t = staged[2] as Int; val period = staged[3] as Int
            checkPlays(r, spec.name, spec.effect, stage5 = true)
            val pressT = r.onLoop { r.shell.mainBinding.staging!!.press.program.T }
            val list = r.onLoop { r.shell.mainBoundForTest().first } ?: error("${spec.name}: Main's list not bound")
            val count = list.count
            check(list.ring && count == MAIN_WINDOWS + 1) { "${spec.name}: Main's bound list ring=${list.ring}, $count rows — not his eight rows as a ring list" }
            check(synchronized(r.sim) { Arm.entries.all { r.sim.bindForTest(it).lists[ListStaging.LIST].ring } }) { "${spec.name}: a lens's list does not ring at the start" }
            check(r.row() == 0) { "${spec.name}: Main's row is ${r.row()} at the start, not 0" }
            fun drawing(a: Arm, s: Int) = r.sim.motionForTest(a).slots[s]?.drawing == true
            /** One gesture's end: its burst closed (the lone SETTLE sent) and RIGHT handed a SETTLE since [from], neither lens
             *  draws in slot 0 or 1, the row is [to]. */
            suspend fun ended(from: Int, to: Int, what: String) = r.until("${spec.name}: $what — its SETTLE on RIGHT, the engine idle, row $to") {
                rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_SETTLE) } } && !r.onLoop { r.shell.localBurstOpenForTest() } &&
                    synchronized(r.sim) { Arm.entries.none { a -> drawing(a, 0) || drawing(a, 1) } } &&
                    r.onLoop { r.shell.mainBoundForTest().second } == to && r.shell.isQuiescent()
            }
            suspend fun notch(eventId: Int, to: Int, what: String) { val from = rec.events.size; rec.fire(eventId); ended(from, to, what); r.settle("${spec.name}: $what") }
            fun owed() = r.notes("transition").count { it.startsWith("owed: ") }
            val words = ArrayList<String>()
            /** C3 SV2, PITCH: the steps of the pans in force after each switch. */
            val pitchTs = ArrayList<Int>()
            when (spec.form) {
                RingForm.NOTCHES -> {
                    rec.act.set(true)
                    notch(CfwModes.EV_UPROLL, 1, "a notch down in the middle")
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up in the middle")
                    notch(CfwModes.EV_DOWNROLL, count - 1, "a wrap notch up from row 0")
                    notch(CfwModes.EV_UPROLL, 0, "a wrap notch down from the last row")
                }
                RingForm.TAP -> {
                    if (spec.atLast) { notch(CfwModes.EV_DOWNROLL, count - 1, "the walk to the last row (a wrap notch up)"); idle(r, spec.name) }
                    val want = if (spec.atLast) "settings" else "w0"
                    val before = owed()
                    val press = spec.taps == Taps.GLASSES
                    val tag0 = synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[0]?.tag }
                    rec.act.set(true)
                    // the press: the phone's act waits for room on the link (`pumpHeldForTest`, as TransitionShellTest's
                    // At.OWED row) until the press holds its last step on both lenses — the round trip in which the glasses
                    // draw it — then the act's first flush (the Select's out PLAY, whose close pass ends the press) goes
                    if (press) r.shell.pumpHeldForTest = true
                    rec.fire(CfwModes.EV_TAP)
                    if (press) {
                        r.until("${spec.name}: the press holding its last step on both lenses") { synchronized(r.sim) { Arm.entries.all { a ->
                            r.sim.motionForTest(a).slots[0]?.let { it.tag != tag0 && it.drawing && it.d >= it.T } == true } } }
                        r.until("${spec.name}: the tap acted on by the phone (the link full)") { r.onLoop { r.shell.currentWindowId() } == want }
                        r.shell.pumpHeldForTest = false; r.shell.postIdleTick()
                    }
                    r.until("${spec.name}: the tap's act — $want opens") { r.onLoop { r.shell.currentWindowId() } == want }
                    r.until("${spec.name}: the Select's transition ends") { owed() == before + 1 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
                    r.until("${spec.name}: the CLOSE of slot 2 reaches RIGHT") { rec.rightOfAct().any { ss -> ss.any { closeOf(it, 2) } } }
                    r.settle("${spec.name}: in $want")
                    // K1-2 stage 3a: the act ends once the window list's lane has nothing left to write (Settings' categories
                    // staged and bound as list 1 after the run) — one evaluation on the loop, so the act is the same every run
                    r.until("${spec.name}: the window list's lane done") { r.shell.isQuiescent() && r.onLoop { r.shell.windowListLaneDoneForTest() && !r.shell.cacheWorkInFlight } }
                    r.settle("${spec.name}: in $want, its list's lane done")
                }
                RingForm.UNDER_BACK -> {
                    // the session: into w0 by the ring's tap on row 0 (the Select tier none: no transition)
                    rec.fire(CfwModes.EV_TAP)
                    r.until("${spec.name}: into w0") { r.onLoop { r.shell.currentWindowId() } == "w0" }
                    r.settle("${spec.name}: in w0"); idle(r, spec.name)
                    val before = owed()
                    val repairs0 = r.notes("repair").size
                    rec.act.set(true)
                    r.doubleTap()                                           // the Back: a double tap reaches only the phone
                    r.until("${spec.name}: back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
                    r.until("${spec.name}: RIGHT's in half drawing") { r.onLoop { r.shell.transitionPhaseForTest() } == "IN" &&
                        synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 && it.d < it.T } } == true }
                    r.until("${spec.name}: Main's entries live on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30 }
                    check(r.row() == 0) { "${spec.name}: back on Main's row ${r.row()}, not 0" }
                    check(synchronized(r.sim) { Arm.entries.all { r.sim.bindForTest(it).lists[ListStaging.LIST].ring } }) { "${spec.name}: a lens's list does not ring under the Back" }
                    val applied0 = r.notes("applied").size
                    rec.fire(CfwModes.EV_DOWNROLL)
                    r.until("${spec.name}: the notch applied as today — the row is Main's last") { r.row() == count - 1 }
                    r.until("${spec.name}: RIGHT's report of the notch") { r.notes("applied").size > applied0 }
                    val note = r.notes("applied").drop(applied0)
                    // the reason's number is read on the model's own answer below (19); the journal's words may change
                    check(note.size == 1 && " refused " in note[0] && "wrapped" !in note[0]) { "${spec.name}: RIGHT did not refuse the ring fire: $note" }
                    words += "under the Back: $note"
                    r.until("${spec.name}: the Back's run ends") { owed() == before + 1 && r.onLoop { r.shell.transitionPhaseForTest() } == null }
                    r.until("${spec.name}: the Back's CLOSE of slot 2 reaches RIGHT") { rec.rightOfAct().any { ss -> ss.any { closeOf(it, 2) } } }
                    r.settle("${spec.name}: after the Back"); idle(r, spec.name)
                    // C3 X3M0 (decisions BB (2)): the notch refused 19 is no table-repair evidence — no `repair` note, no SET
                    // re-sent in the act's answer to it
                    check(r.notes("repair").size == repairs0) { "${spec.name}: a table repair in the act: ${r.notes("repair").drop(repairs0)}" }
                    notch(CfwModes.EV_UPROLL, 0, "after the run, a wrap notch down from the last row")
                }
                RingForm.TABLE -> {
                    // P1's record naming 27: RIGHT's next telemetry reply edited, once, on its way back (a mode 27 refused 14
                    // at present 0x5A5A — the lenses refused nothing), then a read; the shell sends the whole table again
                    val start = r.t as StartRig
                    val edit = java.util.concurrent.atomic.AtomicBoolean(true)
                    start.onNotify = { a, p -> if (a == Arm.RIGHT && StartRig.telemetry(p) != null && edit.getAndSet(false))
                        StartRig.withRecordField(StartRig.withRecordField(StartRig.withRecordField(p, 23, CfwModes.BIND_MODE.toLong()), 24, 14), 25, 0x5A5A) else p }
                    val from = rec.events.size
                    r.t.devProbe("telemetry", "read")
                    r.until("${spec.name}: the whole table again on RIGHT") { rec.rightFrom(from).any { ss ->
                        ss.any { isSub(it, 27, CfwModes.BIND_CLEAR) && (it[2].toInt() and 0xFF) == 0xFF } && ss.any { isSub(it, 27, CfwModes.BIND_LIST_RING) } } }
                    start.onNotify = null
                    check(!edit.get() && r.notes("repair").any { "evidence=refusal mode=27" in it && "object=table" in it }) { "${spec.name}: no repair of a record naming 27: ${r.notes("repair")}" }
                    r.settle("${spec.name}: the table again"); idle(r, spec.name)
                    notch(CfwModes.EV_DOWNROLL, count - 1, "a wrap notch up on the re-sent table")
                }
                RingForm.BURST, RingForm.BURST_EACH -> {
                    notch(CfwModes.EV_UPROLL, 1, "the walk to row 1"); idle(r, spec.name)
                    // the two reports reach the phone after both fires, as over a round trip: RIGHT's 115s held by the rig and
                    // handed over one after the other; the stock echoes after them, as the rubber band sends a fast burst's queued
                    // scroll messages. BURST: no room on the link while both are handled, so one flush answers both;
                    // BURST_EACH: room, so each report is answered by the flush after it
                    val start = r.t as StartRig
                    val held = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
                    val hold = java.util.concurrent.atomic.AtomicBoolean(true)
                    start.onNotify = { a, p -> if (hold.get() && a == Arm.RIGHT && StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID &&
                        p.size >= wm.damage.core.wire.AaFrame.HEADER + wm.damage.core.wire.AaFrame.CRC && DamageMsg.parseApplied(StartRig.body(p)) != null) { held += p; null } else p }
                    val from = rec.events.size
                    rec.act.set(true)
                    val tag0 = synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[0]?.tag }
                    // C3 LO-1X: RIGHT as the hook went in (inside its hold) and as the wait gives up (one hold), for the text
                    var atHook = ""; var sent0 = 0; var matched0 = 0L
                    rec.hook(CfwModes.EV_DOWNROLL) {                       // row 1 → row 0
                        val m = r.sim.motionForTest(Arm.RIGHT); val b = r.sim.bindForTest(Arm.RIGHT)
                        atHook = "slot 0 ${slotState(m, 0)}; ${bindState(b, ListStaging.LIST)}"; sent0 = m.sent.size; matched0 = b.matched }
                    val hookedAt = System.currentTimeMillis()
                    r.until({ "${spec.name}: the first pan drawing on RIGHT — " + synchronized(r.sim) {
                        val m = r.sim.motionForTest(Arm.RIGHT); val b = r.sim.bindForTest(Arm.RIGHT)
                        val since = m.sent.drop(sent0)
                        val bodies = since.filter { it.first == DamageMsg.MOTION_FIELD }.map { DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, it.second))!! }
                        "RIGHT ${System.currentTimeMillis() - hookedAt} ms after the hook: slot 0's tag before it %04x; at the hook ".format(tag0 ?: 0) +
                            "[$atHook]; now [slot 0 ${slotState(m, 0)}; ${bindState(b, ListStaging.LIST)}]; the recorder saw in slot 0 " +
                            rec.seenIn(Arm.RIGHT, 0).entries.joinToString(" ", "{", "}") { "%04x:%d".format(it.key, it.value) } +
                            "; matched since the hook ${b.matched - matched0}; 114 since the hook ${bodies.size} [${said(bodies, 0..3)}]; " +
                            "115 since the hook ${since.count { it.first == DamageMsg.APPLIED_FIELD }}" } }) { synchronized(r.sim) {
                        r.sim.motionForTest(Arm.RIGHT).slots[0]?.let { it.tag != tag0 && it.drawing && it.d >= 1 && it.d < it.T } == true } }
                    rec.hook(CfwModes.EV_DOWNROLL)                          // row 0 → the last row, inside the first's motion
                    r.until("${spec.name}: both reports held") { held.size == 2 }
                    hold.set(false); start.onNotify = null
                    // the shell's loop pumps after every message, so a report is answered by the next flush whenever the link
                    // has room: BURST gives the link no room while both reports are handled (`pumpHeldForTest`, the rig's
                    // "a link with no room at all"), then room — one flush answers both
                    val verdicts = java.util.Collections.synchronizedList(ArrayList<String>())
                    if (spec.form == RingForm.BURST) { r.onLoop { r.shell.localVerdictsForTest = verdicts }; r.shell.pumpHeldForTest = true }
                    for (p in synchronized(held) { held.toList() }) start.deliver(Arm.RIGHT, p)
                    if (spec.form == RingForm.BURST) {
                        r.until("${spec.name}: both reports handled with the link full") { verdicts.size >= 2 }
                        r.onLoop { r.shell.localVerdictsForTest = null }
                        words += "the two reports' verdicts with the link full: $verdicts"
                        r.shell.pumpHeldForTest = false; r.shell.postIdleTick()
                    }
                    r.until("${spec.name}: the burst's answer on RIGHT") { rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_COMMIT) } } }
                    rec.echo(CfwModes.EV_DOWNROLL); rec.echo(CfwModes.EV_DOWNROLL)
                    ended(from, count - 1, "a burst across the wrap")
                    r.settle("${spec.name}: after the burst")
                }
                RingForm.FAST -> {
                    rec.act.set(true)
                    val from = rec.events.size
                    for (k in 1..3) {
                        val tag0 = synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.SLOT]?.tag }
                        rec.fire(CfwModes.EV_UPROLL)
                        // the next notch as RIGHT's pan holds its step FAST_STEP: inside its motion, past Crush's phase 1
                        if (k < 3) r.until("${spec.name}: pan $k at its step $FAST_STEP on RIGHT") { synchronized(r.sim) {
                            r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.SLOT]?.let { it.tag != tag0 && it.drawing && it.d >= FAST_STEP && it.d < it.T } == true } }
                    }
                    ended(from, 3, "three notches down about 120 ticks apart")
                    r.settle("${spec.name}: after the three")
                }
                RingForm.PITCH -> {
                    val start = r.t as StartRig
                    val rowH = r.onLoop { r.shell.mainBinding.staging!!.layout.rowH }
                    check(list.pitch == -rowH) { "${spec.name}: Depth's ring list at pitch ${list.pitch}, not −$rowH" }
                    rec.act.set(true)
                    for ((effect, pitch) in listOf(ScrollEffect.CRUSH to rowH, ScrollEffect.DEPTH to -rowH)) {
                        val row0 = r.row()
                        // a notch on the lenses whose report (RIGHT's 115) the rig holds on its way until the switch has landed
                        val held = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
                        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
                        start.onNotify = { a, p -> if (hold.get() && a == Arm.RIGHT && StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID &&
                            p.size >= wm.damage.core.wire.AaFrame.HEADER + wm.damage.core.wire.AaFrame.CRC && DamageMsg.parseApplied(StartRig.body(p)) != null) { held += p; null } else p }
                        val switched0 = r.notes("mainstage").count { it.startsWith("pans switched") }
                        val from = rec.events.size
                        rec.hook(CfwModes.EV_UPROLL)
                        r.until("${spec.name}: ${effect.word}: the notch's report held") { held.size == 1 }
                        r.shell.updateSettings { it.copy(scrollEffect = effect.id) }
                        r.until("${spec.name}: ${effect.word}: the pans re-made and switched", 15_000) { r.notes("mainstage").count { it.startsWith("pans switched") } > switched0 }
                        r.until("${spec.name}: ${effect.word}: both lenses hold the list at pitch $pitch and the re-made pans") {
                            val l = r.onLoop { r.shell.mainBoundForTest().first }
                            l != null && l.pitch == pitch && synchronized(r.sim) { Arm.entries.all { a -> r.sim.bindForTest(a).let { b -> b.lists[ListStaging.LIST].let { it.pitch == pitch && it.ring } &&
                                b.entries.filterNotNull().firstOrNull { it.eventId == CfwModes.EV_UPROLL }?.prog == l.entryFor(CfwModes.EV_UPROLL)!!.prog4 } } } }
                        hold.set(false); start.onNotify = null
                        // the held report now, and its stock echo: refused by the switch's fresh mark (`restaged`), applied as today
                        val snaps0 = r.notes("snapback").size
                        for (p in synchronized(held) { held.toList() }) start.deliver(Arm.RIGHT, p)
                        rec.echo(CfwModes.EV_UPROLL)
                        ended(from, (row0 + 1).mod(count), "${effect.word}: the notch from before the switch applied as today")
                        val snaps = r.notes("snapback").drop(snaps0)
                        check(snaps.any { "cause=restaged" in it }) { "${spec.name}: ${effect.word}: the report from before the switch was not refused restaged: $snaps" }
                        r.settle("${spec.name}: ${effect.word}: the held report answered"); idle(r, spec.name)
                        checkPlays(r, spec.name, effect, stage5 = true)
                        val staging = r.onLoop { r.shell.mainBinding.staging!!.let { it.t to it.period } }
                        pitchTs += staging.first
                        words += "${effect.word} (T ${staging.first} P ${staging.second}, pitch $pitch): the held notch's answer — ${snaps.joinToString()}"
                        notch(CfwModes.EV_UPROLL, (row0 + 2).mod(count), "${effect.word}: a notch on the new pans")
                        idle(r, spec.name)
                    }
                }
                RingForm.LATE -> {
                    val n1 = wm.damage.core.motion.ScrollEffects.halfStep(t)
                    check(n1 + 2 <= t) { "${spec.name}: n1 $n1 + 2 past T $t" }
                    val start = r.t as StartRig
                    val held = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
                    val hold = java.util.concurrent.atomic.AtomicBoolean(true)
                    start.onNotify = { a, p -> if (hold.get() && a == Arm.RIGHT && StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID &&
                        p.size >= wm.damage.core.wire.AaFrame.HEADER + wm.damage.core.wire.AaFrame.CRC && DamageMsg.parseApplied(StartRig.body(p)) != null) { held += p; null } else p }
                    val from = rec.events.size
                    rec.act.set(true)
                    val tag0 = synchronized(r.sim) { Arm.entries.map { r.sim.motionForTest(it).slots[ListStaging.SLOT]?.tag } }
                    rec.hook(CfwModes.EV_UPROLL)                            // row 0 → row 1, the hook alone
                    r.until("${spec.name}: the notch's report held") { held.size == 1 }
                    r.until("${spec.name}: the pan at its END on both lenses, unanswered") { synchronized(r.sim) { Arm.entries.withIndex().all { (i, a) ->
                        r.sim.motionForTest(a).slots[ListStaging.SLOT]?.let { it.tag != tag0[i] && it.d >= it.T } == true } } }
                    check(rec.rightFrom(from).none { ss -> ss.any { answerList(it) >= 0 } }) { "${spec.name}: the phone answered before the report was delivered" }
                    hold.set(false); start.onNotify = null
                    for (p in synchronized(held) { held.toList() }) start.deliver(Arm.RIGHT, p)
                    r.until("${spec.name}: the notch's COMMIT on RIGHT") { rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_COMMIT) } } }
                    rec.echo(CfwModes.EV_UPROLL)
                    ended(from, 1, "a notch down answered after its END")
                    r.settle("${spec.name}: after the notch")
                    words += "Crush T $t, n1 $n1: the report delivered with both lenses at step T"
                }
                RingForm.WITHDRAW -> {
                    fun cacheWrite(ss: List<ByteArray>) = ss.any { (it[0].toInt() and 0x7F) == 19 || (it[0].toInt() and 0x7F) == 25 }
                    val progs0 = list.entries.filter { !it.isTap }.map { it.prog4 }.toSet()
                    val restaged0 = r.notes("mainstage").count { it.startsWith("row 3 re-staged") }
                    // the notch in between: [its index in the events, RIGHT's S before it, RIGHT's S after it]
                    val between = java.util.concurrent.atomic.AtomicReference<IntArray?>(null)
                    val from = rec.events.size
                    rec.act.set(true)
                    rec.spy = { _, img -> if (between.get() == null && cacheWrite(subs(img)) && r.sim.bindForTest(Arm.RIGHT).field39() == 0) {
                        val s0 = r.sim.bindForTest(Arm.RIGHT).seq
                        val at = rec.events.size
                        rec.fire(CfwModes.EV_UPROLL)                       // row 0 → row 1, with no entry live on RIGHT
                        between.set(intArrayOf(at, s0, r.sim.bindForTest(Arm.RIGHT).seq))
                    } }
                    r.rows[3].s = r.rows[3].s.copy(line = "summary 33")    // row 3's line changes: its row form
                    r.shell.services.requestRender(r.rows[3])
                    r.until("${spec.name}: withdrawn for row 3's form", 15_000) { r.notes("mainstage").any { it.startsWith("withdrawn — row 3") } }
                    r.until("${spec.name}: row 3 re-staged", 15_000) { r.notes("mainstage").count { it.startsWith("row 3 re-staged") } > restaged0 }
                    r.until("${spec.name}: offered again — Main's entries live on RIGHT, the list bound") {
                        r.sim.bindForTest(Arm.RIGHT).field39() and 0x30 == 0x30 && r.onLoop { r.shell.mainBoundForTest().first } != null }
                    rec.spy = null
                    r.settle("${spec.name}: bound again"); idle(r, spec.name)
                    val b = between.get() ?: error("${spec.name}: no notch fired while the re-stage's write was on its way with no entry on RIGHT")
                    check(b[1] == b[2]) { "${spec.name}: the notch in between took an S on RIGHT (${b[1]} → ${b[2]}): it matched an entry" }
                    check(r.row() == 1) { "${spec.name}: the notch in between did not move the row as today: row ${r.row()}" }
                    check(synchronized(r.sim) { Arm.entries.all { r.sim.bindForTest(it).lists[ListStaging.LIST].let { l -> l.ring && l.cursor == 1 } } }) {
                        "${spec.name}: the return did not name the phone's row 1 on both lenses" }
                    val list1 = r.onLoop { r.shell.mainBoundForTest().first }!!
                    check(list1.entries.filter { !it.isTap }.map { it.prog4 }.toSet() == progs0 && list1.mark == list.mark) {
                        "${spec.name}: the list bound again does not name the same pans with the mark kept (P9)" }
                    // on RIGHT, from the change: the CLEAR of Main's entries first, then the re-stage's write, then the SETs
                    val imgs = rec.rightFrom(from)
                    val clearAt = imgs.indexOfFirst { ss -> ss.any { isSub(it, 27, CfwModes.BIND_CLEAR) } }
                    val writeAt = imgs.indexOfFirst { cacheWrite(it) }
                    val setAt = imgs.indices.firstOrNull { it > writeAt && imgs[it].any { s -> isSub(s, 27, CfwModes.BIND_SET) } } ?: -1
                    check(clearAt in 0 until writeAt && setAt > writeAt) { "${spec.name}: on RIGHT the CLEAR at $clearAt, the write at $writeAt, the SETs at $setAt" }
                    check(imgs[clearAt].none { isSub(it, 27, CfwModes.BIND_CLEAR) && (it[2].toInt() and 0xFF) == 0xFF }) { "${spec.name}: the withdrawal's CLEAR is the whole table's" }
                    val notchRight = rec.events.toList().drop(b[0]).filter { it.arm == Arm.RIGHT }.map { subs(it.msg!!) }
                        .let { l -> l.take(l.indexOfFirst { ss -> ss.any { s -> isSub(s, 27, CfwModes.BIND_SET) } }.let { if (it < 0) l.size else it }) }
                    words += "withdrawn: RIGHT handed the CLEAR [${shape(imgs[clearAt])}] (image ${clearAt + 1} of the act), the write [${shape(imgs[writeAt])}] (${writeAt + 1}), " +
                        "the bind again [${shape(imgs[setAt])}] (${setAt + 1}); the notch in between (S ${b[1]} kept) and RIGHT's images from it to the bind: ${notchRight.map { "[${shape(it)}]" }}"
                    notch(CfwModes.EV_UPROLL, 2, "a notch down after the bind")
                    notch(CfwModes.EV_DOWNROLL, 1, "a notch up")
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up to row 0")
                    notch(CfwModes.EV_DOWNROLL, count - 1, "a wrap notch up from row 0")
                }
            }
            idle(r, spec.name)
            rec.act.set(false)
            val recorded = rec.events.toList()
            val firstAct = recorded.indexOfFirst { it.act }
            check(firstAct >= 0 && recorded.drop(firstAct).all { it.act }) { "${spec.name}: an image or input of the session came after the act began (it would be laid in step 0)" }
            // the act's ring form, as RIGHT was handed it
            val act = rec.rightOfAct()
            val subsOfAct = act.flatten()
            val lists = subsOfAct.filter { isSub(it, 27, CfwModes.BIND_LIST) || isSub(it, 27, CfwModes.BIND_LIST_RING) }
            // (a press's act with the link full answers the tap with a DISCARD naming no list, so it may carry no LIST)
            check((lists.isNotEmpty() || spec.form == RingForm.TAP) && lists.all { isSub(it, 27, CfwModes.BIND_LIST_RING) && it.size == 13 && it[12].toInt() == 1 }) {
                "${spec.name}: a LIST of the act is not LIST-RING with flags 1: ${lists.map { hex(it) }}; the act's batches ${act.map { "[${shape(it)}]" }}; " +
                    "its answers ${subsOfAct.filter { answerList(it) >= 0 }.map { hex(it) }}" }
            check(subsOfAct.none { playIn(it, ListStaging.WRAP_SLOT) }) { "${spec.name}: a PLAY in slot ${ListStaging.WRAP_SLOT} on a ring list" }
            for (ss in act) for ((i, s) in ss.withIndex()) if (answerList(s).let { it >= 0 && it != 0xFF })
                check(ss.take(i).any { isSub(it, 27, CfwModes.BIND_LIST_RING) }) { "${spec.name}: an answer naming the list without a LIST-RING before it in its batch: ${shape(ss)}" }
            val commits = subsOfAct.filter { isSub(it, 26, CfwModes.MOTION_COMMIT) && it.size == 18 }
            // the offer's bind of the ring list (items a, h): the first batch holding its LIST-RING and the SETs of Main's pans
            // ([LIST-RING with a fresh mark, the SETs, the DISCARD naming the row, the SETTLE], as `LocalRules.front` sends it)
            val rightAll = recorded.filter { it.arm == Arm.RIGHT }.map { subs(it.msg!!) }
            fun clearAll(s: ByteArray) = isSub(s, 27, CfwModes.BIND_CLEAR) && s.size >= 3 && (s[2].toInt() and 0xFF) == 0xFF
            val bindAt = rightAll.indexOfFirst { ss -> ss.any { isSub(it, 27, CfwModes.BIND_LIST_RING) } && ss.any { isSub(it, 27, CfwModes.BIND_SET) } }
            check(bindAt >= 0) { "${spec.name}: no batch binds the ring list (LIST-RING beside the SETs)" }
            val bind = rightAll[bindAt]
            val bindTail = bind.filter { (it[0].toInt() and 0x7F) == 26 || (it[0].toInt() and 0x7F) == 27 }
            check(isSub(bindTail.first(), 27, CfwModes.BIND_LIST_RING) && bindTail.drop(1).takeWhile { isSub(it, 27, CfwModes.BIND_SET) }.size == 3 &&
                bindTail.any { isSub(it, 26, CfwModes.MOTION_DISCARD) && u16(it, 6) == 0 } && isSub(bindTail.last(), 26, CfwModes.MOTION_SETTLE)) {
                "${spec.name}: the offer's bind batch is not [LIST-RING, SET × 3, DISCARD naming row 0, SETTLE]: [${shape(bind)}]" }
            val startBatch = rightAll.take(bindAt).lastOrNull { ss -> ss.any { clearAll(it) } }
            check(startBatch != null && startBatch.none { isSub(it, 27, CfwModes.BIND_SET) || isSub(it, 27, CfwModes.BIND_LIST_RING) }) {
                "${spec.name}: the start's batch before the offer is not CLEAR 0xFF without a list: ${startBatch?.let { shape(it) }}" }
            val plainBefore = rightAll.flatten().count { isSub(it, 27, CfwModes.BIND_LIST) }
            if (plainBefore > 0) words += "the run sent $plainBefore plain LIST(s)"
            words += "the start's batch [${shape(startBatch!!)}]"
            // TABLE: the whole table again, [CLEAR 0xFF, the SETs, LIST-RING with a fresh mark], after the offer
            val table = rightAll.drop(bindAt + 1).firstOrNull { ss -> ss.any { clearAll(it) } }
            if (spec.form == RingForm.TABLE) {
                val tail = table?.filter { (it[0].toInt() and 0x7F) == 26 || (it[0].toInt() and 0x7F) == 27 }
                check(tail != null && clearAll(tail.first()) && tail.drop(1).takeWhile { isSub(it, 27, CfwModes.BIND_SET) }.size == 3 &&
                    tail.getOrNull(4)?.let { l -> isSub(l, 27, CfwModes.BIND_LIST_RING) && l[11] != bind.first { isSub(it, 27, CfwModes.BIND_LIST_RING) }[11] } == true) {
                    "${spec.name}: the whole table is not [CLEAR 0xFF, SET × 3, LIST-RING with a fresh mark]: ${table?.let { shape(it) }}" }
                words += "the whole table again [${shape(table!!)}]"
            } else check(table == null) { "${spec.name}: a whole table again where none was provoked: [${shape(table!!)}]" }
            // UNDER_BACK (item l): the DISCARD answering the refused fire, naming the wrapped row behind the riding LIST-RING
            if (spec.form == RingForm.UNDER_BACK) check(act.any { ss -> ss.indexOfFirst { isSub(it, 26, CfwModes.MOTION_DISCARD) && u16(it, 6) == count - 1 }
                    .let { i -> i > 0 && ss.take(i).any { s -> isSub(s, 27, CfwModes.BIND_LIST_RING) } } }) {
                "${spec.name}: no DISCARD naming row ${count - 1} behind a LIST-RING answers the refused fire" }
            if (spec.form == RingForm.UNDER_BACK) act.filter { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_DISCARD) && u16(it, 6) == count - 1 } }.let { b ->
                check(b.none { ss -> ss.any { isSub(it, 27, CfwModes.BIND_SET) } }) { "${spec.name}: a SET rides the answer to the refused fire: ${b.map { shape(it) }}" } }
            val st = r.t.state.value
            check(st.flagsInForce == 0x7c) { "${spec.name}: flags in force 0x${st.flagsInForce.toString(16)}, not stage 5's 0x7c" }
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val applied = ArrayList<DamageMsg.Applied>()
            val vec = vector(spec.name, recorded, st.cacheSize / 1024, st.flagsInForce, finalCrc, ArrayList(), slots, ANY_TAG, applied) { _, bodies ->
                check(bodies.none { it.outcome == DamageMsg.OUTCOME_REFUSED }) { "${spec.name}: a refusal on RIGHT: ${said(bodies, 0..3)}" }
                check(bodies.filter { it.outcome == DamageMsg.OUTCOME_DREW }.all { (it.folded ?: 0L) == 0L }) { "${spec.name}: a step folded: ${said(bodies, 0..3)}" }
                val pans = bodies.filter { it.slot == ListStaging.SLOT && it.tag?.let { g -> g and 0xC000 == 0 } == true }
                val halves = bodies.filter { it.slot in 2..3 && it.outcome == DamageMsg.OUTCOME_DREW && ((it.tag ?: 0) and 0xE000) == 0xC000 }
                /** Each pan of [n] notches to its last step: a DREW body at T, or (None, T 1) the COMMIT's body with its one step. */
                fun pansWhole(n: Int) = pans.filter { it.outcome != DamageMsg.OUTCOME_CLOSED }.groupBy { it.tag }.let { g -> g.size == n && g.values.all { b -> b.maxOf { it.drawn ?: 0L } == t.toLong() } }
                when (spec.form) {
                    RingForm.NOTCHES, RingForm.WITHDRAW -> check(pansWhole(4) && pans.none { it.outcome == DamageMsg.OUTCOME_CLOSED }) { "${spec.name}: not four pans whole on RIGHT: ${said(pans, 0..0)}" }
                    RingForm.TAP -> check(halves.size == 2 && (spec.taps != Taps.GLASSES || pans.singleOrNull()?.let { it.outcome == DamageMsg.OUTCOME_CLOSED && it.drawn == pressT.toLong() } == true)) {
                        "${spec.name}: not both halves of the Select whole${if (spec.taps == Taps.GLASSES) " and the press closed at its HOLD (drawn $pressT)" else ""} on RIGHT: ${said(bodies, 0..3)}" }
                    RingForm.UNDER_BACK -> check(halves.size == 2 && pansWhole(1)) { "${spec.name}: not both halves of the Back and the wrap's pan whole on RIGHT: ${said(bodies, 0..3)}" }
                    // the first local instance closed by the wrap's fire with no body (its own slot, §6.2 step 11); the wrap's pan whole
                    RingForm.TABLE, RingForm.BURST, RingForm.LATE -> check(pansWhole(1) && pans.none { it.outcome == DamageMsg.OUTCOME_CLOSED }) {
                        "${spec.name}: not one pan whole on RIGHT: ${said(pans, 0..0)}" }
                    // BURST_EACH: the first report's COMMIT adopts nothing (its instance closed by the wrap's fire, the wrap's
                    // newer instance left drawing: §6.5 COMMIT) — its one body outcome 3, drawn 0 — then the wrap's pan whole
                    RingForm.BURST_EACH -> check(pans.groupBy { it.tag }.let { g -> g.size == 2 && g.values.first().singleOrNull()?.let { it.outcome == DamageMsg.OUTCOME_COMMITTED && it.drawn == 0L } == true &&
                        g.values.last().maxOf { it.drawn ?: 0L } == t.toLong() } && pans.none { it.outcome == DamageMsg.OUTCOME_CLOSED }) {
                        "${spec.name}: not the first COMMIT adopting nothing and the wrap's pan whole on RIGHT: ${said(pans, 0..0)}" }
                    // C3 SV2 (a local instance is tagged its fire's S, §6.2 step 12). FAST: the first two pans cut by the next
                    // fire's close (silently, §6.2 step 11), none of them whole; the last pan whole
                    RingForm.FAST -> applied.filter { !it.isTap }.map { it.s }.let { s ->
                        fun most(tag: Int?) = pans.filter { it.tag == tag }.maxOfOrNull { it.drawn ?: 0L } ?: 0L
                        check(s.size == 3 && s.dropLast(1).all { most(it) < t } && most(s.last()) == t.toLong()) {
                            "${spec.name}: not two pans cut and the last whole on RIGHT: ${said(pans, 0..0)}" } }
                    // PITCH: each notch after a switch whole at the new pans' T; those before it never adopted
                    RingForm.PITCH -> applied.filter { !it.isTap }.map { it.s }.let { s ->
                        fun most(tag: Int?) = pans.filter { it.tag == tag }.maxOfOrNull { it.drawn ?: 0L } ?: 0L
                        check(s.size == 4 && pitchTs.size == 2 && most(s[1]) == pitchTs[0].toLong() && most(s[3]) == pitchTs[1].toLong() &&
                            pans.none { (it.tag == s[0] || it.tag == s[2]) && it.outcome == DamageMsg.OUTCOME_COMMITTED }) {
                            "${spec.name}: not each notch after a switch whole (T $pitchTs) with those before it unadopted on RIGHT: ${said(pans, 0..0)}" } }
                }
                words += "RIGHT's 114 bodies of the act: ${said(bodies, 0..3)}"
            }
            // RIGHT's 115s of the act, as the model laid out answers them
            fun bad(what: String): Nothing = error("${spec.name}: $what — ${applied.map { it.describe() }}")
            when (spec.form) {
                RingForm.NOTCHES -> {
                    if (applied.size != 4 || applied.any { it.isTap || it.outcome != DamageMsg.APPLIED_DREW }) bad("not four scroll fires that drew")
                    if (applied.map { it.cursor } != listOf(1, 0, count - 1, 0)) bad("the cursors are not 1, 0, ${count - 1}, 0")
                    if (applied.map { it.field18 } != listOf(null, null, 1L, 1L)) bad("field 18 is not absent in the middle and 1 at both wraps")
                }
                RingForm.TAP -> {
                    val a = applied.singleOrNull() ?: bad("not one report")
                    if (!a.isTap || a.outcome != DamageMsg.APPLIED_DREW || a.cursor != (if (spec.atLast) count - 1 else 0) || a.field18 != null) bad("not the tap's report at its row")
                    if ((a.params != null) != (spec.taps == Taps.GLASSES)) bad("the tap's form is not ${spec.taps}'s")
                }
                RingForm.UNDER_BACK -> {
                    if (applied.size != 2) bad("not two reports")
                    val (a, b) = applied
                    // §7.9 reason 19: a fire whose close set holds an owning instance; fields 10–14 carried (§6.5)
                    if (a.outcome != DamageMsg.APPLIED_REFUSED || a.reason != 19 || a.params == null || a.field18 != null || a.cursor != 0) bad("the notch under the Back is not refused 19 at row 0")
                    if (b.outcome != DamageMsg.APPLIED_DREW || b.cursor != 0 || b.field18 != 1L) bad("the notch after the run is not a wrap that drew")
                }
                RingForm.TABLE -> {
                    val a = applied.singleOrNull() ?: bad("not one report")
                    val fresh = table!!.first { isSub(it, 27, CfwModes.BIND_LIST_RING) }[11].toInt() and 0xFF
                    if (a.outcome != DamageMsg.APPLIED_DREW || a.cursor != count - 1 || a.field18 != 1L || a.mark != fresh) bad("the wrap on the re-sent table did not draw at its fresh mark $fresh")
                }
                RingForm.BURST, RingForm.BURST_EACH -> {
                    if (applied.size != 2 || applied.any { it.isTap || it.outcome != DamageMsg.APPLIED_DREW }) bad("not two scroll fires that drew")
                    if (applied.map { it.cursor } != listOf(0, count - 1) || applied.map { it.field18 } != listOf(null, 1L)) bad("not a notch to row 0 and then the wrap")
                    val want = if (spec.form == RingForm.BURST) 1 else 2
                    if (commits.size != want) bad("not $want COMMIT(s) for the burst: ${commits.map { hex(it) }}")
                }
                RingForm.FAST -> {
                    if (applied.size != 3 || applied.any { it.isTap || it.outcome != DamageMsg.APPLIED_DREW }) bad("not three scroll fires that drew")
                    if (applied.map { it.cursor } != listOf(1, 2, 3) || applied.any { it.field18 != null }) bad("the cursors are not 1, 2, 3 with no field 18")
                }
                // SV3: the notch while withdrawn matched no entry (no report); the four after the bind drew
                RingForm.WITHDRAW -> {
                    if (applied.size != 4 || applied.any { it.isTap || it.outcome != DamageMsg.APPLIED_DREW }) bad("not four scroll fires that drew")
                    if (applied.map { it.cursor } != listOf(2, 1, 0, count - 1)) bad("the cursors are not 2, 1, 0, ${count - 1}")
                    if (applied.map { it.field18 } != listOf(null, null, null, 1L)) bad("field 18 is not absent for the three and 1 at the wrap")
                }
                RingForm.LATE -> {
                    val a = applied.singleOrNull() ?: bad("not one report")
                    if (a.isTap || a.outcome != DamageMsg.APPLIED_DREW || a.cursor != 1 || a.field18 != null) bad("not a notch down to row 1 that drew")
                }
                RingForm.PITCH -> {
                    if (applied.size != 4 || applied.any { it.isTap || it.outcome != DamageMsg.APPLIED_DREW }) bad("not four scroll fires that drew")
                    if (applied.map { it.cursor } != listOf(1, 2, 3, 4) || applied.any { it.field18 != null }) bad("the cursors are not 1, 2, 3, 4 with no field 18")
                }
            }
            // the phone's answer to each fire that drew: the COMMIT of the report's own parameters at the absolute cursor (a
            // burst's: the newest's alone). C3 SV2: FAST — the newest's at least, and every COMMIT of the act its own report's;
            // PITCH — each notch after a switch, and none for a notch from before it (refused `restaged`)
            val drewScroll = applied.filter { !it.isTap && it.outcome == DamageMsg.APPLIED_DREW }
            val owedCommits = when (spec.form) {
                RingForm.BURST, RingForm.FAST -> drewScroll.takeLast(1)
                RingForm.PITCH -> drewScroll.filterIndexed { i, _ -> i % 2 == 1 }
                else -> drewScroll
            }
            for (a in owedCommits) {
                val c = commits.firstOrNull { u16(it, 3) == a.s } ?: bad("no COMMIT of S ${a.s}")
                if (u16(c, 6) != a.cursor || (0..3).any { s16(c, 10 + 2 * it) != a.params!![it] }) bad("the COMMIT of S ${a.s} is not the report's parameters at its cursor: ${hex(c)}")
            }
            if (spec.form == RingForm.FAST) for (c in commits) {
                val a = drewScroll.firstOrNull { it.s == u16(c, 3) } ?: bad("a COMMIT of no report of the act: ${hex(c)}")
                if (u16(c, 6) != a.cursor || (0..3).any { s16(c, 10 + 2 * it) != a.params!![it] }) bad("the COMMIT of S ${a.s} is not the report's parameters at its cursor: ${hex(c)}")
            }
            if (spec.form == RingForm.PITCH) for (a in drewScroll.filterIndexed { i, _ -> i % 2 == 0 })
                if (commits.any { u16(it, 3) == a.s }) bad("a COMMIT of S ${a.s}, the notch from before a switch")
            // PITCH: each switch's ONE batch — the LIST-RING (its pitch, a fresh mark) ahead of the SETs naming the re-made pans
            if (spec.form == RingForm.PITCH) {
                val switches = act.filter { ss -> ss.any { isSub(it, 27, CfwModes.BIND_LIST_RING) } && ss.any { isSub(it, 27, CfwModes.BIND_SET) } }
                if (switches.size != 2) bad("not two batches each carrying a LIST-RING and SETs: ${switches.map { shape(it) }}")
                for (ss in switches) {
                    val li = ss.indexOfFirst { isSub(it, 27, CfwModes.BIND_LIST_RING) }
                    if (ss.withIndex().any { (i, s) -> isSub(s, 27, CfwModes.BIND_SET) && i < li }) bad("a SET ahead of the switch's LIST-RING: [${shape(ss)}]")
                    words += "a switch's batch [${shape(ss)}], its LIST-RING's pitch ${s16(ss[li], 9)} mark ${ss[li][11].toInt() and 0xFF}"
                }
            }
            words += "RIGHT's 115s: ${applied.joinToString(" | ") { it.describe().substringBefore(" uptime") }}"
            val answers = act.filter { ss -> ss.any { answerList(it) >= 0 } }.map { shape(it) }.distinct()
            return Captured(spec, vec, listOf("${spec.effect.word} T $t P $period, ring at ${spec.height}, stage 5 (flags 0x${st.flagsInForce.toString(16)}), " +
                "${spec.form.name.lowercase()}${if (spec.form == RingForm.TAP) " ${spec.taps.name.lowercase()} ${if (spec.atLast) "last row" else "row 0"}" else ""}: " +
                "${commits.size} COMMIT(s); the bind batch [${shape(bind)}]; the act's answer batches ${answers.map { "[$it]" }}") + words)
        } finally { r.close() }
    }

    /** K1-2 stage 4f: a window whose own view is a list of [n] rows, each drawn as a few marks of its own (the COUNT and
     *  APP_EFFECT acts' window, as `WindowListShellTest`'s). */
    private class ListWin(id: String, name: String, @Volatile var n: Int) :
        wm.damage.core.shell.DamageWindow(id, name, wm.damage.core.gfx.IconKind.FILES) {
        val model = wm.damage.core.shell.ListModel()
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.ListView(model, { n },
            { g, i, r, _ -> for (k in 0..(i % 5)) g.fillRect(r.x + 12 + k * 22 + (i * 7) % 40, r.y + 6, 14, 12, 120 + 20 * (k % 4)) },
            { g, r, i -> g.fillRect(r.x + 4, r.y + 4, 8 + (i * 13) % 200, r.h - 8, 255) }, {})
        override fun summary() = Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private fun isSetOf(s: ByteArray, list: Int) = isSub(s, 27, CfwModes.BIND_SET) && s.size > 5 && s[5].toInt() == list
    private fun isClearOf(s: ByteArray, entry: Int) = isSub(s, 27, CfwModes.BIND_CLEAR) && s.size > 2 && (s[2].toInt() and 0xFF) == entry
    private fun isListOf(s: ByteArray, list: Int) = (isSub(s, 27, CfwModes.BIND_LIST) || isSub(s, 27, CfwModes.BIND_LIST_RING)) && s.size >= 12 && s[2].toInt() == list
    /** A DISCARD in slot 0 naming [list] (mode 26 sub 4: byte 2 the slot, byte 5 the list, bytes 6–7 the cursor). */
    private fun isDiscard0Of(s: ByteArray, list: Int) = isSub(s, 26, CfwModes.MOTION_DISCARD) && s.size >= 8 && s[2].toInt() == 0 && s[5].toInt() == list

    /** K1-2 stage 4f: a list-1 act ([ListSpec]) as the shell plays it, checked as it goes, and its vector. */
    private suspend fun captureList(spec: ListSpec): Captured {
        val over = spec.form == ListForm.MAIN_OVER
        val lw = when (spec.form) { ListForm.COUNT -> ListWin("lw", "Listy", 6); ListForm.APP_EFFECT -> ListWin("files", "Files", 6); else -> null }
        val mainEffect = if (spec.form == ListForm.APP_EFFECT) ScrollEffect.DEPTH else spec.effect
        fun base(s: wm.damage.core.shell.ShellSettings) = s.copy(heightMode = spec.height, cachedText = "on", taps = Taps.REPORT, scrollEffect = mainEffect.id)
            .withTier(Transitions.Tier.SELECT, effects = emptyList()).withTier(Transitions.Tier.BACK, effects = emptyList())
            .withTier(Transitions.Tier.LOCK, effects = emptyList())
            .let { if (spec.form == ListForm.APP_EFFECT) it.withAppStyle("files") { a -> a.copy(scrollEffect = spec.effect.id) } else it }
        val r = TransitionShellTest.Rig(stage = 5, kib256 = true, nRows = MAIN_WINDOWS, text = if (over) NoisyText() else FakeText(),
            startOn = if (over) "settings" else null, startSettings = base(wm.damage.core.shell.ShellSettings()))
        try {
            if (lw != null) r.shell.register(lw)
            val withRun = spec.form == ListForm.KEPT || spec.form == ListForm.SELECT_LEVEL
            // the slots whose instances place the act's events: the lists' pans (0, 1); with a transition, its halves too
            val slots = if (withRun) listOf(0, 1, 2, 3) else listOf(0, 1)
            val rec = Recorder(r, slots)
            val words = ArrayList<String>()
            fun wrow(): Int = r.onLoop { r.shell.windowBoundForTest().second }
            fun wlist(): wm.damage.core.local.BoundList? = r.onLoop { r.shell.windowBoundForTest().first }
            fun entriesOf(a: Arm, list: Int) = synchronized(r.sim) { r.sim.bindForTest(a).entries.withIndex().filter { it.value?.list == list }.map { it.index } }
            fun wireMark1(): Int = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).lists[1].mark }
            fun staged() = r.notes("liststage").count { it.startsWith("staged") }
            fun phase() = r.onLoop { r.shell.transitionPhaseForTest() }
            suspend fun lanesDone(what: String) {
                r.until("${spec.name}: $what — every lane done", 60_000) { r.shell.isQuiescent() && r.onLoop { r.shell.noticeLaneDoneForTest() &&
                    r.shell.wheelLaneDoneForTest() && r.shell.windowListLaneDoneForTest() && !r.shell.cacheWorkInFlight && r.shell.transitionPhaseForTest() == null } }
                r.settle("${spec.name}: $what")
            }
            suspend fun windowBound(what: String) {
                r.until("${spec.name}: $what — the window list bound on RIGHT", 60_000) {
                    r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first != null } &&
                        synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().count { it.list == 1 && !it.tap } == 2 } }
                lanesDone(what)
            }
            suspend fun open(id: String) {
                r.onLoop { r.shell.services.openWindow(id, null) }
                r.until("${spec.name}: $id in front") { r.onLoop { r.shell.currentWindowId() } == id }
            }
            fun drawing(a: Arm, s: Int) = r.sim.motionForTest(a).slots[s]?.drawing == true
            /** One gesture on list 1 ended: its burst closed (the lone SETTLE sent) and RIGHT handed a SETTLE since [from],
             *  neither lens draws in slot 0 or 1, list 1's row is [to]. */
            suspend fun ended(from: Int, to: Int, what: String, row: () -> Int = { wrow() }) = r.until("${spec.name}: $what — its SETTLE on RIGHT, the engine idle, row $to") {
                rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_SETTLE) } } && !r.onLoop { r.shell.localBurstOpenForTest() } &&
                    synchronized(r.sim) { Arm.entries.none { a -> drawing(a, 0) || drawing(a, 1) } } && row() == to && r.shell.isQuiescent()
            }
            suspend fun notch(eventId: Int, to: Int, what: String, row: () -> Int = { wrow() }) {
                val from = rec.events.size; rec.fire(eventId); ended(from, to, what, row); r.settle("${spec.name}: $what") }
            suspend fun intoGlobal() {
                rec.fire(CfwModes.EV_TAP)                                   // the categories' row 0: Global (no transition)
                r.until("${spec.name}: Global's rows bound") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 5 } == true } }
                lanesDone("Global"); windowBound("Global")
            }
            suspend fun selectTierOn() {
                r.shell.updateSettings { it.withTier(Transitions.Tier.SELECT, effects = listOf(Transitions.Effect.DEPTH.id)) }
                r.settle("${spec.name}: the Select tier at Depth"); lanesDone("the Select tier at Depth")
            }
            /** The in half of the act's transition drawing on RIGHT. */
            suspend fun inHalf() = r.until("${spec.name}: RIGHT's in half drawing") { phase() == "IN" &&
                synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[3]?.let { it.drawing && it.d >= 1 && it.d < it.T } == true } }
            suspend fun runEnds() {
                r.until("${spec.name}: both lenses' in halves landed whole") { synchronized(r.sim) { Arm.entries.all { r.sim.motionForTest(it).slots[3]?.let { s -> !s.drawing && s.d == s.T } == true } } }
                r.until("${spec.name}: the run ended") { phase() == null }
                r.until("${spec.name}: the CLOSE of slot 2 reaches RIGHT") { rec.rightOfAct().any { ss -> ss.any { closeOf(it, 2) } } }
            }

            // ---- the session
            if (!over) r.upAt(spec.height) { base(it) } else {
                r.shell.start(); r.settle("${spec.name}: start")
                r.until("${spec.name}: the atlas goes live") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
            }
            var row0 = 0
            var count = 0
            var oldMark = -1
            var mainEnd = 0
            when (spec.form) {
                ListForm.NOTCHES -> { open("settings"); windowBound("Settings"); intoGlobal() }
                ListForm.ADJUST -> {
                    // (the walk to `Presence` is the act's: a session's inputs are laid at one tick in step 0)
                    open("settings"); windowBound("Settings"); intoGlobal()
                    check(r.onLoop { r.shell.settings.presence } == 1) { "${spec.name}: the fixture — Presence not at dim at rest" }
                }
                ListForm.KEPT -> {
                    // (Main's walk to Settings' row is the act's, after the Back: a session's inputs are laid at one tick)
                    open("settings"); windowBound("Settings"); selectTierOn(); windowBound("Settings, the Select tier on")
                }
                ListForm.SELECT_LEVEL -> { open("settings"); windowBound("Settings"); selectTierOn(); windowBound("Settings, the Select tier on") }
                ListForm.COUNT, ListForm.APP_EFFECT -> {
                    open(lw!!.id); windowBound(lw.name)
                    if (spec.form == ListForm.APP_EFFECT) {
                        val plays = r.onLoop { r.shell.windowListBinding.staging?.let { it.motion?.effect to it.plays.effect } }
                        val mainPlays = r.onLoop { r.shell.mainBinding.staging?.motion?.effect }
                        check(plays == spec.effect to spec.effect && mainPlays == ScrollEffect.DEPTH) {
                            "${spec.name}: Files' list plays $plays and Main's $mainPlays — not ${spec.effect.word} beside Main's Depth" }
                    }
                }
                ListForm.MAIN_OVER -> {
                    windowBound("started on Settings")
                    val ms1 = r.onLoop { r.shell.windowListBinding.staging } ?: error("${spec.name}: no staging of Settings' list")
                    check(r.onLoop { r.shell.mainBinding.staging } == null) { "${spec.name}: a staging of Main's at a start on Settings" }
                    r.doubleTap()
                    r.until("${spec.name}: Main staged", 60_000) { r.onLoop { r.shell.currentWindowId() == null && r.shell.mainBinding.staging != null && r.shell.mainBinding.counted } }
                    lanesDone("Main")
                    // Main grows by its rows' content, one ink-heavy row at a time, each followed by a `Depth` change that
                    // stages it whole again (`WindowListShellTest`'s pin 5), until its staging reaches list 1's records
                    var depth = 8
                    var k = 0
                    val light = r.rows.map { it.s }
                    fun mainStaged() = r.notes("mainstage").count { it.startsWith("staged") || "cannot be staged" in it }
                    while (r.onLoop { r.shell.mainBinding.staging?.end ?: 0 } <= ms1.start && k < r.rows.size) {
                        r.rows[k].s = wm.damage.core.shell.DamageWindow.Summary("M@W#".repeat(24), detail = "detail of $k", more = true); k++
                        val n0 = mainStaged()
                        depth = if (depth == 8) 4 else 8
                        r.shell.updateSettings { it.copy(depth = depth) }
                        r.until("${spec.name}: Main staged whole again at Depth $depth", 60_000) { mainStaged() > n0 &&
                            r.onLoop { r.shell.mainBinding.staging == null || r.shell.mainBinding.counted } }
                        lanesDone("Main at Depth $depth")
                        if (r.onLoop { r.shell.mainBinding.staging } == null) {
                            k--; r.rows[k].s = light[k]                         // past what Main's region holds: one row lighter
                            r.until("${spec.name}: Main staged again one row lighter", 60_000) { r.onLoop { r.shell.mainBinding.staging != null && r.shell.mainBinding.counted } }
                            lanesDone("Main one row lighter")
                            break
                        }
                    }
                    val main = r.onLoop { r.shell.mainBinding.staging } ?: error("${spec.name}: Main not staged: ${r.notes("mainstage").takeLast(3)}")
                    check(main.end > ms1.start) { "${spec.name}: Main's staging (to ${main.end}) does not reach list 1's records (from ${ms1.start})" }
                    check(r.notes("liststage").any { it.startsWith("dropped — Main's staging at") && "(Main wins)" in it }) { "${spec.name}: list 1 not dropped: ${r.notes("liststage").takeLast(3)}" }
                    words += "Main grown over list 1: $k heavy row(s), Main at ${main.start}..${main.end}, list 1 was at ${ms1.start}..${ms1.end}"
                    // then one row lighter, staged whole again (the pin's other branch): the first capture found list 1 refused
                    // from the grown Main's end (12,032 B of space against the 13,744 B Settings' list takes), so the act's
                    // staging from Main's end needs a Main as large as the one before the overlap
                    k--; r.rows[k].s = light[k]
                    val n1 = mainStaged()
                    depth = if (depth == 8) 4 else 8
                    r.shell.updateSettings { it.copy(depth = depth) }
                    r.until("${spec.name}: Main staged whole again one row lighter", 60_000) { mainStaged() > n1 &&
                        r.onLoop { r.shell.mainBinding.staging != null && r.shell.mainBinding.counted } }
                    lanesDone("Main one row lighter")
                    val lighter = r.onLoop { r.shell.mainBinding.staging }!!
                    words += "then one row lighter: Main at ${lighter.start}..${lighter.end}"
                    mainEnd = lighter.end
                }
            }
            idle(r, spec.name)
            val refusals0 = r.onLoop { r.shell.windowListRefusalsForTest() }

            // ---- the act
            when (spec.form) {
                ListForm.NOTCHES, ListForm.APP_EFFECT -> {
                    val l = wlist() ?: error("${spec.name}: no list bound")
                    count = l.count
                    if (spec.form == ListForm.NOTCHES) check(l.ring && l.list == 1 && l.slot == 0) { "${spec.name}: Global's rows not a ring list 1 in slot 0 (ring ${l.ring}, list ${l.list}, slot ${l.slot})" }
                    check(wrow() == 0) { "${spec.name}: list 1 at row ${wrow()}, not 0" }
                    rec.act.set(true)
                    notch(CfwModes.EV_UPROLL, 1, "a notch down")
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up")
                    if (spec.form == ListForm.NOTCHES) {
                        notch(CfwModes.EV_DOWNROLL, count - 1, "a wrap notch up from row 0")
                        notch(CfwModes.EV_UPROLL, 0, "a wrap notch down from the last row")
                    }
                }
                ListForm.ADJUST -> {
                    val ms = r.onLoop { r.shell.windowListBinding.staging }
                    oldMark = wlist()!!.mark
                    val restaged0 = r.notes("liststage").count { it.startsWith("row 3 re-staged") }
                    rec.act.set(true)
                    for (k in 1..3) notch(CfwModes.EV_UPROLL, k, "the walk to Presence, row $k")
                    idle(r, spec.name)
                    rec.fire(CfwModes.EV_TAP)                               // Presence: the adjust level, a cover
                    r.until("${spec.name}: adjusting — slot 0 is nobody's") { r.onLoop { r.shell.slot0ForTest() } == null }
                    r.until("${spec.name}: entries 3–5 cleared on RIGHT") { entriesOf(Arm.RIGHT, 1).isEmpty() }
                    r.settle("${spec.name}: adjusting")
                    val seq = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).seq }
                    rec.fire(CfwModes.EV_DOWNROLL)                          // dim at rest → away at rest: the value, not the list
                    r.until("${spec.name}: Presence changed") { r.onLoop { r.shell.settings.presence } == 0 }
                    check(synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).seq } == seq) { "${spec.name}: the notch while adjusting took an S on RIGHT" }
                    r.settle("${spec.name}: the value moved")
                    rec.fire(CfwModes.EV_TAP)                               // the adjust level left
                    r.until("${spec.name}: row 3 re-staged", 15_000) { r.notes("liststage").count { it.startsWith("row 3 re-staged") } > restaged0 }
                    windowBound("offered again")
                    check(r.onLoop { r.shell.windowListBinding.staging } === ms && wlist()!!.mark == oldMark) { "${spec.name}: the staging or its mark not kept (P9)" }
                    check(wrow() == 3) { "${spec.name}: list 1 at row ${wrow()} after the adjust, not 3" }
                    words += "re-staged: ${r.notes("liststage").filter { it.startsWith("withdrawn") || "re-staged" in it }.takeLast(2)}"
                    notch(CfwModes.EV_UPROLL, 4, "a notch down after the adjust")
                }
                ListForm.KEPT -> {
                    val ms1 = r.onLoop { r.shell.windowListBinding.staging }
                    oldMark = wireMark1()
                    val mark = wlist()!!.mark
                    val staged0 = staged()
                    rec.act.set(true)
                    r.doubleTap()                                           // the Back (tier None): a double tap reaches only the phone
                    r.until("${spec.name}: back on Main, its list bound on RIGHT") { r.onLoop { r.shell.currentWindowId() == null &&
                        r.shell.slot0ForTest() == "main" && r.shell.mainBinding.counted } && entriesOf(Arm.RIGHT, 0).size >= 2 }
                    lanesDone("back on Main")
                    check(r.onLoop { r.shell.windowListBinding.staging } === ms1) { "${spec.name}: the window list's staging not kept while Main is in front" }
                    val mainCount = r.onLoop { r.shell.mainBoundForTest().first }?.count ?: error("${spec.name}: Main's list not bound")
                    check(r.row() == 0) { "${spec.name}: Main at row ${r.row()}, not 0" }
                    notch(CfwModes.EV_DOWNROLL, mainCount - 1, "Main's wrap notch up to Settings' row") { r.row() }
                    idle(r, spec.name)
                    rec.fire(CfwModes.EV_TAP)                               // Main's last row: a Select of Settings (Depth)
                    inHalf()
                    r.until("${spec.name}: list 1 bound on RIGHT, or the run ended") { phase() == null ||
                        synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().count { it.list == 1 && !it.tap } == 2 } }
                    check(phase() == "IN" && synchronized(r.sim) { r.sim.motionForTest(Arm.RIGHT).slots[3]?.drawing == true }) {
                        "${spec.name}: list 1's SETs did not ride the held frame (not bound on RIGHT while the in half draws)" }
                    val applied0 = r.notes("applied").size
                    row0 = r.onLoop { r.shell.focusedRowForTest() }
                    rec.fire(CfwModes.EV_UPROLL)
                    r.until("${spec.name}: the notch applied as today") { r.onLoop { r.shell.focusedRowForTest() } == row0 + 1 }
                    r.until("${spec.name}: RIGHT's report of the notch") { r.notes("applied").size > applied0 }
                    words += "the notch in the in half: ${r.notes("applied").drop(applied0)}"
                    runEnds()
                    windowBound("Settings, after the run")
                    check(r.onLoop { r.shell.windowListBinding.staging } === ms1 && wlist()!!.mark == mark && staged() == staged0) {
                        "${spec.name}: not the same staging and mark (no staging of the window list)" }
                    check(wrow() == row0 + 1) { "${spec.name}: list 1 at row ${wrow()}, not the moved row ${row0 + 1}" }
                    notch(CfwModes.EV_UPROLL, row0 + 2, "a notch after the run")
                }
                ListForm.SELECT_LEVEL -> {
                    oldMark = wireMark1()
                    val staged0 = staged()
                    rec.act.set(true)
                    rec.fire(CfwModes.EV_TAP)                               // the categories' row 0: a Select into Global (Depth)
                    inHalf()
                    check(entriesOf(Arm.RIGHT, 1).isEmpty()) { "${spec.name}: an entry of list 1 held on RIGHT in the in half: ${entriesOf(Arm.RIGHT, 1)}" }
                    val applied0 = r.notes("applied").size
                    val seq = synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).seq }
                    row0 = r.onLoop { r.shell.focusedRowForTest() }
                    rec.fire(CfwModes.EV_UPROLL)
                    check(synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).seq } == seq) { "${spec.name}: the in-half notch took an S on RIGHT (it matched an entry)" }
                    r.until("${spec.name}: the notch applied as today") { r.onLoop { r.shell.focusedRowForTest() } == row0 + 1 }
                    runEnds()
                    check(r.notes("applied").size == applied0) { "${spec.name}: a report of the in-half notch: ${r.notes("applied").drop(applied0)}" }
                    windowBound("Global, after the run")
                    check(staged() > staged0) { "${spec.name}: the new level not staged after the run" }
                    check(wrow() == row0 + 1) { "${spec.name}: Global bound at row ${wrow()}, not the moved row ${row0 + 1}" }
                    notch(CfwModes.EV_UPROLL, row0 + 2, "a notch on Global after the run")
                }
                ListForm.COUNT -> {
                    oldMark = wireMark1()
                    rec.act.set(true)
                    lw!!.n = 7
                    r.onLoop { r.shell.services.requestRender(lw) }
                    r.until("${spec.name}: a new staging of 7 rows", 60_000) { r.onLoop { r.shell.windowBoundForTest().first?.let { it.count == 7 } == true } }
                    windowBound("7 rows")
                    check(r.notes("liststage").any { it.startsWith("dropped — the window list's rows changed (6 staged, 7 now)") }) { "${spec.name}: ${r.notes("liststage").takeLast(3)}" }
                    notch(CfwModes.EV_UPROLL, 1, "a notch on the new staging")
                }
                ListForm.MAIN_OVER -> {
                    oldMark = wireMark1()
                    rec.act.set(true)
                    open("settings")
                    r.until("${spec.name}: Settings staged again, or refused", 60_000) { r.onLoop { r.shell.windowListRefusalsForTest() } > refusals0 ||
                        (r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first != null }) }
                    check(r.onLoop { r.shell.windowListRefusalsForTest() } == refusals0) { "${spec.name}: Settings' list refused after Main's growth: ${r.notes("liststage").takeLast(2)}" }
                    windowBound("Settings again")
                    val ms2 = r.onLoop { r.shell.windowListBinding.staging }!!
                    check(ms2.start >= mainEnd) { "${spec.name}: staged again at ${ms2.start}, not from Main's end $mainEnd" }
                    words += "list 1 staged again at ${ms2.start}..${ms2.end}"
                    notch(CfwModes.EV_UPROLL, 1, "a notch on Settings again")
                }
            }
            lanesDone("the act's end"); idle(r, spec.name)
            rec.act.set(false)
            val recorded = rec.events.toList()
            val firstAct = recorded.indexOfFirst { it.act }
            check(firstAct >= 0 && recorded.drop(firstAct).all { it.act }) { "${spec.name}: an image or input of the session came after the act began (it would be laid in step 0)" }
            val act = rec.rightOfAct()
            val subsOfAct = act.flatten()
            val commits = subsOfAct.filter { isSub(it, 26, CfwModes.MOTION_COMMIT) && it.size == 18 }
            val lists1 = subsOfAct.filter { isListOf(it, 1) }
            // the act's wire forms, as RIGHT was handed them
            fun cleared35(ss: List<ByteArray>) = (3..5).all { e -> ss.any { isClearOf(it, e) } }
            when (spec.form) {
                ListForm.NOTCHES -> {
                    val rightAll = recorded.filter { it.arm == Arm.RIGHT }.map { subs(it.msg!!) }
                    check(rightAll.any { ss -> ss.any { isSub(it, 27, CfwModes.BIND_LIST_RING) && it[2].toInt() == 1 } }) { "${spec.name}: no LIST-RING naming list 1" }
                }
                ListForm.ADJUST -> {
                    check(act.any { cleared35(it) }) { "${spec.name}: no batch CLEARs entries 3–5" }
                    check(act.any { ss -> ss.any { isSetOf(it, 1) } && ss.any { isDiscard0Of(it, 1) && u16(it, 6) == 3 } }) { "${spec.name}: no return batch of list 1's SETs with the DISCARD naming row 3" }
                }
                ListForm.KEPT -> {
                    check(act.any { ss -> cleared35(ss) } && act.any { ss -> ss.any { isSetOf(it, 0) } && ss.any { isDiscard0Of(it, 0) } }) { "${spec.name}: no change of slot 0 to Main (list 1's CLEARs, Main's SETs and DISCARD)" }
                    check(act.any { ss -> ss.any { isSetOf(it, 1) } && ss.any { isDiscard0Of(it, 1) } }) { "${spec.name}: no return of list 1 (its SETs and the DISCARD naming it)" }
                    check(lists1.all { u16(it, 7) == 0 && (it[11].toInt() and 0xFF) == oldMark }) { "${spec.name}: a new LIST of list 1 (only the riding one, shift 0, mark $oldMark): ${lists1.map { hex(it) }}" }
                }
                ListForm.SELECT_LEVEL, ListForm.COUNT, ListForm.MAIN_OVER -> {
                    if (spec.form != ListForm.MAIN_OVER) check(act.any { cleared35(it) }) { "${spec.name}: no batch CLEARs entries 3–5" }
                    check(lists1.any { (it[11].toInt() and 0xFF) != oldMark && (spec.form != ListForm.COUNT || u16(it, 5) == 7) }) {
                        "${spec.name}: no LIST of list 1 with a new mark (old $oldMark): ${lists1.map { hex(it) }}" }
                }
                ListForm.APP_EFFECT -> {}
            }
            val st = r.t.state.value
            check(st.flagsInForce == 0x7c) { "${spec.name}: flags in force 0x${st.flagsInForce.toString(16)}, not stage 5's 0x7c" }
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val applied = ArrayList<DamageMsg.Applied>()
            val vec = vector(spec.name, recorded, st.cacheSize / 1024, st.flagsInForce, finalCrc, ArrayList(), slots, ANY_TAG, applied) { _, bodies ->
                check(bodies.none { it.outcome == DamageMsg.OUTCOME_REFUSED }) { "${spec.name}: a refusal on RIGHT: ${said(bodies, 0..3)}" }
                check(bodies.filter { it.outcome == DamageMsg.OUTCOME_DREW }.all { (it.folded ?: 0L) == 0L }) { "${spec.name}: a step folded: ${said(bodies, 0..3)}" }
                if (withRun) check(bodies.count { it.slot in 2..3 && it.outcome == DamageMsg.OUTCOME_DREW && ((it.tag ?: 0) and 0xE000) == 0xC000 } == 2) {
                    "${spec.name}: not both halves of the Select whole on RIGHT: ${said(bodies, 0..3)}" }
                words += "RIGHT's 114 bodies of the act: ${said(bodies, 0..3)}"
            }
            // RIGHT's 115s of the act, as the model laid out answers them
            fun bad(what: String): Nothing = error("${spec.name}: $what — ${applied.map { it.describe() }}")
            val scrolls = applied.filter { !it.isTap && it.list == 1 }
            // KEPT: Main's walk to Settings' row (a wrap notch up on list 0) is the act's one report on another list
            val others = applied.filter { !it.isTap && it.list != 1 }
            if (spec.form != ListForm.KEPT && others.isNotEmpty()) bad("a scroll report not on list 1")
            if (spec.form == ListForm.KEPT && (others.size != 1 || others[0].list != 0 || others[0].outcome != DamageMsg.APPLIED_DREW || others[0].field18 != 1L))
                bad("not Main's one wrap notch on list 0 beside list 1's reports")
            fun drew(cursors: List<Int>, f18: List<Long?> = cursors.map { null }) {
                if (scrolls.size != cursors.size || scrolls.any { it.outcome != DamageMsg.APPLIED_DREW }) bad("not ${cursors.size} scroll fire(s) that drew")
                if (scrolls.map { it.cursor } != cursors) bad("the cursors are not $cursors")
                if (scrolls.map { it.field18 } != f18) bad("field 18 is not $f18")
            }
            when (spec.form) {
                ListForm.NOTCHES -> drew(listOf(1, 0, count - 1, 0), listOf(null, null, 1L, 1L))
                ListForm.APP_EFFECT -> drew(listOf(1, 0))
                ListForm.ADJUST -> drew(listOf(1, 2, 3, 4))
                ListForm.SELECT_LEVEL -> drew(listOf(row0 + 2))
                ListForm.COUNT, ListForm.MAIN_OVER -> drew(listOf(1))
                ListForm.KEPT -> {
                    if (scrolls.size != 2) bad("not two scroll reports")
                    val (a, b) = scrolls
                    // §7.9 reason 19: a fire whose close set holds an owning instance (the in half's)
                    if (a.outcome != DamageMsg.APPLIED_REFUSED || a.reason != 19) bad("the notch in the in half is not refused 19")
                    if (b.outcome != DamageMsg.APPLIED_DREW || b.cursor != row0 + 2 || b.field18 != null) bad("the notch after the run did not draw at row ${row0 + 2}")
                }
            }
            // the phone's answer to each fire that drew: the COMMIT of the report's own parameters at the absolute cursor
            for (a in (scrolls + others).filter { it.outcome == DamageMsg.APPLIED_DREW }) {
                val c = commits.firstOrNull { u16(it, 3) == a.s } ?: bad("no COMMIT of S ${a.s}")
                if (u16(c, 6) != a.cursor || (0..3).any { s16(c, 10 + 2 * it) != a.params!![it] }) bad("the COMMIT of S ${a.s} is not the report's parameters at its cursor: ${hex(c)}")
            }
            words += "RIGHT's 115s: ${applied.joinToString(" | ") { it.describe().substringBefore(" uptime") }}"
            val staging = r.onLoop { r.shell.windowListBinding.staging?.let { s -> "${s.motion?.effect?.word} T ${s.t} P ${s.period} at ${s.start}..${s.end}" } }
            val answers = act.filter { ss -> ss.any { answerList(it) >= 0 } }.map { shape(it) }.distinct()
            return Captured(spec, vec, listOf("list 1 ${spec.form.name.lowercase()} at ${spec.height}, stage 5 (flags 0x${st.flagsInForce.toString(16)}): its staging $staging; " +
                "${commits.size} COMMIT(s); LISTs of list 1 in the act ${lists1.map { hex(it) }}; the act's answer batches ${answers.map { "[$it]" }}") + words)
        } finally { r.close() }
    }

    /** C3 DW-3: a wheel act ([WheelSpec]) as the shell plays it, checked as it goes, its wire forms asserted on its own
     *  vector's [wheelForms] (decisions DQ (2)), and its vector. */
    private suspend fun captureWheel(spec: WheelSpec): Captured {
        fun base(s: wm.damage.core.shell.ShellSettings) = s.copy(heightMode = spec.height, cachedText = "on", taps = Taps.REPORT, depth = spec.depth)
            .withTier(Transitions.Tier.SELECT, effects = emptyList()).withTier(Transitions.Tier.BACK, effects = emptyList())
            .withTier(Transitions.Tier.LOCK, effects = emptyList())
        val r = TransitionShellTest.Rig(stage = 5, kib256 = true, nRows = MAIN_WINDOWS, text = FakeText(), startSettings = base(wm.damage.core.shell.ShellSettings()))
        try {
            // the slots whose instances place the act's events: the lists' (0 — Main's, the window list's and the wheel's — and 1)
            val rec = Recorder(r, listOf(0, 1))
            val words = ArrayList<String>()
            fun wrow(): Int = r.onLoop { r.shell.wheelBoundForTest().second }
            fun wlist(): wm.damage.core.local.BoundList? = r.onLoop { r.shell.wheelBoundForTest().first }
            fun entriesOf(a: Arm, list: Int) = synchronized(r.sim) { r.sim.bindForTest(a).entries.withIndex().filter { it.value?.list == list }.map { it.index } }
            fun open() = r.onLoop { r.shell.switcherIsOpen }
            fun front(): String? = r.onLoop { r.shell.currentWindowId() }
            fun state(): String = r.onLoop { "front ${r.shell.currentWindowId()}, open ${r.shell.switcherIsOpen}, slot 0 ${r.shell.slot0ForTest()}, " +
                "the wheel ${r.shell.wheelBoundForTest().let { (l, row) -> "count ${l?.count} row $row" }}, a burst open ${r.shell.localBurstOpenForTest()}, " +
                "the item table ${r.shell.wheelBlockForTest()?.let { b -> b.order.map { b.cells[it].entry.name } }}" } + "; the wheel notes ${r.notes("wheel").takeLast(2)}"
            /** [cond] within [maxMs], or the capture ends with the shell's state beside the wait's own report. */
            suspend fun waitOn(what: String, maxMs: Long = 30_000, cond: () -> Boolean) {
                try { r.until(what, maxMs, cond) } catch (e: AssertionError) { throw AssertionError("$what — ${state()} — ${e.message}") }
            }
            suspend fun lanesDone(what: String) {
                r.until("${spec.name}: $what — every lane done", 60_000) { r.shell.isQuiescent() && r.onLoop { r.shell.noticeLaneDoneForTest() &&
                    r.shell.wheelLaneDoneForTest() && r.shell.windowListLaneDoneForTest() && !r.shell.cacheWorkInFlight && r.shell.transitionPhaseForTest() == null } }
                r.settle("${spec.name}: $what")
            }
            suspend fun visit(id: String) {
                r.onLoop { r.shell.services.openWindow(id, null) }
                r.until("${spec.name}: $id in front") { front() == id }
                lanesDone("$id visited")
            }
            suspend fun mainBound(what: String) {
                r.until("${spec.name}: $what — Main's list bound on RIGHT", 60_000) { r.onLoop { r.shell.currentWindowId() == null &&
                    r.shell.slot0ForTest() == "main" && r.shell.mainBinding.counted } && entriesOf(Arm.RIGHT, 0).size >= 2 }
                lanesDone(what)
            }
            suspend fun toMain() {
                for (k in 0 until 4) { if (front() == null) break; r.doubleTap(); r.until("${spec.name}: a double tap toward Main") { r.shell.isQuiescent() }; lanesDone("a double tap toward Main") }
                mainBound("back on Main")
            }
            suspend fun windowBound(what: String) {
                r.until("${spec.name}: $what — the window list bound on RIGHT", 60_000) {
                    r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first != null } &&
                        synchronized(r.sim) { r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().count { it.list == 1 && !it.tap } == 2 } }
                lanesDone(what)
            }
            /** The chord (`WheelBindTest`'s): the ring's long press, its release, the double click. */
            fun chord() { for (g in listOf(EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)) r.shell.postGesture(g) }
            /** The wheel bound: slot 0 the wheel's, both lenses holding list 2's entries 0 (the tap), 6 and 7; then settled. */
            suspend fun wheelBound(what: String) {
                r.until("${spec.name}: $what — the wheel bound on both lenses", 60_000) { open() && r.onLoop { r.shell.slot0ForTest() == "wheel" &&
                    r.shell.wheelBoundForTest().first != null } && Arm.entries.all { a -> entriesOf(a, 2).containsAll(listOf(0, 6, 7)) } }
                r.settle("${spec.name}: $what")
            }
            fun drawing(a: Arm, s: Int) = r.sim.motionForTest(a).slots[s]?.drawing == true
            /** One notch on the wheel ended: its burst closed (the lone SETTLE sent) and RIGHT handed a SETTLE since [from],
             *  neither lens draws in slot 0 or 1, the cursor is [to]. */
            suspend fun ended(from: Int, to: Int, what: String) = r.until("${spec.name}: $what — its SETTLE on RIGHT, the engine idle, row $to") {
                rec.rightFrom(from).any { ss -> ss.any { isSub(it, 26, CfwModes.MOTION_SETTLE) } } && !r.onLoop { r.shell.localBurstOpenForTest() } &&
                    synchronized(r.sim) { Arm.entries.none { a -> drawing(a, 0) || drawing(a, 1) } } && wrow() == to && r.shell.isQuiescent()
            }
            suspend fun notch(eventId: Int, to: Int, what: String) { val from = rec.events.size; rec.fire(eventId); ended(from, to, what); r.settle("${spec.name}: $what") }

            // ---- the session
            r.upAt(spec.height) { base(it) }
            ready(r, spec.name)
            mainBound("up")
            val visits = if (spec.form == WheelForm.YIELD_OPEN || spec.form == WheelForm.COMMIT_WINDOW) listOf("w0", "w1") else listOf("w0", "w1", "w2")
            for (id in visits) visit(id)
            when (spec.form) {
                WheelForm.YIELD_OPEN -> {
                    visit("settings"); windowBound("Settings")
                    rec.fire(CfwModes.EV_TAP)                               // the categories' row 0: Global (no transition)
                    r.until("${spec.name}: Global's rows bound") { r.onLoop { r.shell.slot0ForTest() == "window" && r.shell.windowBoundForTest().first?.count?.let { it > 5 } == true } }
                    lanesDone("Global"); windowBound("Global")
                }
                // Settings opened from Main (its root returns to its caller), so it is the most recent window: the open's cursor
                WheelForm.COMMIT_WINDOW -> { toMain(); visit("settings"); windowBound("Settings"); toMain(); chord(); wheelBound("the session's open") }
                WheelForm.CANCEL -> { toMain(); chord(); wheelBound("the session's open") }
                WheelForm.OPEN_NOTCHES, WheelForm.LATE_MAIN -> toMain()
            }
            idle(r, spec.name)
            // a burst the session's last report opened is closed by its lone SETTLE before the act (the act's batches then
            // carry the idle SETTLE, P7)
            val burstAtIdle = r.onLoop { r.shell.localBurstOpenForTest() }
            r.until("${spec.name}: no burst open") { !r.onLoop { r.shell.localBurstOpenForTest() } }
            idle(r, spec.name)
            words += "a burst open when the session went idle: $burstAtIdle (waited closed)"
            val notes0 = r.notes("wheel").size

            // ---- the act
            var n = 0
            var row0 = 0
            when (spec.form) {
                WheelForm.OPEN_NOTCHES -> {
                    rec.act.set(true)
                    chord(); wheelBound("the open over Main")
                    val l = wlist() ?: error("${spec.name}: the wheel not offered")
                    n = l.count
                    check(l.list == 2 && l.slot == 0 && l.ring && n == 4) { "${spec.name}: not a ring list 2 of 4 in slot 0 (list ${l.list}, slot ${l.slot}, ring ${l.ring}, count $n)" }
                    check(wrow() == 1) { "${spec.name}: the open's cursor ${wrow()}, not 1 (the most recent window)" }
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up")
                    notch(CfwModes.EV_DOWNROLL, n - 1, "a wrap notch up from row 0")
                    notch(CfwModes.EV_UPROLL, 0, "a wrap notch down from the last row")
                    notch(CfwModes.EV_UPROLL, 1, "a notch down")
                }
                WheelForm.YIELD_OPEN -> {
                    val ms1 = r.onLoop { r.shell.windowListBinding.staging } ?: error("${spec.name}: no staging of Global's list")
                    val base0 = r.onLoop { r.shell.wheelBlockForTest() }?.base ?: error("${spec.name}: no block")
                    check(ms1.end == base0) { "${spec.name}: Global's list at ${ms1.start}..${ms1.end}, not flush below the block at $base0" }
                    rec.act.set(true)
                    r.onLoop { r.shell.register(ListWin("w9", "W9", 6)) }  // one more cell: the block placed again, larger
                    r.until("${spec.name}: the block placed again") { r.onLoop { r.shell.wheelBlockForTest()?.base?.let { it < base0 } == true } }
                    lanesDone("the block again"); windowBound("Global again")
                    val b1 = r.onLoop { r.shell.wheelBlockForTest() }!!
                    val ms2 = r.onLoop { r.shell.windowListBinding.staging }!!
                    check(ms2 !== ms1 && ms2.end <= b1.base) { "${spec.name}: not staged again below the block: ${ms2.start}..${ms2.end}, the block at ${b1.base}" }
                    check(r.notes("liststage").any { it.startsWith("dropped — the wheel's block goes at") }) { "${spec.name}: no yield: ${r.notes("liststage").takeLast(3)}" }
                    words += "the yield: Global's list ${ms1.start}..${ms1.end} under the block at $base0; the block again at ${b1.base}..${b1.end}, the list again at ${ms2.start}..${ms2.end}"
                    chord(); wheelBound("the open over Global")
                    n = wlist()!!.count
                }
                WheelForm.LATE_MAIN -> {
                    rec.act.set(true)
                    r.onLoop { r.shell.services.openWindow("w0", null); chord() }   // a page change and the chord before any idle pump
                    waitOn("${spec.name}: w0 in front, the switcher open") { front() == "w0" && open() }
                    wheelBound("bound mid-open")
                    n = wlist()!!.count
                    check(wrow() == 2) { "${spec.name}: the open's cursor ${wrow()}, not 2 (the most recent inactive window)" }
                    notch(CfwModes.EV_DOWNROLL, 1, "a notch up")
                    notch(CfwModes.EV_DOWNROLL, 0, "a notch up to Main")
                    idle(r, spec.name)
                    rec.fire(CfwModes.EV_TAP)                               // the report-only tap: the phone commits
                    waitOn("${spec.name}: the commit into Main") { !open() && front() == null }
                    mainBound("Main after the commit")
                }
                WheelForm.COMMIT_WINDOW -> {
                    n = wlist()!!.count
                    val atCursor = r.onLoop { r.shell.wheelBlockForTest()?.let { b -> b.cells[b.order[r.shell.wheelBoundForTest().second]].entry.name } }
                    check(wrow() == 1 && atCursor == "Settings" && r.onLoop { r.shell.windowListBinding.staging } != null) {
                        "${spec.name}: the cursor ${wrow()} on $atCursor (want Settings at 1), or Settings' staging not kept — ${state()}" }
                    val ms1 = r.onLoop { r.shell.windowListBinding.staging }
                    row0 = r.onLoop { r.shell.windowBoundForTest().second }
                    rec.act.set(true)
                    rec.fire(CfwModes.EV_TAP)
                    waitOn("${spec.name}: the commit into Settings") { !open() && front() == "settings" }
                    windowBound("Settings after the commit")
                    check(r.onLoop { r.shell.windowListBinding.staging } === ms1) { "${spec.name}: Settings' staging not held through the open and the commit" }
                }
                WheelForm.CANCEL -> {
                    n = wlist()!!.count
                    row0 = wrow()
                    rec.act.set(true)
                    // in one loop turn: the cancel posted first, then a notch on the ring — the lenses draw it on list 2
                    // before the loop takes the cancel, and its report reaches the loop after the cancel
                    r.onLoop { r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); rec.fire(CfwModes.EV_UPROLL) }
                    waitOn("${spec.name}: the wheel closed") { !open() }
                    mainBound("Main after the cancel")
                }
            }
            if (spec.form == WheelForm.OPEN_NOTCHES || spec.form == WheelForm.YIELD_OPEN) r.settle("${spec.name}: the act's end, the wheel open")
            else lanesDone("the act's end")
            idle(r, spec.name)
            rec.act.set(false)
            val recorded = rec.events.toList()
            val firstAct = recorded.indexOfFirst { it.act }
            check(firstAct >= 0 && recorded.drop(firstAct).all { it.act }) { "${spec.name}: an image or input of the session came after the act began (it would be laid in step 0)" }
            val act = rec.rightOfAct()
            val commits = act.flatten().filter { isSub(it, 26, CfwModes.MOTION_COMMIT) && it.size == 18 }
            val st = r.t.state.value
            check(st.flagsInForce == 0x7c) { "${spec.name}: flags in force 0x${st.flagsInForce.toString(16)}, not stage 5's 0x7c" }
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val applied = ArrayList<DamageMsg.Applied>()
            val vec = vector(spec.name, recorded, st.cacheSize / 1024, st.flagsInForce, finalCrc, ArrayList(), listOf(0, 1), ANY_TAG, applied) { _, bodies ->
                words += "RIGHT's 114 bodies of the act: ${said(bodies, 0..3)}"
                if (spec.form != WheelForm.CANCEL) check(bodies.none { it.outcome == DamageMsg.OUTCOME_REFUSED }) { "${spec.name}: a refusal on RIGHT: ${said(bodies, 0..3)}" }
                check(bodies.filter { it.outcome == DamageMsg.OUTCOME_DREW }.all { (it.folded ?: 0L) == 0L }) { "${spec.name}: a step folded: ${said(bodies, 0..3)}" }
            }
            // RIGHT's 115s of the act, as the model laid out answers them
            fun bad(what: String): Nothing = error("${spec.name}: $what — ${applied.map { it.describe() }}")
            val scrolls = applied.filter { !it.isTap }
            val taps = applied.filter { it.isTap }
            if (scrolls.any { it.list != 2 }) bad("a scroll report not on list 2")
            fun drew(cursors: List<Int>, f18: List<Long?> = cursors.map { null }) {
                if (scrolls.size != cursors.size || scrolls.any { it.outcome != DamageMsg.APPLIED_DREW }) bad("not ${cursors.size} scroll fire(s) that drew")
                if (scrolls.map { it.cursor } != cursors) bad("the cursors are not $cursors")
                if (scrolls.map { it.field18 } != f18) bad("field 18 is not $f18")
            }
            when (spec.form) {
                WheelForm.OPEN_NOTCHES -> drew(listOf(0, n - 1, 0, 1), listOf(null, 1L, 1L, null))
                WheelForm.YIELD_OPEN -> drew(emptyList())
                WheelForm.LATE_MAIN -> drew(listOf(1, 0))
                WheelForm.COMMIT_WINDOW -> drew(emptyList())
                WheelForm.CANCEL -> drew(listOf((row0 + 1).mod(n)), listOf(if (row0 + 1 >= n) 1L else null))
            }
            val wantTaps = if (spec.form == WheelForm.LATE_MAIN || spec.form == WheelForm.COMMIT_WINDOW) 1 else 0
            if (taps.size != wantTaps || taps.any { it.list != 2 || it.outcome != DamageMsg.APPLIED_DREW || it.params != null }) bad("not $wantTaps report-only tap(s) on list 2")
            // the phone's answer to each fire that drew while the wheel was in front: the COMMIT of the report's own
            // parameters at the absolute cursor; the cancel's notch, reported after the close, is committed by nobody
            for (a in scrolls.filter { it.outcome == DamageMsg.APPLIED_DREW }) {
                val c = commits.firstOrNull { u16(it, 3) == a.s }
                if (spec.form == WheelForm.CANCEL) { if (c != null) bad("the notch reported after the close was committed: ${hex(c)}"); continue }
                if (c == null) bad("no COMMIT of S ${a.s}")
                if (u16(c, 6) != a.cursor || (0..3).any { s16(c, 10 + 2 * it) != a.params!![it] }) bad("the COMMIT of S ${a.s} is not the report's parameters at its cursor: ${hex(c)}")
            }
            // decisions DQ (2): each wire form the act carries, read on the projection the re-play row compares
            val forms = wheelForms(vec)
            fun formBytes(prefix: String) = forms.filter { it.startsWith("$prefix ") }.map { bytes(it.substringAfter(' ').substringBefore("··")) }
            /** RIGHT's bind and slot messages of the act, batch by batch (C entry, S entry:list, L list, D list cursor, T list, X slot, M cursor). */
            fun trace(): String = act.mapNotNull { ss -> ss.mapNotNull { m -> when {
                isSub(m, 27, CfwModes.BIND_CLEAR) -> "C${m[2].toInt() and 0xFF}"
                isSub(m, 27, CfwModes.BIND_SET) -> "S${m[2].toInt() and 0xFF}:${m[5].toInt() and 0xFF}"
                isSub(m, 27, CfwModes.BIND_LIST) || isSub(m, 27, CfwModes.BIND_LIST_RING) -> "L${m[2].toInt() and 0xFF}"
                isSub(m, 26, CfwModes.MOTION_DISCARD) -> "D${m[5].toInt() and 0xFF}c${u16(m, 6)}"
                isSub(m, 26, CfwModes.MOTION_SETTLE) -> "T${m[5].toInt() and 0xFF}"
                isSub(m, 26, CfwModes.MOTION_CLOSE) -> "X${m[2].toInt() and 0xFF}"
                isSub(m, 26, CfwModes.MOTION_COMMIT) -> "M${u16(m, 6)}"
                else -> null } }.takeIf { it.isNotEmpty() }?.joinToString(" ") }.joinToString(" | ")
            fun has(what: String, ok: Boolean) { if (!ok) error("${spec.name}: the act's projection holds no $what — ${forms.filter { !it.startsWith("rec @") && !it.startsWith("table @") }}; RIGHT's act batches: ${trace()}") }
            fun cleared(vararg e: Int) = e.all { x -> formBytes("CLEAR").any { (it[2].toInt() and 0xFF) == x } }
            fun setsOf(list: Int) = formBytes("SET@act").filter { it[5].toInt() == list }.map { it[2].toInt() and 0xFF }.toSet()
            fun listRing2() = formBytes("LIST").any { it[1].toInt() == CfwModes.BIND_LIST_RING && it[2].toInt() == 2 }
            fun listShift0(list: Int) = formBytes("LIST").any { it[2].toInt() == list && u16(it, 7) == 0 }
            fun discard0(list: Int) = forms.any { it.startsWith("DISCARD slot 0 list $list ") }
            fun settled(list: Int) = forms.any { it.startsWith("SETTLE slot ") && it.endsWith(" list $list") }
            fun theOpen(clears: IntArray) {
                has("CLEAR of ${clears.toList()} (the list in front)", cleared(*clears))
                has("LIST-RING of list 2", listRing2())
                has("SET of entries 6, 7 and the tap 0 on list 2", setsOf(2) == setOf(0, 6, 7))
                has("DISCARD in slot 0 naming list 2", discard0(2))
                has("SETTLE naming list 2", settled(2))
            }
            fun theClose(list: Int) {
                has("CLEAR of 6, 7 and 0 (the wheel's entries)", cleared(6, 7, 0))
                has("SET of list $list (the arriving list)", setsOf(list).isNotEmpty())
                has("LIST of list $list with shift 0", listShift0(list))
                has("DISCARD in slot 0 naming list $list", discard0(list))
            }
            when (spec.form) {
                WheelForm.OPEN_NOTCHES -> theOpen(intArrayOf(0, 1, 2))
                WheelForm.YIELD_OPEN -> { has("CLEAR of 3, 4 and 5 (the yield, the open)", cleared(3, 4, 5)); has("SET of list 1 (staged again)", setsOf(1).isNotEmpty()); theOpen(intArrayOf(3, 4, 5)) }
                WheelForm.LATE_MAIN -> { has("SETTLE naming 0xFF (the open not offered)", settled(0xFF)); theOpen(intArrayOf()); theClose(0) }
                WheelForm.COMMIT_WINDOW -> theClose(1)
                WheelForm.CANCEL -> {
                    theClose(0)
                    // the report after the close is not the list in front: the slot's DISCARD names the list it came from
                    // while its row is known, else none (`LocalRules.tail`, step 3)
                    has("DISCARD in slot 0 naming list 2 or none (the report after the close)", discard0(2) || discard0(0xFF))
                }
            }
            words += "RIGHT's 115s: ${applied.joinToString(" | ") { it.describe().substringBefore(" uptime") }}"
            words += "the wheel notes of the act: ${r.notes("wheel").drop(notes0)}"
            words += "the act's wire forms: ${forms.filter { !it.startsWith("rec @") && !it.startsWith("table @") && !it.startsWith("SET ") }}"
            words += "RIGHT's act batches: ${trace()}"
            val answers = act.filter { ss -> ss.any { isSub(it, 27, CfwModes.BIND_SET) || isSub(it, 27, CfwModes.BIND_CLEAR) || isSub(it, 26, CfwModes.MOTION_DISCARD) } }.map { shape(it) }.distinct()
            val b = r.onLoop { r.shell.wheelBlockForTest() }
            return Captured(spec, vec, listOf("wheel ${spec.form.name.lowercase()} at ${spec.height}, Depth ${spec.depth}, stage 5 (flags 0x${st.flagsInForce.toString(16)}): " +
                "n $n; the block ${b?.describe()}; geo ${b?.geo}; ${commits.size} COMMIT(s); the act's bind batches ${answers.map { "[$it]" }}") + words)
        } finally { r.close() }
    }

    /**
     * SV-083: the program bytes of a scroll or notice vector, each once, in the order first named — the session's table
     * (each entry's last SET in step 0) as it stands when the act begins, then every SET, PLAY and PLAY-AFTER of the act;
     * beside each, the record it names (and every easing table its segments name: `FIRMWARE.md` §5.4), as the vector's cache
     * writes left it at that message; a COMMIT's record too. What the session sent before its last table (an earlier
     * staging, a repair) and where an image lands follow the run and are not compared.
     */
    fun namedPrograms(vec: JsonObject): List<String> {
        val cache = ByteArray(256 * 1024)
        val out = LinkedHashSet<String>()
        fun named(prog4: Int) {
            val o = prog4 * 4
            if (prog4 == 0xFFFF || o + 4 > cache.size) return
            val version = cache[o].toInt() and 0xFF
            val len = u16(cache, o + 2)
            if (version !in 1..2 || len < 4 || o + len > cache.size) { out += "rec @$prog4 none (${hex(cache.copyOfRange(o, minOf(cache.size, o + 8)))})"; return }
            out += "rec @$prog4 ${hex(cache.copyOfRange(o, o + len))}"
            var p = o + (if (version == 2) 8 else 4)
            repeat(cache[o + 1].toInt() and 0xFF) {
                if (p + 4 > o + len) return
                val segs = cache[p].toInt() and 0xFF; val perLens = cache[p + 2].toInt() == 1
                p += 4 + (if (perLens) 16 else 8)
                repeat(segs) {
                    if (cache[p + 2].toInt() == 2) {           // ease 2: the table the segment names
                        val t4 = u16(cache, p + 22); val n = cache[t4 * 4].toInt() and 0xFF
                        out += "table @$t4 ${hex(cache.copyOfRange(t4 * 4, t4 * 4 + 2 + 2 * n))}"
                    }
                    p += if (version == 2) 40 else 32
                }
            }
        }
        val table = java.util.TreeMap<Int, ByteArray>()
        for ((si, st) in vec["steps"]!!.jsonArray.withIndex()) {
            if (si == 1) for ((_, s) in table) { out += "SET ${hex(s)}"; named(u16(s, 9)) }
            for (o in st.jsonObject["ops"]!!.jsonArray) {
                val m = bytes(o.jsonObject["msg"]?.jsonPrimitive?.content ?: continue)
                for (s in subs(m)) {
                    val mode = s[0].toInt() and 0x7F
                    if (mode == 19 || mode == 25) { for ((off, d) in cacheWrites(s)) d.copyInto(cache, off); continue }
                    if (si == 0) { if (isSub(s, 27, CfwModes.BIND_SET) && s.size == 23) table[s[2].toInt() and 0xFF] = s; continue }
                    when {
                        isSub(s, 27, CfwModes.BIND_SET) && s.size == 23 -> { out += "SET ${hex(s)}"; named(u16(s, 9)) }
                        isSub(s, 26, CfwModes.MOTION_PLAY) && s.size == 18 -> { out += "PLAY ${hex(s)}"; named(u16(s, 4)) }
                        isSub(s, 26, CfwModes.MOTION_PLAY_AFTER) && s.size == 17 -> { out += "PLAY-AFTER ${hex(s)}"; named(u16(s, 4)) }
                        isSub(s, 26, CfwModes.MOTION_COMMIT) && s.size == 18 -> named(u16(s, 8))
                    }
                }
            }
        }
        return out.toList()
    }

    /**
     * C3 SVR: a ring act's projection for the re-play row — [namedPrograms], then the act's ring form: every LIST the act
     * sends (its sub, list, items, count, shift, pitch and flags; the mark, which follows how many stagings the session
     * made, left out) and every COMMIT (the report's own parameters at the absolute cursor), each once, in the order first
     * sent. A LIST-RING sent as a plain LIST, or a COMMIT at another row or other parameters, changes it.
     */
    fun ringForms(vec: JsonObject): List<String> {
        val out = LinkedHashSet(namedPrograms(vec))
        for (st in vec["steps"]!!.jsonArray.drop(1)) for (o in st.jsonObject["ops"]!!.jsonArray) {
            val m = bytes(o.jsonObject["msg"]?.jsonPrimitive?.content ?: continue)
            for (s in subs(m)) when {
                (isSub(s, 27, CfwModes.BIND_LIST) || isSub(s, 27, CfwModes.BIND_LIST_RING)) && s.size >= 12 ->
                    out += "LIST " + hex(s.copyOfRange(0, 11)) + "··" + hex(s.copyOfRange(12, s.size))
                isSub(s, 26, CfwModes.MOTION_COMMIT) -> out += "COMMIT ${hex(s)}"
            }
        }
        return out.toList()
    }

    /**
     * C3 DM-2bF (decisions FA 4): a records act's projection — [ringForms], then the whole of what the act writes into
     * the cache: every write of every step applied in order, and each maximal span written given whole as the writes
     * left it ("CACHE @from..to" and its bytes). An effect's records (Matrix's rain, Scramble's cells and private fonts)
     * are no program the other forms name, so a change of them alone moves this.
     */
    fun recordsForms(vec: JsonObject): List<String> {
        val out = LinkedHashSet(ringForms(vec))
        val cache = ByteArray(256 * 1024); val written = BooleanArray(cache.size)
        for (st in vec["steps"]!!.jsonArray) for (o in st.jsonObject["ops"]!!.jsonArray) {
            val m = bytes(o.jsonObject["msg"]?.jsonPrimitive?.content ?: continue)
            for (s in subs(m)) if ((s[0].toInt() and 0x7F).let { it == 19 || it == 25 })
                for ((off, d) in cacheWrites(s)) { d.copyInto(cache, off); for (i in off until off + d.size) written[i] = true }
        }
        var i = 0
        while (i < cache.size) {
            if (!written[i]) { i++; continue }
            var j = i; while (j < cache.size && written[j]) j++
            out += "CACHE @$i..$j ${hex(cache.copyOfRange(i, j))}"
            i = j
        }
        return out.toList()
    }

    /**
     * C3 DW-3 (decisions DQ (2)): a wheel act's wire forms — [ringForms], then every bind and slot message of the act's steps,
     * each once in the order first sent: each SET whole (`SET@act`, the act's own, which [namedPrograms]' session table
     * does not tell apart), each CLEAR whole, each DISCARD's slot, list and cursor, each SETTLE's slot and list and each
     * CLOSE's slot (a DISCARD's and a SETTLE's S, a SETTLE's cursor and age follow the run's timing and are left out). The
     * open's batch, the yield's CLEARs, the close's batch and a late report's DISCARD each change it.
     */
    fun wheelForms(vec: JsonObject): List<String> {
        val out = LinkedHashSet(ringForms(vec))
        for (st in vec["steps"]!!.jsonArray.drop(1)) for (o in st.jsonObject["ops"]!!.jsonArray) {
            val m = bytes(o.jsonObject["msg"]?.jsonPrimitive?.content ?: continue)
            for (s in subs(m)) when {
                isSub(s, 27, CfwModes.BIND_SET) && s.size == 23 -> out += "SET@act ${hex(s)}"
                isSub(s, 27, CfwModes.BIND_CLEAR) -> out += "CLEAR ${hex(s)}"
                isSub(s, 26, CfwModes.MOTION_DISCARD) && s.size == 8 -> out += "DISCARD slot ${s[2].toInt() and 0xFF} list ${s[5].toInt() and 0xFF} cursor ${u16(s, 6)}"
                isSub(s, 26, CfwModes.MOTION_SETTLE) && s.size == 10 -> out += "SETTLE slot ${s[2].toInt() and 0xFF} list ${s[5].toInt() and 0xFF}"
                isSub(s, 26, CfwModes.MOTION_CLOSE) && s.size >= 3 -> out += "CLOSE slot ${s[2].toInt() and 0xFF}"
            }
        }
        return out.toList()
    }

    /** The bytes of the version-1 and version-2 program records [namedPrograms] holds (for the report). */
    fun namedProgramBytes(vec: JsonObject): Int = namedPrograms(vec).filter { it.startsWith("rec @") && !it.contains(" none ") }
        .sumOf { bytes(it.substringAfterLast(' ')).size }

    // ------------------------------------------------------------------------------------------------- the vector
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun op(k: String, v: Any): JsonObject = JsonObject(mapOf(k to when (v) {
        is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
        else -> JsonPrimitive(v as Number) }))

    private fun newSim() = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = FEATURES; it.clockIsOsTick = true }

    /**
     * The vector of one act from the rig's [events]: each lens's images (the same, checked), the inputs, the act's
     * events placed at their recorded progress. Run through the model as it is laid out — the plan's waits place the
     * wakes — and checked there: every act message taken on both lenses, the act's halves as the shell's notes say,
     * the final shadows the rig's ([finalCrc]). [halves] receives the model's C of each transition instance RIGHT opens
     * in slots 2 and 3 during the act (C3 X3T: the report's budget figure, `FIRMWARE.md` §7.12).
     */
    private fun vector(spec: Spec, events: List<Event>, cacheKib: Int, flags: Int, finalCrc: Map<Arm, Long>,
                       halves: MutableList<String> = ArrayList()): JsonObject =
        vector(spec.name, events, cacheKib, flags, finalCrc, halves) { bodies, _ ->
            val whole = bodies.filter { it.slot in 2..3 && it.outcome == DamageMsg.OUTCOME_DREW && ((it.tag ?: 0) and 0xE000) == 0xC000 }
            val wantWhole = if (spec.form == Form.CUT) 3 else 2     // the cut act's in half is closed by the second act's out PLAY
            check(whole.size == wantWhole) { "${spec.name}: ${whole.size} halves drew their last step on RIGHT, want $wantWhole: ${bodies.map { it.describe() }}" }
            // a wake at every due tick: every step of every half is a present of its own, none folded
            check(whole.all { (it.folded ?: 0L) == 0L && (it.drawn ?: 0L) > 0L }) { "${spec.name}: a half folded a step: ${whole.map { it.describe() }}" }
        }

    /** [vector] for any act (SV-083): its events placed by the progress of the instances in [slots] whose tags [accept]
     *  takes; [endCheck] reads RIGHT's 114 bodies — all of them, and the act's (those after the session's step 0).
     *  C3 SVR: [applied], when given, receives RIGHT's 115 bodies of the act (after the session's step 0), in send order. */
    private fun vector(name: String, events: List<Event>, cacheKib: Int, flags: Int, finalCrc: Map<Arm, Long>,
                       halves: MutableList<String>, slots: List<Int> = TRANSITION_SLOTS, accept: (Int) -> Boolean = TRANSITION_TAG,
                       applied: MutableList<DamageMsg.Applied>? = null,
                       endCheck: (all: List<DamageMsg.Motion>, act: List<DamageMsg.Motion>) -> Unit): JsonObject {
        // C3 B2X: what the model did from the act's start, and the rig's act events as recorded — added to any failure here
        val trace = ArrayList<String>()
        fun evidence(): String = "the rig's act events (the progress recorded; RIGHT's slots as recorded):\n" +
            events.filter { it.act && it.arm != Arm.LEFT }.withIndex().joinToString("\n") { (i, e) -> "  #$i ${eventWords(e)} rec ${e.progress} [${e.at}]" } +
            "\nthe model as laid out, from the act's start:\n" + trace.joinToString("\n") { "  $it" }
        try {
        val left = events.filter { it.arm == Arm.LEFT }; val right = events.filter { it.arm == Arm.RIGHT }
        check(left.size == right.size && left.indices.all { left[it].msg!!.contentEquals(right[it].msg!!) }) {
            "$name: LEFT and RIGHT were handed different images (${left.size} and ${right.size}; first difference at " +
                "${left.indices.firstOrNull { it >= right.size || !left[it].msg!!.contentEquals(right[it].msg!!) }})" }
        // one ordered list: RIGHT's images and the inputs, each at the progress RIGHT's model held when it went
        val seq = events.filter { it.arm != Arm.LEFT }
        val sim = newSim()
        val seen = HashMap<Int, Int>()
        val steps = ArrayList<JsonObject>()
        var now = 1000L
        val bodies = ArrayList<DamageMsg.Motion>()
        val refused = ArrayList<String>()
        fun record(ops: List<JsonObject>) { steps += JsonObject(mapOf("ops" to JsonArray(ops), "expect" to JsonObject(emptyMap()))) }
        val costed = HashSet<Int>()
        /** The model's C of each transition instance newly open on RIGHT (slots 2 and 3), once each. */
        fun costs() { for (s in listOf(2, 3)) { val i = sim.motionForTest(Arm.RIGHT).slots[s] ?: continue
            if (i.tag and 0xE000 != 0xC000 || !costed.add((s shl 16) or i.tag)) continue
            halves += "slot $s tag 0x${i.tag.toString(16)}: C ${i.cost} u (%.1f %% of the cap), T ${i.T}, P ${i.period}".format(i.cost * 100.0 / wm.damage.core.motion.Motion.BUDGET_CAP) } }
        val reports = ArrayList<DamageMsg.Applied>()
        fun notes() { for ((field, b) in sim.conformanceSent(Arm.RIGHT)) when (field) {
                DamageMsg.MOTION_FIELD -> bodies += DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, b))!!
                DamageMsg.APPLIED_FIELD -> reports += DamageMsg.parseApplied(Pb.l(DamageMsg.APPLIED_FIELD, b))!!
            }
            check(sim.conformanceSent(Arm.LEFT).isEmpty()) { "$name: LEFT sent a notify" } }
        fun apply(e: Event, act: Boolean): JsonObject = if (e.msg != null) {
            for (a in Arm.entries) if (!sim.conformanceMessage(a, e.msg, now) && act) refused += "$a refused act image ${hex(e.msg).take(24)}… (${sim.refusalRecord(a)})"
            op("msg", hex(e.msg))
        } else {
            for (a in Arm.entries) sim.conformanceInput(a, e.input!![0].toInt(), e.input[1], e.input[2], now)
            op("input", e.input!!)
        }
        /** A wake at the next due tick of either lens; false when nothing is planned on either. */
        fun wake(): Boolean {
            val w = Arm.entries.map { sim.conformancePlan(it, now) }.filter { it != MotionSim.PLAN_FOREVER }
            if (w.isEmpty()) return false
            now += w.min()
            for (a in Arm.entries) sim.conformanceWake(a, now)
            notes(); val p = progress(sim, Arm.RIGHT, seen, slots, accept)
            trace += "wake at $now: progress $p [${slotWords(sim, Arm.RIGHT, slots)}]"
            record(listOf(op("tick", now), op("wake", true)))
            return true
        }
        // step 0: the session before the act
        val head = arrayListOf(op("tick", now), op("lease", "acquire"))
        for (a in Arm.entries) sim.conformanceLease(a, true, now)
        head += op("cachesize", cacheKib); for (a in Arm.entries) sim.conformanceControl(a, DamageMsg.OP_CACHE_SIZE, cacheKib, now)
        head += op("flags", flags); for (a in Arm.entries) sim.conformanceControl(a, DamageMsg.OP_FLAGS_SET, flags, now)
        head += op("page", true); for (a in Arm.entries) sim.conformancePage(a, true)
        for (e in seq.filter { !it.act }) head += apply(e, act = false)
        notes()
        record(head)
        var guard = 0
        while (wake()) check(++guard < 64) { "$name: the session's engine never went idle" }
        val sessionBodies = bodies.size
        val sessionReports = reports.size
        // the act
        trace.clear(); trace += "the session idle at $now: progress ${progress(sim, Arm.RIGHT, seen, slots, accept)} [${slotWords(sim, Arm.RIGHT, slots)}]"
        var first = true
        for ((i, e) in seq.filter { it.act }.withIndex()) {
            guard = 0
            while (progress(sim, Arm.RIGHT, seen, slots, accept) < e.progress) check(wake() && ++guard < 64) {
                "$name: the model's engine never reached progress ${e.progress} (at ${progress(sim, Arm.RIGHT, seen, slots, accept)})" }
            val ops = ArrayList<JsonObject>()
            if (first) { now = maxOf(now, ACT_TICK); ops += op("tick", now); first = false }
            ops += apply(e, act = true)
            notes(); val p = progress(sim, Arm.RIGHT, seen, slots, accept); costs()
            trace += "#$i ${eventWords(e)} (rec ${e.progress}) applied at $now: progress $p [${slotWords(sim, Arm.RIGHT, slots)}]"
            record(ops)
        }
        guard = 0
        while (wake()) check(++guard < 64) { "$name: the engine never went idle after the act" }
        // checked on the model as laid out
        check(refused.isEmpty()) { "$name: ${refused.joinToString("; ")}" }
        for (a in Arm.entries) check(sim.shadowCrc32(a) == finalCrc[a]) { "$name: $a's shadow at the end is not the rig's" }
        for (a in Arm.entries) check(sim.shadowCrc32(a) == sim.panelCrc32(a)) { "$name: $a's panel is not its shadow at the end" }
        applied?.addAll(reports.drop(sessionReports))
        endCheck(bodies, bodies.drop(sessionBodies))
        // C3 B2X: `DAMAGE_SHIP_TRACE=part` says the evidence of a green capture too (the acts whose names hold `part`)
        if (System.getenv("DAMAGE_SHIP_TRACE")?.let { it.isNotEmpty() && it in name } == true) println("ShipVectors: $name, green:\n${evidence()}")
        return JsonObject(mapOf("name" to JsonPrimitive(name), "steps" to JsonArray(steps), "contract" to JsonPrimitive(2),
            "start" to JsonPrimitive("zero"), "panel" to JsonPrimitive(true), "features" to JsonArray(listOf(JsonPrimitive(FEATURES)))))
        } catch (e: IllegalStateException) { throw IllegalStateException("${e.message}\n${evidence()}", e) }
    }

    // ------------------------------------------------------------------------------------------------- the programs
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** A message's sub-messages (a lone message is one). */
    fun subs(img: ByteArray): List<ByteArray> {
        if (img.isEmpty() || img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) { val n = u16(img, i); out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n }
        return out
    }
    private fun closeOf(s: ByteArray, slot: Int) = s.size >= 3 && s[0].toInt() == 26 && s[1].toInt() == CfwModes.MOTION_CLOSE && (s[2].toInt() and 0xFF) == slot

    /** The cache writes of a mode-19 message, or of a mode-25 one inflated: (offset in bytes, data). */
    fun cacheWrites(m: ByteArray): List<Pair<Int, ByteArray>> {
        val list = when (m[0].toInt() and 0x7F) {
            19 -> m.copyOfRange(1, m.size)
            25 -> Zl.inflate(m.copyOfRange(1, m.size), CfwModes.ZWRITE_MAX)
            else -> return emptyList()
        }
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 4 <= list.size) { val n = u16(list, i + 2); out += u16(list, i) * 4 to list.copyOfRange(i + 4, i + 4 + n); i += 4 + n }
        return out
    }

    /** Whether [d] at [o] starts a version-2 program record (`FIRMWARE.md` §7.2's header, its tracks and 40-B segments
     *  walking to exactly its length). */
    fun isV2Record(d: ByteArray, o: Int): Boolean {
        if (o + 8 > d.size || d[o].toInt() != 2) return false
        val tracks = d[o + 1].toInt() and 0xFF; val len = u16(d, o + 2); val flags = d[o + 4].toInt() and 0xFF
        if (tracks !in 1..8 || flags and 0xFC != 0 || d[o + 5].toInt() != 0 || u16(d, o + 6) != 0 || len < 8 || o + len > d.size) return false
        var p = o + 8
        repeat(tracks) {
            if (p + 12 > o + len) return false
            val segs = d[p].toInt() and 0xFF; val perLens = d[p + 2].toInt() and 0xFF
            if (segs !in 1..8 || perLens > 1 || d[p + 3].toInt() != 0) return false
            p += 4 + (if (perLens == 1) 16 else 8) + segs * 40
        }
        return p == o + len
    }

    /** The program bytes of a vector's act (every step after the session's): each cache write that holds a version-2
     *  program record, as written, and every PLAY and PLAY-AFTER, in order. */
    fun programs(vec: JsonObject): List<String> {
        val out = ArrayList<String>()
        for (st in vec["steps"]!!.jsonArray.drop(1)) for (o in st.jsonObject["ops"]!!.jsonArray) {
            val m = bytes(o.jsonObject["msg"]?.jsonPrimitive?.content ?: continue)
            for (s in subs(m)) {
                val mode = s[0].toInt() and 0x7F
                if (mode == 19 || mode == 25) for ((off, d) in cacheWrites(s)) {
                    if (d.indices.any { (off + it) % 4 == 0 && isV2Record(d, it) }) out += "write @$off ${hex(d)}"
                }
                if (mode == 26 && s.size > 1 && (s[1].toInt() == CfwModes.MOTION_PLAY || s[1].toInt() == CfwModes.MOTION_PLAY_AFTER))
                    out += (if (s[1].toInt() == CfwModes.MOTION_PLAY) "PLAY " else "PLAY-AFTER ") + hex(s)
            }
        }
        return out
    }

    /** The bytes of the act's version-2 program records (the records' write, less its tables): for the report. */
    fun programBytes(vec: JsonObject): Int = programs(vec).filter { it.startsWith("write") }.sumOf { w ->
        val d = bytes(w.substringAfterLast(' ')); var n = 0
        var i = 0
        while (i < d.size) { if (isV2Record(d, i)) { n += u16(d, i + 2); i += u16(d, i + 2) } else i += 4 }
        n
    }

    // ------------------------------------------------------------------------------------------------- writing
    /** [vec] as `make_vectors.py` writes a vector (`json.dumps(vec, indent=1)`), with a newline. */
    fun format(vec: JsonElement): String {
        val sb = StringBuilder()
        fun w(e: JsonElement, ind: Int) {
            when (e) {
                is JsonObject -> if (e.isEmpty()) sb.append("{}") else {
                    sb.append("{\n"); var i = 0
                    for ((k, v) in e) { sb.append(" ".repeat(ind + 1)).append('"').append(k).append("\": "); w(v, ind + 1); if (++i < e.size) sb.append(','); sb.append('\n') }
                    sb.append(" ".repeat(ind)).append('}')
                }
                is JsonArray -> if (e.isEmpty()) sb.append("[]") else {
                    sb.append("[\n")
                    e.forEachIndexed { i, v -> sb.append(" ".repeat(ind + 1)); w(v, ind + 1); if (i < e.size - 1) sb.append(','); sb.append('\n') }
                    sb.append(" ".repeat(ind)).append(']')
                }
                is JsonNull -> sb.append("null")
                is JsonPrimitive -> sb.append(e.toString())
            }
        }
        w(vec, 0)
        return sb.append('\n').toString()
    }

    /** The vector to write at [p]: [vec], with the expectations the file already holds kept for the longest prefix of
     *  steps whose inputs (and every vector-level key) are unchanged — `make_vectors.py`'s `_merged`. */
    fun merged(p: Path, vec: JsonObject): JsonObject {
        if (!Files.exists(p)) return vec
        val old = Json.parseToJsonElement(Files.readString(p)).jsonObject
        if (old.filterKeys { it != "steps" } != vec.filterKeys { it != "steps" }) return vec
        val oldSteps = old["steps"]!!.jsonArray; val newSteps = vec["steps"]!!.jsonArray
        val out = ArrayList<JsonElement>()
        var same = true
        for ((i, s) in newSteps.withIndex()) {
            same = same && i < oldSteps.size && oldSteps[i].jsonObject["ops"] == s.jsonObject["ops"]
            out += if (same) JsonObject(s.jsonObject + ("expect" to (oldSteps[i].jsonObject["expect"] ?: JsonObject(emptyMap())))) else s
        }
        return JsonObject(vec + ("steps" to JsonArray(out)))
    }
}
