package wm.damage.desktop

import java.lang.management.ManagementFactory
import wm.damage.core.shell.LoopClock
import wm.damage.core.util.Log

/**
 * Build W, package G (E4; G-design §4): the desktop's clocks for the shell's `slow` note — the JVM's thread bean for
 * the calling thread's CPU time and its collector beans for the collector's total. A figure the JVM cannot give is
 * -1, which the shell says once.
 */
object JvmLoopClock : LoopClock {
    private val threads = ManagementFactory.getThreadMXBean()
    private val cpuOn: Boolean = try {
        threads.isCurrentThreadCpuTimeSupported && (threads.isThreadCpuTimeEnabled || run { threads.isThreadCpuTimeEnabled = true; true })
    } catch (e: Exception) {           // UnsupportedOperationException / SecurityException: said, and the figure reads -1
        Log.w("shell", "thread CPU time is not available on this JVM: ${e.message}")
        false
    }

    override fun threadCpuNs(): Long = if (cpuOn) threads.currentThreadCpuTime else -1L

    override fun gcMs(): Long {
        var total = 0L
        for (b in ManagementFactory.getGarbageCollectorMXBeans()) {
            val t = b.collectionTime
            if (t < 0) return -1L        // a collector that does not report its time: no total
            total += t
        }
        return total
    }
}
