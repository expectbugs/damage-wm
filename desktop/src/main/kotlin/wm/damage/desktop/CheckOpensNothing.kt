package wm.damage.desktop

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC.
 *
 * Stage OP (`C3-decisions` Part DG): the "Open on PC" opener `--selfcheck` and `--snapshot` hand the real Files
 * provider. The PC's own opener (`Main.kt`) starts `xdg-open` on his display; a check opens nothing on the PC. This one
 * starts nothing: it records the call and throws (the step's op is refused), and the check's end turns any recorded
 * call into a failure ([failure]).
 */
internal class CheckOpensNothing : (String) -> Unit {
    private val calls = CopyOnWriteArrayList<String>()

    override fun invoke(target: String) {
        calls += target
        throw IllegalStateException("a check opens nothing on the PC ($target)")
    }

    /** The failure line when a step reached "Open on PC", null when none did. */
    fun failure(): String? =
        if (calls.isEmpty()) null else "a check opens nothing on the PC, yet a step asked to open ${calls.toList()}"
}
