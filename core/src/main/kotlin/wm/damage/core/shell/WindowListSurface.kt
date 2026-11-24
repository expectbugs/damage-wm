package wm.damage.core.shell

import wm.damage.core.local.BoundList
import wm.damage.core.local.BoundSurface

/**
 * K1-2 stage 3 (the K1-2 spec §5.1; stage 0's report §5): what the shell's list holder (`Shell`'s `ListBind`) needs of the
 * surface its list is offered through — the rules' [BoundSurface], the list it offers once its staging is counted
 * ([offered], set by the holder's poll), and the view its staging is painted from ([view]). Main's ([MainSurface]) and a
 * window's ([WindowListSurface]). Context for the reader: Adam's own glasses on his own firmware fork; display and input
 * work on a personal device. Loop-confined.
 */
interface ListSurface : BoundSurface {
    var offered: BoundList?
    /** The list view the staging is painted from; null when its window's list is not in front. */
    fun view(): WindowView.ListView?
}

/**
 * K1-2 stage 3 (the K1-2 spec §5.1, §6's `WindowListSurface` row; decisions BD: glasses list 1, entries 3–5, slot 0): the
 * surface a window's list is bound through. [bound] is the list the holder offers ([offered]) while its staging's key
 * matches the view in front ([matches]: the window, its list level, its title, its `ListModel`, its row count, the layout
 * and the planes — `Shell.windowListMatches`); [view], [row] and [setRow] are those of [front] — the view in front while
 * nothing is staged, else the staged view while it is in front, else none (a staging is never painted from another); [shift]
 * is 0 — the phone's cursor is an index, as Main's ([MainSurface.shift]). Cleared at a session start, the staging's drop
 * and a key mismatch by what it reads: [offered] is the holder's, the key the shell's. Loop-confined.
 */
class WindowListSurface(private val front: () -> WindowView.ListView?, private val matches: () -> Boolean) : ListSurface {
    override var offered: BoundList? = null

    override fun view(): WindowView.ListView? = front()

    override fun bound(): BoundList? = offered?.takeIf { matches() }

    override fun row(): Int = front()?.model?.cursor ?: -1

    /** row := [row] — an adoption (P3) or a tap's correction (the tap rule (4)): the lens drew it. */
    override fun setRow(row: Int) { front()?.model?.cursor = row }

    override fun shift(from: BoundList, to: BoundList): Int = 0
}
