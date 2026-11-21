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
import wm.damage.core.motion.PopupPrograms
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.Transitions
import wm.damage.core.shell.MainStaging
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
 */
internal object ShipVectors {
    const val PREFIX = "v5-ship-"
    private const val FEATURES = 0xDFF
    private const val ACT_TICK = 2000L

    enum class Form { WHOLE, CUT, WRITE }

    /** C3 X3T, X4T: the window's labelled rows for a glyph effect ([labelsOf]) — a typical window, a page dense enough for
     *  two cell lists, or (APART) one too dense for the records and both halves' lists in one compressed message, so the
     *  in lists go in a second write under the out half (the shell's not-compressed order). */
    enum class Page { TYPICAL, DENSE, APART }

    /** One act: [tier]'s [effect] at layout height [height], Global Depth [depth]; [form] WHOLE (the act alone), CUT (a
     *  second Select inside the in half) or WRITE (his scroll's frame written under the in half). C3 X3T: Encode and
     *  Matrix ([Transitions.Effect.glyphs]) play on the rig's 256 KiB session with BIND ([kib256]: the session stage 5
     *  has on his glasses, the only one Encode plays on), over the window's labelled rows ([labelsOf], [page]), every
     *  screen's text packed into the atlas before the act. */
    data class Spec(override val name: String, val tier: Transitions.Tier, val effect: Transitions.Effect, val reversed: Boolean,
                    val height: Int, val depth: Int = 8, val form: Form = Form.WHOLE, val page: Page = Page.TYPICAL) : Act {
        val kib256: Boolean get() = effect.glyphs
    }

    /** C3 X3T: the glyph effects whose programs differ between Global Depth 8 and 12 (Encode: each cell track takes its
     *  plane's disparity on each lens; Matrix's rain is one rect on both lenses, its programs the same at both — measured
     *  on the first dump, `C3-X3T-built.md`). */
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
    private class Event(val arm: Arm?, val msg: ByteArray?, val input: LongArray?, val progress: Int, val act: Boolean)

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
            // inside the model's own lock (the observer runs in its dispatch): the progress as the message arrives
            r.onImage = { a, img -> events += Event(a, img.copyOf(), null, progress(r.sim, a, if (a == Arm.LEFT) seenL else seenR), act.get()) }
            // and every millisecond between, so a step drawn with no message after it (the out half's last, before the
            // PLAY-AFTER's deferred empty takes the slot) is counted: each instance holds its d for a period (~45 ms)
            r.scope.launch { while (true) { synchronized(r.sim) { progress(r.sim, Arm.LEFT, seenL); progress(r.sim, Arm.RIGHT, seenR) }; delay(1) } }
            fun fire(eventId: Int) {
                val p = synchronized(r.sim) { progress(r.sim, Arm.RIGHT, seenR) }
                events += Event(null, null, longArrayOf(CfwModes.DEV_RING.toLong(), eventId.toLong(), 0), p, act.get())
                r.fire(eventId)
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
                .withTier(Transitions.Tier.LOCK, effects = if (!glyphs && spec.tier == Transitions.Tier.LOCK && !spec.reversed) listOf(spec.effect.id) else emptyList()) }
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
            val st = r.t.state.value
            val finalCrc = synchronized(r.sim) { Arm.entries.associateWith { r.sim.shadowCrc32(it) } }
            val all = events.toList()
            val halves = ArrayList<String>()
            return Captured(spec, vector(spec, all, st.cacheSize / 1024, st.flagsInForce, finalCrc, halves), owed, halves)
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
        for (h in listOf(352, 480)) for (e in ScrollEffect.entries) out += ScrollSpec(n(e, h), e, h, at(e, h).first, at(e, h).second)
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

    /** Every ship act: the transitions ([specs]), then SV-083's scroll and notice acts. */
    fun acts(): List<Act> = specs() + scrollSpecs() + noticeSpecs()

    /** [act] as the rig plays it, and its vector. */
    suspend fun captureAct(act: Act): Captured = when (act) {
        is Spec -> capture(act)
        is ScrollSpec -> captureScroll(act)
        is NoticeSpec -> captureNotice(act)
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
        private val seenL = HashMap<Int, Int>()
        private val seenR = HashMap<Int, Int>()
        fun prog(a: Arm): Int = progress(r.sim, a, if (a == Arm.LEFT) seenL else seenR, slots, ANY_TAG)
        init {
            // inside the model's own dispatch: the progress as the message arrives; and every millisecond between
            r.onImage = { a, img -> events += Event(a, img.copyOf(), null, prog(a), act.get()) }
            r.scope.launch { while (true) { synchronized(r.sim) { prog(Arm.LEFT); prog(Arm.RIGHT) }; delay(1) } }
        }
        /** A gesture on the ring: the hook on both lenses, then its stock echo ([TransitionShellTest.Rig.fire]). */
        fun fire(eventId: Int) {
            val p = synchronized(r.sim) { prog(Arm.RIGHT) }
            events += Event(null, null, longArrayOf(CfwModes.DEV_RING.toLong(), eventId.toLong(), 0), p, act.get())
            r.fire(eventId)
        }
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
        r.until("$name: the notice's lane done, no cache work") { r.onLoop { r.shell.noticeLaneDoneForTest() && !r.shell.cacheWorkInFlight } }
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
            val wraps = act.sumOf { ss -> ss.count { playIn(it, MainStaging.WRAP_SLOT) } }
            if (spec.form == ScrollForm.WRAP) check(wraps == 2) { "${spec.name}: $wraps wrap PLAY(s) in slot ${MainStaging.WRAP_SLOT}, not one each way" }
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
                    ScrollForm.WRAP -> check(drew.count { it.slot == MainStaging.WRAP_SLOT } == 2) { "${spec.name}: not two wraps whole on RIGHT: ${said(local, 0..1)}" }
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
     *  takes; [endCheck] reads RIGHT's 114 bodies — all of them, and the act's (those after the session's step 0). */
    private fun vector(name: String, events: List<Event>, cacheKib: Int, flags: Int, finalCrc: Map<Arm, Long>,
                       halves: MutableList<String>, slots: List<Int> = TRANSITION_SLOTS, accept: (Int) -> Boolean = TRANSITION_TAG,
                       endCheck: (all: List<DamageMsg.Motion>, act: List<DamageMsg.Motion>) -> Unit): JsonObject {
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
        fun notes() { for (b in sim.conformanceNotifies(Arm.RIGHT)) bodies += DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, b))!!
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
            notes(); progress(sim, Arm.RIGHT, seen, slots, accept)
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
        // the act
        var first = true
        for (e in seq.filter { it.act }) {
            guard = 0
            while (progress(sim, Arm.RIGHT, seen, slots, accept) < e.progress) check(wake() && ++guard < 64) {
                "$name: the model's engine never reached progress ${e.progress} (at ${progress(sim, Arm.RIGHT, seen, slots, accept)})" }
            val ops = ArrayList<JsonObject>()
            if (first) { now = maxOf(now, ACT_TICK); ops += op("tick", now); first = false }
            ops += apply(e, act = true)
            notes(); progress(sim, Arm.RIGHT, seen, slots, accept); costs()
            record(ops)
        }
        guard = 0
        while (wake()) check(++guard < 64) { "$name: the engine never went idle after the act" }
        // checked on the model as laid out
        check(refused.isEmpty()) { "$name: ${refused.joinToString("; ")}" }
        for (a in Arm.entries) check(sim.shadowCrc32(a) == finalCrc[a]) { "$name: $a's shadow at the end is not the rig's" }
        for (a in Arm.entries) check(sim.shadowCrc32(a) == sim.panelCrc32(a)) { "$name: $a's panel is not its shadow at the end" }
        endCheck(bodies, bodies.drop(sessionBodies))
        return JsonObject(mapOf("name" to JsonPrimitive(name), "steps" to JsonArray(steps), "contract" to JsonPrimitive(2),
            "start" to JsonPrimitive("zero"), "panel" to JsonPrimitive(true), "features" to JsonArray(listOf(JsonPrimitive(FEATURES)))))
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
