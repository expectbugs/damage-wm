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
        assertEquals(8, s.appStyle("reader").depth, "an unset app defaults to depth 8")
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
