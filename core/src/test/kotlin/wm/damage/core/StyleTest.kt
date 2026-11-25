package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.shell.ShellSettings
import wm.damage.core.text.Face
import wm.damage.core.text.Faces
import wm.damage.core.text.FontSpec
import wm.damage.core.text.StyleTransform

/** The typography settings (Style.kt, 2026-08-31 — Adam's §Type reversal):
 *  the transform is the ONE mechanism chrome and every app draw through, so
 *  its edge behaviour is what the whole feature rests on. */
class StyleTest {

    @Test
    fun chromeTransformSwapsOnlySystemAndScalesEverything() {
        val t = StyleTransform(face = Face.LIST, systemOnly = true, scale = 1.15)
        val sys = t.apply(FontSpec(Face.SYSTEM, 20))
        assertEquals(Face.LIST, sys.face, "SYSTEM follows the global face")
        assertEquals(23, sys.sizePx, "and the global scale")
        val mono = t.apply(FontSpec(Face.MONO, 20))
        assertEquals(Face.MONO, mono.face, "a non-SYSTEM face is left alone by the chrome transform")
        assertEquals(23, mono.sizePx, "but still scales")
    }

    @Test
    fun appTransformReplacesEveryFaceAndForcesStyle() {
        val t = StyleTransform(face = Face.READER, systemOnly = false, scale = 0.85,
            bold = true, italic = false)
        val s = t.apply(FontSpec(Face.MONO, 18, italic = true))
        assertEquals(Face.READER, s.face)
        assertEquals(15, s.sizePx)
        assertTrue(s.bold, "bold FORCED on")
        assertTrue(!s.italic, "italic forced off")
        // null style keeps the spec's own flags
        val keep = StyleTransform(scale = 1.0).apply(FontSpec(Face.SYSTEM, 18, bold = true))
        assertTrue(keep.bold, "'default' style keeps the design's weights")
    }

    @Test
    fun rawSpecsBypassEveryTransform() {
        val t = StyleTransform(face = Face.MONO, scale = 1.3, bold = true)
        val raw = FontSpec(Face.SYSTEM, 18, raw = true)
        assertEquals(raw, t.apply(raw), "a Settings preview shows the CANDIDATE untouched")
    }

    @Test
    fun settingsClampRepairsRottenValues() {
        val s = ShellSettings(fontFace = "Comic Sans", fontStyle = "wavy", fontScale = 1.09,
            appStyles = mapOf("tmux" to ShellSettings.AppStyle(face = "Wingdings", depth = 999,
                scale = 1.2, style = "loud"))).clamped()
        assertEquals("Clear Sans", s.fontFace)
        assertEquals("default", s.fontStyle)
        assertEquals(1.15, s.fontScale, "nearest ladder step")
        val a = s.appStyle("tmux")
        assertEquals("default", a.face)
        assertEquals("default", a.style)
        assertEquals(16, a.depth, "depth clamps onto the 4 px ladder")
        assertEquals(1.15, a.scale)
        assertEquals(ShellSettings.GLOBAL_DEPTH, s.appStyle("reader").depth, "an unset app follows the Global row (§41)")
        assertEquals(s.depth, s.appDepthOf("reader"), "and so its content depth IS the global depth")
    }

    /** K1-2 stage 3c (the K1-2 spec §4, §6's row; decisions CV): an app's `Scroll effect` — additive (a record without it, or
     *  with it at "default", reads and writes as before), clamped (an unknown id, and Crush or Flip, which a window's list does
     *  not play itself yet, read "default"), and "default" follows `Animations`'. */
    @Test
    fun appScrollEffectIsAdditiveClampedAndDefaultFollowsAnimations_K12S3c() {
        val old = ShellSettings(appStyles = mapOf("files" to ShellSettings.AppStyle(depth = 8)))
        val json = old.toJson()
        assertTrue("scrollEffect" !in (json["appStyles"] as kotlinx.serialization.json.JsonObject)["files"].toString(),
            "an app at \"default\" writes no new key: the record as before: $json")
        assertEquals(old.clamped(), ShellSettings.fromJson(json), "and reads back the same")
        assertEquals("default", old.appStyle("files").scrollEffect)
        assertEquals(wm.damage.core.motion.ScrollEffect.DEPTH, old.scrollEffectOf("files"), "default → Animations' (Depth by default)")
        val s = ShellSettings(scrollEffect = "fall", appStyles = mapOf(
            "files" to ShellSettings.AppStyle(scrollEffect = "slide"),
            "tmux" to ShellSettings.AppStyle(scrollEffect = "crush"),
            "reader" to ShellSettings.AppStyle(scrollEffect = "wobble"))).clamped()
        assertEquals("slide", s.appStyle("files").scrollEffect)
        assertEquals(wm.damage.core.motion.ScrollEffect.SLIDE, s.scrollEffectOf("files"), "the app's own")
        assertEquals("default", s.appStyle("tmux").scrollEffect, "Crush has no window form yet: default")
        assertEquals("default", s.appStyle("reader").scrollEffect, "an unknown id: default")
        assertEquals(wm.damage.core.motion.ScrollEffect.FALL, s.scrollEffectOf("reader"), "default follows Animations'")
        assertEquals(wm.damage.core.motion.ScrollEffect.FALL, s.scrollEffectOf("games"), "an app never set follows Animations'")
        assertEquals(s, ShellSettings.fromJson(s.toJson()), "the record round-trips")
        assertEquals(listOf("default", "none", "depth", "slide", "fall"), ShellSettings.APP_SCROLL_EFFECTS)
    }

    @Test
    fun facesRoundTripTheirLabels() {
        for (label in Faces.LABELS) {
            val f = Faces.byLabel(label)
            assertTrue(f != null, label)
            assertEquals(label, Faces.label(f!!))
        }
        assertEquals(null, Faces.byLabel("Papyrus"))
    }
}
