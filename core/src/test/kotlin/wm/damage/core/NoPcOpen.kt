package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue
import org.junit.rules.Verifier

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC.
 *
 * Stage OP (`C3-decisions` Part DG): the "Open on PC" opener every test hands a real `LocalFilesProvider` or
 * `LocalTorrentsProvider`. The PC's own opener (`desktop/Main.kt`) starts `xdg-open` on his display; a test opens
 * nothing on the PC. This one starts nothing: it records the call and throws (the op is refused), and [assertNone]
 * fails the test that reached it — as a JUnit rule (`@get:Rule val pc = NoPcOpen()`) after each test by itself.
 */
class NoPcOpen : Verifier(), (String) -> Unit {
    val calls = CopyOnWriteArrayList<String>()

    override fun invoke(target: String) {
        calls += target
        throw IllegalStateException("a test opens nothing on the PC ($target)")
    }

    fun assertNone() = assertTrue(calls.isEmpty(), "a test reached \"Open on PC\": $calls")

    override fun verify() = assertNone()
}
