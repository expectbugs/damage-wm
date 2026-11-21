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
        // the Scroll tier's rows (K1-1; C3 X2T: "(default)" on `Scroll frames` too), then the notice box's (K1-4a: the
        // Popups tier, last on the page as `MOTION.md` §7 orders it); every row says "(default)" at its default
        // (decisions AM (3), BW (3))
        assertEquals(listOf("Scroll effect" to "Depth (default)", "Scroll length" to "300 ms (default)", "Scroll frames" to "auto (10 frames × 45 ms) (default)",
                "Popup length" to "300 ms (default)", "Popup frames" to "auto (default)", "Popup · Unfurl" to "on (default)",
                "Popup · Fade" to "off (default)", "Popup · Slide" to "off (default)"),
            w(true).rowsForTest("Animations"))
        // off its default a Popup row says no default
        st = st.copy(popupFrames = 6, popupUnfurl = false, popupFade = true)
        assertEquals(listOf("Popup frames" to "6", "Popup · Unfurl" to "off", "Popup · Fade" to "on", "Popup · Slide" to "off (default)"),
            w(true).rowsForTest("Animations").filter { it.first in setOf("Popup frames", "Popup · Unfurl", "Popup · Fade", "Popup · Slide") })
    }

    /** K1-1: `Scroll effect` cycles None · Depth · Slide · Fall (Depth the default); an older record reads Depth, a value
     *  no effect has reads Depth, and the key round-trips. C3 DM-1: Flip and Crush in the order his plan gives (Depth,
     *  Slide, Flip, Fall, …, Crush; None first, as K1-1 put it). */
    @Test
    fun theScrollEffectRowCyclesTheFourEffects_K11() {
        assertEquals(listOf("none", "depth", "slide", "flip", "fall", "crush"), ShellSettings.SCROLL_EFFECTS)
        assertEquals("depth", ShellSettings().scrollEffect)
        assertEquals("depth", ShellSettings.fromJson(kotlinx.serialization.json.buildJsonObject { put("taps", kotlinx.serialization.json.JsonPrimitive("report")) }).scrollEffect)
        assertEquals("depth", ShellSettings.fromJson(kotlinx.serialization.json.buildJsonObject { put("scrollEffect", kotlinx.serialization.json.JsonPrimitive("glitch")) }).scrollEffect)
        assertEquals("fall", ShellSettings.fromJson(ShellSettings().copy(scrollEffect = "fall").toJson()).scrollEffect)
        assertTrue("scrollEffect" !in ShellSettings().toJson(), "a default is not written")
        var st = ShellSettings()
        val w = SettingsWindow(FakeText(), { st }, { st = it }, bindBuild = { true })
        val seen = ArrayList<String>()
        repeat(6) { seen += w.rowsForTest("Animations").first().second; st = st.copy(scrollEffect = ShellSettings.SCROLL_EFFECTS[(ShellSettings.SCROLL_EFFECTS.indexOf(st.scrollEffect) + 1) % 6]) }
        assertEquals(listOf("Depth (default)", "Slide", "Flip", "Fall", "Crush", "None"), seen)
        assertEquals("crush", ShellSettings.fromJson(ShellSettings().copy(scrollEffect = "crush").toJson()).scrollEffect, "Crush round-trips")
    }

    /** C3 DM-1 (D3): the row keeps his choice and says what plays when it is not its own program — the shell's words
     *  ([SettingsWindow]'s `scrollPlays`): Crush below stage 5 "plays Slide", Flip there "plays its height turn"; nothing
     *  added when the effect plays itself. The words fit the row (lint SYM002 and the row's own width check). */
    @Test
    fun theScrollEffectRowSaysWhatPlays_DM1() {
        var st = ShellSettings().copy(scrollEffect = "crush")
        var plays = "plays Slide"
        val w = SettingsWindow(FakeText(), { st }, { st = it }, bindBuild = { true }, scrollPlays = { plays })
        assertEquals("Scroll effect" to "Crush (plays Slide)", w.rowsForTest("Animations").first())
        st = st.copy(scrollEffect = "flip"); plays = wm.damage.core.motion.ScrollEffects.playsAt(wm.damage.core.motion.ScrollEffect.FLIP, false).words(wm.damage.core.motion.ScrollEffect.FLIP)
        assertEquals("Scroll effect" to "Flip (plays its height turn)", w.rowsForTest("Animations").first())
        plays = wm.damage.core.motion.ScrollEffects.playsAt(wm.damage.core.motion.ScrollEffect.FLIP, true).words(wm.damage.core.motion.ScrollEffect.FLIP)
        assertEquals("Scroll effect" to "Flip", w.rowsForTest("Animations").first(), "at stage 5 Flip plays itself")
        st = st.copy(scrollEffect = "crush"); plays = wm.damage.core.motion.ScrollEffects.playsAt(wm.damage.core.motion.ScrollEffect.CRUSH, false).words(wm.damage.core.motion.ScrollEffect.CRUSH)
        assertEquals("Scroll effect" to "Crush (plays Slide)", w.rowsForTest("Animations").first(), "the words from what plays below stage 5")
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
