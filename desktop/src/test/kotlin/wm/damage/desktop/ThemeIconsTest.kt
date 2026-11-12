package wm.damage.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APK 0.65, R4 item 7 (review 3B-2): a resolve counted itself in [ThemeIcons.completions] BEFORE it asked for its
 * repaint ([ThemeIcons] `onLoaded`), so the snapshot harness's one evaluation could read the new count, then a shell
 * at rest (the repaint not posted yet), then nothing in flight — and end its icon wait with the chain's repaint still
 * to come (P7 item 11's `11-files-locations`). Fixed: the count moves after `onLoaded`. The cache directory is a
 * temporary one; the theme is a name no system has, so the resolve is a clean miss.
 */
class ThemeIconsTest {
    @Test
    fun aResolveIsCountedOnlyAfterItsRepaintIsAsked() {
        val tmp = Files.createTempDirectory("theme-icons")
        try {
            val seen = AtomicLong(-1)
            val latch = CountDownLatch(1)
            val box = arrayOfNulls<ThemeIcons>(1)
            val icons = ThemeIcons(AwtImages(), tmp.resolve("icons"), themeOverride = "no-such-theme",
                onLoaded = { seen.set(box[0]!!.completions()); latch.countDown() })
            box[0] = icons
            icons.icon("no-such-icon-b", 16)                   // a clean miss, resolved on the pool
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the clean miss asked for its repaint")
            assertEquals(0L, seen.get(), "not counted before its repaint is asked")
            assertTrue(icons.missing("no-such-icon-b", 16), "the miss is latched")
        } finally { tmp.toFile().deleteRecursively() }
    }
}
