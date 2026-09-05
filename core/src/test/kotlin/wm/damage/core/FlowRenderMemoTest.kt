package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.windows.tmux.FlowRender
import wm.damage.core.windows.tmux.PaneFrame

/** `HANDOFF.md` §35: a pushed frame wraps only the lines that changed, and
 *  what it draws is what a fresh renderer draws. */
class FlowRenderMemoTest {

    private fun frame(lines: List<String>) = PaneFrame(lines, 80, lines.size, 0, 0, true, false, 0L)

    private fun render(fr: FlowRender, lines: List<String>): Gray8 {
        val g = Gray8(640, 480)
        fr.renderTail(g, Rect(16, 34, 608, 400), frame(lines))
        return g
    }

    @Test
    fun onlyChangedLinesAreWrappedAgainAndThePixelsAreTheSame() {
        val text = FakeText()
        val esc = "\u001b"
        val a = (1..40).map { "line $it some ${esc}[1mstyled${esc}[0m terminal output that is long enough to wrap around the width more than once $it" }
        val b = a.toMutableList().also { it[7] = "a changed line"; it[30] = "another changed line" }

        val memo = FlowRender(text)
        render(memo, a)
        val wrapsFirst = memo.wrapsDone
        assertEquals(40L, wrapsFirst, "every line wrapped once on the first frame")
        val pixels = render(memo, b)
        assertEquals(2L, memo.wrapsDone - wrapsFirst, "the second frame wraps only its two changed lines")

        val fresh = FlowRender(FakeText())
        val expect = render(fresh, b)
        assertEquals(expect.pix.toList(), pixels.pix.toList(), "the memoised frame draws exactly what a fresh renderer draws")

        // a width change is a new memo: everything wraps again, once
        val g = Gray8(640, 480)
        memo.renderTail(g, Rect(16, 34, 500, 400), frame(b))
        assertEquals(40L, memo.wrapsDone - wrapsFirst - 2, "a new width re-wraps every line once")
    }
}
