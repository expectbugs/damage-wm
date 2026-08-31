package wm.damage.core.text

/**
 * User-directed typography (2026-08-31, Adam's ask — and a recorded REVERSAL
 * of §Type's "the system face is not negotiable per window": chrome and Main
 * now follow a GLOBAL font/size/style setting, and every app can override
 * font/size/style for its own content, each choice previewed in its own face
 * in the Settings window).
 *
 * One mechanism serves both: a [StyleTransform] rewrites every [FontSpec] on
 * its way into the platform rasterizer — the shell hands its chrome surfaces
 * a [StyledText] wrapper carrying the global transform, and hands each window
 * a per-app transform (`DamageWindow.styleTransform`) that windows route all
 * their measuring and drawing through. Rewriting at the spec level keeps
 * wraps, fits and draws consistent by construction: whatever measured is what
 * draws.
 */
data class StyleTransform(
    /** Replace the face: chrome swaps SYSTEM for the global choice; an app
     *  override replaces EVERY face the window uses. Null = keep. */
    val face: Face? = null,
    /** Applies only to specs whose face is SYSTEM (the chrome transform);
     *  false replaces any face (the app transform). */
    val systemOnly: Boolean = false,
    val scale: Double = 1.0,
    /** Style FORCE: null = keep the spec's own flags. */
    val bold: Boolean? = null,
    val italic: Boolean? = null,
) {
    fun apply(f: FontSpec): FontSpec {
        if (f.raw) return f                       // Settings previews show the CANDIDATE, untouched
        val face2 = if (face != null && (!systemOnly || f.face == Face.SYSTEM)) face else f.face
        return FontSpec(
            face = face2,
            sizePx = maxOf(6, Math.round(f.sizePx * scale).toInt()),
            bold = bold ?: f.bold,
            italic = italic ?: f.italic,
            raw = false,
        )
    }

    companion object {
        val NONE = StyleTransform()
    }
}

/** A [TextRasterizer] whose specs pass through a live mapper — measure,
 *  metrics, draw and coverage all see the SAME rewritten spec. */
class StyledText(
    private val base: TextRasterizer,
    private val mapper: (FontSpec) -> FontSpec,
) : TextRasterizer {
    override fun measure(text: String, font: FontSpec): Int = base.measure(text, mapper(font))
    override fun metrics(font: FontSpec): FontMetrics = base.metrics(mapper(font))
    override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) =
        base.draw(surface, x, y, text, mapper(font), level)
    override fun covers(text: String, font: FontSpec): Boolean = base.covers(text, mapper(font))
}

/** The four loaded faces by their Settings labels — the option lists, the
 *  preview specs, and the persisted names all go through here. */
object Faces {
    val LABELS = listOf("Clear Sans", "Fira Sans", "Alegreya", "JetBrains Mono")

    fun byLabel(label: String): Face? = when (label) {
        "Clear Sans" -> Face.SYSTEM
        "Fira Sans" -> Face.LIST
        "Alegreya" -> Face.READER
        "JetBrains Mono" -> Face.MONO
        else -> null
    }

    fun label(face: Face): String = when (face) {
        Face.SYSTEM -> "Clear Sans"
        Face.LIST -> "Fira Sans"
        Face.READER -> "Alegreya"
        Face.MONO -> "JetBrains Mono"
    }
}
