package wm.damage.core.shell

/**
 * Build W, package G (the lead's decision H, row E4; G-design §4): the host's clocks for the shell's `slow` note —
 * what a loop message that held the loop 250 ms or more spent: the loop thread's own CPU time (computation) against
 * the wall clock (waiting: disk, a lock, a descheduled thread), and the collector's time during it. The shell is
 * shared by Android and the desktop, so the host supplies these: the phone through `android.os.Debug`, the desktop
 * through the JVM's management beans. A journal-only read; nothing decides on it.
 */
interface LoopClock {
    /** CPU time the CALLING thread has used, in ns; -1 when this host cannot say. */
    fun threadCpuNs(): Long

    /** The process's total collector time so far, in ms; -1 when this host cannot say. */
    fun gcMs(): Long
}
