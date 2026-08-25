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

    fun addListener(l: LensListener)
    fun removeListener(l: LensListener)

    fun interface LensListener {
        /** [arm]'s panel content changed (a present landed, or stock repainted). */
        fun panelChanged(arm: Arm)
    }
}
