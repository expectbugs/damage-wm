package wm.damage.core.transport

/**
 * What the glasses are believed to hold, per lens — the source every replica
 * draws (HANDOFF.md §8.2 "Mirror"). A transport owns one: for the sim
 * transport it is the sim itself; a hardware transport keeps a private
 * [wm.damage.core.sim.GlassFirmwareSim] fed the exact bytes it writes, so the
 * replica shows what the firmware model holds after applying our own traffic —
 * exact relative to the model, never Even's simulator; a transport reached over
 * the network seam mirrors the far end's panels as they are streamed.
 */
interface LensPanels {
    /** True when this mirror applies the exact bytes the transport wrote,
     *  synchronously with the write — a local mirror. False for a mirror fed
     *  over a network seam, which lags and is display-only: the shell's
     *  divergence check only reads exact mirrors. */
    val exact: Boolean

    /** Packed-4bpp row stride in bytes (PANEL_W / 2). */
    val stride: Int

    /** The live packed-4bpp panel buffer for [arm] — PANEL_H * stride bytes,
     *  high nibble = left pixel. Owned by the mirror: readers copy before
     *  holding it across frames and never write into it. */
    fun panel(arm: Arm): ByteArray

    /** A consistent copy of [arm]'s panel, taken under the mirror's own lock —
     *  for readers that compare or raise alarms (a torn read of the live
     *  buffer is fine for a replica, not for the divergence check). */
    fun snapshot(arm: Arm): ByteArray

    /** APK 0.65, P6 item 5 (the loop-cost sweep's LC-2): the rows of [arm]'s panel written since the last call
     *  (empty: none), and from then on none. The shell's agreement check compares those rows with the rows its own
     *  flushes touched. A mirror that does not keep them answers every row, which is always right. */
    fun takeChangedRows(arm: Arm): IntRange = 0 until wm.damage.core.geom.Geometry.PANEL_H

    /** P6 item 5: [block] over a consistent view of [arm]'s panel — the live buffer under the mirror's own lock where
     *  it has one (no copy); a copy otherwise. [block] reads only, and returns before the lock is let go. */
    fun <T> readPanel(arm: Arm, block: (ByteArray) -> T): T = block(snapshot(arm))

    /** Build C1 (spec §6.5, `FIRMWARE.md` §5.6): the rects of [arm]'s panel whose pixels this mirror cannot know — a
     *  motion instance's areas from its PLAY until the later of RIGHT's notify and its nominal end, where the lens
     *  draws steps on its own clock. The shell's agreement check skips them. Read inside [readPanel]'s block, so the
     *  two are one view. A mirror with no engine answers none. */
    fun unknownAreas(arm: Arm): List<wm.damage.core.geom.Rect> = emptyList()

    /** Build C1's ruling B-N5 (`FIRMWARE.md` §5.6): true while this mirror holds a motion instance open on either lens,
     *  so the compositor keeps one sub-message of the batch's count back for the CLOSE the transport may add. A mirror
     *  with no engine answers false. */
    fun motionOpen(): Boolean = false

    /** Music's motion update, stage L (`shell/WindowLanes.kt`): the tag of the instance open in [slot] on [arm]'s lens as
     *  this mirror models it — drawing, waiting or a done owner — or null when the slot is empty. A mirror with no engine
     *  model answers [modelsMotion] false, and the held lanes do not run on it. */
    fun motionTagIn(arm: Arm, slot: Int): Int? = null
    val modelsMotion: Boolean get() = false

    fun addListener(l: LensListener)
    fun removeListener(l: LensListener)

    fun interface LensListener {
        /** [arm]'s panel content changed (a present landed, or stock repainted). */
        fun panelChanged(arm: Arm)
    }
}
