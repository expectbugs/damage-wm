package wm.damage.core.geom

/**
 * The shell layout, expressed RELATIVE to a calibrated safe rect — DESIGN.md
 * §2.2b: no hardcoded 34/210/450 anywhere; the bars, the lens and the content
 * band are all positioned from the safe rect, so the first-light calibration
 * changes one value and nothing else. Until then the safe rect defaults to the
 * full 640x480, which is the assumption Adam chose to build on for now.
 *
 * Cell geometry is DESIGN.md §2.3, with the status bar rebalanced for the
 * compass tape per §4.5b: op 160 · status 132 · thru 128 · tape 100 · link 120.
 * Every x/w is a multiple of 4 and every y/h a multiple of 2 by construction —
 * `LayoutTest` asserts it for a sweep of safe rects, not just the default.
 */
data class Layout(val safe: Rect = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)) {

    companion object {
        const val TOP_H = 32
        const val DIV_H = 2
        const val STATUS_H = 28
        const val CONTENT_INSET_X = 16   // §3.3: the stereo-shift budget, not content space
        const val CONTENT_PAD = 16
        const val ROW_H = 32
        const val LENS_H = 64
        const val RAIL_W = 12            // §4.6: the WM-owned scroll rail at the right edge
    }

    init {
        val errs = Geometry.checkRect(safe, "safe rect")
        if (errs.isNotEmpty()) throw LintError("Layout safe rect invalid: $errs")
        require(safe.h >= TOP_H + DIV_H + LENS_H + 2 * CONTENT_PAD + DIV_H + STATUS_H) {
            "safe rect ${safe.h}px too short for the shell chrome"
        }
    }

    // --- bars ------------------------------------------------------------------
    val topBar = Rect(safe.x, safe.y, safe.w, TOP_H)
    val topDivider = Rect(safe.x, safe.y + TOP_H, safe.w, DIV_H)
    val statusBar = Rect(safe.x, safe.bottom - STATUS_H, safe.w, STATUS_H)
    val bottomDivider = Rect(safe.x, statusBar.y - DIV_H, safe.w, DIV_H)

    /** §2.3 top-bar cells (Title 384 / batteries 176 / clock 80 at full width),
     *  scaled by tiling the same proportions onto the safe width, grid-snapped. */
    val titleCell: Rect
    val batteryCell: Rect
    val clockCell: Rect

    /** §4.5b status-bar cells: op / status / throughput / compass tape / link. */
    val opCell: Rect
    val statusCell: Rect
    val thruCell: Rect
    val tapeCell: Rect
    val linkCell: Rect

    // --- content ---------------------------------------------------------------
    /** The window's area between the dividers, inset 16 px each side (§2.2/§3.3). */
    val content = Rect(
        safe.x + CONTENT_INSET_X,
        topDivider.bottom,
        safe.w - 2 * CONTENT_INSET_X,
        bottomDivider.y - topDivider.bottom,
    )

    /** §4.6: the WM draws the rail itself in the content area's right edge;
     *  List and Document content is therefore RAIL_W narrower. */
    val rail = Rect(content.right - RAIL_W, content.y, RAIL_W, content.h)
    val contentInner = Rect(content.x, content.y, content.w - RAIL_W, content.h)

    /** The lens band — centre pinned to the content area's vertical centre so
     *  Main, the switcher wheel and every list window share one axis (§4.2). */
    val lens: Rect

    /** §4.3 switcher panel: 240x176 centred on (content centre x, lens centre y). */
    val switcherPanel: Rect

    /** §4.5 notification box (max) centred the same way. */
    val notificationMax: Rect

    init {
        val cy = content.y + content.h / 2
        val lensY = Geometry.snapY(cy - LENS_H / 2)
        lens = Rect(content.x, lensY, content.w, LENS_H)

        val cx = safe.x + safe.w / 2
        switcherPanel = Rect(Geometry.snapX(cx - 120), Geometry.snapY(cy - 88), 240, 176)
        notificationMax = Rect(Geometry.snapX(cx - 124), Geometry.snapY(cy - 52), 248, 104)

        // Top bar cells: clock fixed 80, batteries fixed 176, title takes the rest.
        val clockW = 80
        val battW = 176
        val titleW = Geometry.snapX(safe.w - clockW - battW)
        titleCell = Rect(topBar.x, topBar.y, titleW, TOP_H)
        batteryCell = Rect(topBar.x + titleW, topBar.y, battW, TOP_H)
        clockCell = Rect(topBar.x + titleW + battW, topBar.y, safe.w - titleW - battW, TOP_H)

        // Status bar cells (§4.5b): fixed pitches at full width; at a narrower safe
        // width the op cell absorbs the difference (it is the marquee-friendly one).
        val fixed = 132 + 128 + 100 + 120
        val opW = Geometry.snapX(safe.w - fixed)
        if (opW < 40) throw LintError("safe rect too narrow for the status bar cells")
        var x = statusBar.x
        opCell = Rect(x, statusBar.y, opW, STATUS_H); x += opW
        statusCell = Rect(x, statusBar.y, 132, STATUS_H); x += 132
        thruCell = Rect(x, statusBar.y, 128, STATUS_H); x += 128
        tapeCell = Rect(x, statusBar.y, 100, STATUS_H); x += 100
        linkCell = Rect(x, statusBar.y, 120, STATUS_H)
    }

    /** Rows visible above/below the lens in a panning list (§4.2 geometry). */
    val rowsAbove: Int = (lens.y - content.y - CONTENT_PAD) / ROW_H
    val rowsBelow: Int = (content.bottom - lens.bottom - CONTENT_PAD) / ROW_H

    /** All chrome cells for the GEO007/GEO008 tiling check. */
    fun chromeCells(): Map<String, Rect> = mapOf(
        "title" to titleCell, "battery" to batteryCell, "clock" to clockCell,
        "op" to opCell, "status" to statusCell, "thru" to thruCell,
        "tape" to tapeCell, "link" to linkCell,
    )

    /** Build the safe rect for a reduced height mode (§4.2 Settings "Size"):
     *  e.g. 288-high band positioned within the panel. Grid-snapped. */
    fun withHeightMode(height: Int, pos: VPos): Layout {
        val h = Geometry.snapY(height.coerceIn(TOP_H + DIV_H + LENS_H + 2 * CONTENT_PAD + DIV_H + STATUS_H, Geometry.PANEL_H))
        val free = Geometry.PANEL_H - h
        val y = when (pos) {
            VPos.TOP -> 0
            VPos.UPPER -> Geometry.snapY(free / 4)
            VPos.CENTRE -> Geometry.snapY(free / 2)
            VPos.LOWER -> Geometry.snapY(free * 3 / 4)
            VPos.BOTTOM -> free
        }
        return Layout(Rect(safe.x, y, safe.w, h))
    }
}

/** Vertical placement of a reduced-height band inside the panel — Faceclaw's
 *  model (top/upper/centre/lower/bottom), adopted in §4.2 Settings ("Size"). */
enum class VPos { TOP, UPPER, CENTRE, LOWER, BOTTOM }
