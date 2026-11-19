package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.shell.SettingsWindow
import wm.damage.core.shell.ShellSettings

/** Build C2, decisions AF.0: the `Taps` and `Local input` rows are shown only on a build that reports DamageCaps bit 10 —
 *  on any other firmware they would be rows that do nothing. */
class SettingsLocalRowsTest {
    @Test
    fun theLocalInputRowsShowOnlyOnABit10Build_AF0() {
        fun rows(bit10: Boolean) = SettingsWindow(FakeText(), { ShellSettings() }, {}, bindBuild = { bit10 }).globalRowNamesForTest()
        assertTrue(rows(false).none { it == "Taps" || it == "Local input" }, "no bit 10: ${rows(false)}")
        assertTrue(rows(true).containsAll(listOf("Taps", "Local input")), "bit 10: ${rows(true)}")
    }

    /** Build ANIM (AV): the `Animations` category, shown on a bit-10 build only, holds `Scroll length` and `Frames`; the
     *  `Frames` row says the motion in force; the rows step along their ladders. */
    @Test
    fun theAnimationsCategoryShowsOnlyOnABit10Build_ANIM() {
        var st = ShellSettings()
        fun w(bit10: Boolean) = SettingsWindow(FakeText(), { st }, { st = it }, bindBuild = { bit10 }, motionHint = { "10 frames × 45 ms" })
        assertTrue("Animations" !in w(false).categoryNamesForTest(), "no bit 10: ${w(false).categoryNamesForTest()}")
        assertTrue("Animations" in w(true).categoryNamesForTest(), "bit 10: ${w(true).categoryNamesForTest()}")
        assertEquals(listOf("Scroll length" to "450 ms (default)", "Frames" to "auto (10 frames × 45 ms)"), w(true).rowsForTest("Animations"))
    }

    /** An older record without the keys reads the defaults (450 ms, Auto); a value off the ladder clamps onto it. */
    @Test
    fun anOlderRecordReadsTheDefaults_ANIM() {
        val old = kotlinx.serialization.json.buildJsonObject { put("taps", kotlinx.serialization.json.JsonPrimitive("report")) }
        val s = ShellSettings.fromJson(old)
        assertEquals(450 to 0, s.scrollLength to s.frames)
        val odd = ShellSettings.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("scrollLength", kotlinx.serialization.json.JsonPrimitive(500)); put("frames", kotlinx.serialization.json.JsonPrimitive(7)) })
        assertEquals(450 to 0, odd.scrollLength to odd.frames, "500 ms to the nearest rung, 7 frames back to Auto")
        val round = ShellSettings.fromJson(ShellSettings().copy(scrollLength = 800, frames = 12).toJson())
        assertEquals(800 to 12, round.scrollLength to round.frames, "the keys round-trip")
    }
}
