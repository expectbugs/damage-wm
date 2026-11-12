package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The harnesses' shared wait (0.64 R1; review 1a, RD-7): poll [cond] every [stepMs] until it holds or
 * [maxMs] have passed, and decide on the evaluation that ENDED the wait — `CLAUDE.md`: "a wait
 * decides on ONE evaluation".
 *
 * The shape it replaces, `while (!cond() && clock) delay(n); assertTrue(cond(), text)`, asks twice:
 * the loop's last answer ends the wait and a second answer is asserted. The conditions the rigs
 * wait on are not all monotone — `cacheCountUnjudged == 0L` goes back above zero when a notice's
 * own glyph upload is acked, `sends(msg) == 2`, a count of journal lines read from the file again
 * — so a change between the two answers failed a wait that had decided. That shape also built its
 * text at the call, so "(5 unread)" in a failure was the count when the wait BEGAN: [message] is
 * a lambda here and runs once, when the wait gives up.
 *
 * Bounded by the wall clock like every harness wait (test code: no BLE, render or input path).
 */
internal suspend fun waitUntil(maxMs: Long, stepMs: Long = 5, message: () -> String, cond: () -> Boolean) {
    val t0 = System.currentTimeMillis()
    var ok = cond()
    while (!ok && System.currentTimeMillis() - t0 < maxMs) { delay(stepMs); ok = cond() }
    if (!ok) throw AssertionError(message())
}

class WaitTest {

    /** A condition that answers false, true, false: the wait ended on the second answer and that
     *  answer is its verdict. The two-evaluation shape asserts the third and fails here. */
    @Test
    fun aWaitDecidesOnTheAnswerThatEndedIt(): Unit = runBlocking {
        val answers = ArrayDeque(listOf(false, true, false))
        var asked = 0
        waitUntil(5_000, 1, { "the condition held on its second answer ($asked asked)" }) { asked++; answers.removeFirstOrNull() ?: false }
        assertEquals(2, asked, "the condition was asked until it held, and not again")
    }

    /** A condition that holds at once is asked once and nothing is waited for. */
    @Test
    fun aConditionThatHoldsAtOnceIsAskedOnce(): Unit = runBlocking {
        var asked = 0
        val t0 = System.nanoTime()
        waitUntil(60_000, 1_000, { "held at once" }) { asked++; true }
        assertEquals(1, asked, "asked once")
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "and not one step was slept")
    }

    /** The text of a wait that gives up is built THEN: it carries what was true at the end, not at the call. */
    @Test
    fun aWaitThatGivesUpSaysWhatWasTrueWhenItDid(): Unit = runBlocking {
        var asked = 0
        val e = assertFailsWith<AssertionError> { waitUntil(40, 1, { "gave up after $asked answers" }) { asked++; false } }
        assertTrue(asked >= 2, "the condition was polled ($asked)")
        assertEquals("gave up after $asked answers", e.message, "the text was built when the wait gave up, and the condition was not asked again after it")
    }
}
