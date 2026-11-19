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
        assertEquals(listOf("Scroll length" to "300 ms (default)", "Frames" to "auto (10 frames × 45 ms)"), w(true).rowsForTest("Animations"))
    }

    /** An older record without the keys reads the defaults (300 ms since decisions AZ.7, Auto); a value off the ladder clamps
     *  onto it. */
    @Test
    fun anOlderRecordReadsTheDefaults_ANIM() {
        val old = kotlinx.serialization.json.buildJsonObject { put("taps", kotlinx.serialization.json.JsonPrimitive("report")) }
        val s = ShellSettings.fromJson(old)
        assertEquals(300 to 0, s.scrollLength to s.frames)
        val odd = ShellSettings.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("scrollLength", kotlinx.serialization.json.JsonPrimitive(500)); put("frames", kotlinx.serialization.json.JsonPrimitive(7)) })
        assertEquals(450 to 0, odd.scrollLength to odd.frames, "500 ms to the nearest rung, 7 frames back to Auto")
        val round = ShellSettings.fromJson(ShellSettings().copy(scrollLength = 800, frames = 12).toJson())
        assertEquals(800 to 12, round.scrollLength to round.frames, "the keys round-trip")
    }
    /** Decisions AZ.7 (his word: "I like 300ms… the perfect length"): `Scroll length` is 300 ms by default — the setting, the
     *  staging's default motion and so, at 352, T 6 at P 46; a record that saved 450 when it was the default (the key left
     *  out) reads 300. */
    @Test
    fun theDefaultScrollLengthIs300_AZ7() {
        assertEquals(300, ShellSettings().scrollLength)
        assertEquals(wm.damage.core.shell.MainStaging.ScrollMotion(300, 0), wm.damage.core.shell.MainStaging.ScrollMotion.DEFAULT)
        assertTrue("scrollLength" !in ShellSettings().toJson(), "a default is not written, so an older record's omitted key reads the new default")
        val rig = wm.damage.core.shell.MainLocalCheck.MainRig(wm.damage.core.motion.BoxFace, 352, wm.damage.core.shell.MainLocalCheck.sampleRows())
        val ms = rig.staging(wm.damage.core.wire.CfwModes.CACHE2_MAX, wm.damage.core.wire.CfwModes.mainRegion(wm.damage.core.wire.CfwModes.CACHE2_MAX, true),
            wm.damage.core.shell.MainStaging.ScrollMotion(ShellSettings().scrollLength, ShellSettings().frames))
        assertEquals(46 to 6, ms.period to ms.t, "the default at 352")
    }
}
