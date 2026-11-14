package wm.damage.phone

import wm.damage.core.shell.LoopClock

/**
 * Build W, package G (E4; G-design §4): the phone's clocks for the shell's `slow` note — `Debug.threadCpuTimeNanos`
 * (the calling thread's CPU time; -1 where the system does not support it) and the runtime's `art.gc.gc-time`
 * statistic (the collector's total in ms, a string; -1 when absent or not a number). The shell says a -1 once.
 */
object AndroidLoopClock : LoopClock {
    override fun threadCpuNs(): Long = android.os.Debug.threadCpuTimeNanos()

    override fun gcMs(): Long = android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: -1L
}
