package wm.damage.core

import java.util.concurrent.TimeUnit
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Shell

/*
 * C3 AH3: the one door for a test to read what the shell's loop changes in place — its notices, its staged records,
 * its programs — while the loop runs on another thread. A copy taken on the test's thread can overlap the loop's
 * change: `Notifications.queued()` read at size 1 while the loop's `post` added a notice and `show()` took it at once
 * threw "index: 0, size: 0" (GlyphSubsetShellTest, gate g18), and the shown box and the queue read one after the other
 * can see a notice in neither place. Read here, the block is one turn of the loop: nothing of the loop runs during it,
 * so it is one evaluation of one state, not a wait for anything. Its 10 s bound is a failure's, never a decision.
 */

/** [block] evaluated once on this shell's loop ([Shell.readOnLoopForTest]). */
internal fun <T> Shell.onLoop(block: () -> T): T = readOnLoopForTest(block).get(10, TimeUnit.SECONDS)

/** The notices this shell holds — the shown box first, then the queue — read in one turn of its loop. */
internal fun Shell.noticesNow(): List<Notifications.Notice> = onLoop { listOfNotNull(notifications.current) + notifications.queued() }

/** The queue behind the shown box, read in one turn of the shell's loop. */
internal fun Shell.queuedNow(): List<Notifications.Notice> = onLoop { notifications.queued() }
