package wm.damage.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import wm.damage.core.geom.Geometry
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels

/**
 * The 1x lens replica — strictly native 640x480 per lens, no upscaling (a 2x
 * view flattered delicate type and misled the design; DESIGN.md §Type). Draws
 * the transport's MIRROR (HANDOFF.md §8.2), whichever transport: sim, BLE, or
 * the seam-fed mirror in remote mode. Green micro-LED mapping as
 * design/render_shots.py green().
 *
 * Mouse = the ring: wheel notch = scroll (one notch per wheel unit), left
 * click = tap, right click = double-tap, press-and-hold ≥ 600 ms = long-press
 * (the ring's own threshold is unmeasured) then release. Keyboard stays:
 * ↑/↓ scroll · Enter tap · Backspace/Esc double-tap · Space long-press · R
 * release · Tab lens toggle · B both lenses side by side.
 *
 * The status strip sits UNDER the panel, outside the 640x480 image, so the
 * true-1x rule holds for the pixels that matter; it wraps rather than cuts
 * (NO TRUNCATION — it is where a fault text is shown). A held key is one
 * gesture: the ring has no auto-repeat. Closing the window stops the stack
 * (state saved, lease released) before the process ends.
 */
class Preview(
    private val panels: () -> LensPanels?,
    private val onGesture: (Int) -> Unit,
    private val status: () -> String,
) : JPanel() {

    private var arm = Arm.LEFT
    private var both = false
    private val imgL = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)
    private val imgR = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)
    private var source: LensPanels? = null
    private val listener = LensPanels.LensListener { a -> SwingUtilities.invokeLater { refresh(a) } }
    /** Width pinned to the panel, height whatever the wrapped text needs —
     *  the frame re-packs when that height changes (NO TRUNCATION). */
    private val strip = object : JTextArea(" ") {
        override fun getPreferredSize(): Dimension = Dimension(Geometry.PANEL_W, super.getPreferredSize().height)
    }.apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isFocusable = false
    }
    private val keysDown = HashSet<Int>()
    /** Set once a close is in progress: the strip keeps its closing text and
     *  a second close click waits instead of starting another stop. */
    @Volatile var closing = false
        private set

    init {
        background = Color.BLACK
        isFocusable = true
        setFocusTraversalKeysEnabled(false)   // Tab is ours
        updateSize()
        // a key held across a focus change never reports its release here:
        // forget every key on focus loss, or its next press would be dropped
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { keysDown.clear() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) { keysDown.remove(e.keyCode) }
            override fun keyPressed(e: KeyEvent) {
                if (!keysDown.add(e.keyCode)) return     // auto-repeat: one gesture per press
                when (e.keyCode) {
                    KeyEvent.VK_UP -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_TOP)
                    KeyEvent.VK_DOWN -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)
                    KeyEvent.VK_ENTER -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_DOUBLE_CLICK)
                    KeyEvent.VK_SPACE -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS)
                    KeyEvent.VK_R -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS_RELEASE)
                    KeyEvent.VK_TAB -> toggleArm()
                    KeyEvent.VK_B -> toggleBoth()
                }
            }
        })
        val holdTimer = Timer(HOLD_MS) { holding = true; onGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS) }
        holdTimer.isRepeats = false
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                if (SwingUtilities.isLeftMouseButton(e)) { holding = false; holdTimer.restart() }
            }

            override fun mouseReleased(e: MouseEvent) {
                when {
                    SwingUtilities.isLeftMouseButton(e) -> {
                        holdTimer.stop()
                        if (holding) { holding = false; onGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS_RELEASE) }
                        else if (e.clickCount <= 1) onGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)
                        // the second click of a double-click is not a second tap
                    }
                    SwingUtilities.isRightMouseButton(e) -> onGesture(wm.damage.core.wire.EvenHubMsg.EV_DOUBLE_CLICK)
                    SwingUtilities.isMiddleMouseButton(e) -> toggleArm()
                }
            }
        })
        addMouseWheelListener { e: MouseWheelEvent ->
            wheelAccum += e.preciseWheelRotation
            while (wheelAccum >= 1.0) { wheelAccum -= 1.0; onGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM) }
            while (wheelAccum <= -1.0) { wheelAccum += 1.0; onGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_TOP) }
        }
        // follow the mirror provider (a rebuilt stack brings a new mirror) and
        // refresh the strip
        Timer(500) {
            attach()
            if (closing) return@Timer
            val s = " ${status()}"
            if (strip.text != s) strip.text = s
            // the wrapped text's height is known once the strip is laid out at
            // the panel's width: grow the frame to fit it
            if (strip.preferredSize.height != strip.height) topFrame()?.pack()
        }.start()
        attach()
    }

    private var holding = false
    private var wheelAccum = 0.0

    private fun attach() {
        val p = panels()
        if (p === source) return
        source?.removeListener(listener)
        source = p
        p?.addListener(listener)
        refresh(Arm.LEFT); refresh(Arm.RIGHT)
    }

    private fun updateSize() {
        preferredSize = Dimension(if (both) Geometry.PANEL_W * 2 + GAP else Geometry.PANEL_W, Geometry.PANEL_H)
        revalidate()
        topFrame()?.pack()
        topFrame()?.title = title()
    }

    private fun toggleArm() { arm = if (arm == Arm.LEFT) Arm.RIGHT else Arm.LEFT; updateSize(); repaint() }
    private fun toggleBoth() { both = !both; updateSize(); repaint() }

    private fun topFrame(): JFrame? = SwingUtilities.getWindowAncestor(this) as? JFrame

    private fun title() = "Damage — ${if (both) "both lenses" else "${arm.name} lens"} · 1x (Tab lens · B both)"

    private fun refresh(a: Arm) {
        val p = source ?: return
        val panel = p.panel(a)
        val stride = p.stride
        val img = if (a == Arm.LEFT) imgL else imgR
        for (y in 0 until Geometry.PANEL_H) {
            for (x in 0 until Geometry.PANEL_W) {
                val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
                val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                val v = n * 17
                val r = (v * 0.16).toInt()
                val g = minOf(255, (v * 1.05).toInt())
                val bl = (v * 0.34).toInt()
                img.setRGB(x, y, (r shl 16) or (g shl 8) or bl)
            }
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (both) {
            g.drawImage(imgL, 0, 0, null)
            g.drawImage(imgR, Geometry.PANEL_W + GAP, 0, null)
        } else {
            g.drawImage(if (arm == Arm.LEFT) imgL else imgR, 0, 0, null)
        }
    }

    companion object {
        const val HOLD_MS = 600
        const val GAP = 16

        fun show(panels: () -> LensPanels?, onGesture: (Int) -> Unit, status: () -> String,
                 onClose: () -> Unit = {}): Preview {
            val p = Preview(panels, onGesture, status)
            SwingUtilities.invokeLater {
                val f = JFrame(p.title())
                f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                f.addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) {
                        if (p.closing) return          // one orderly stop; a second click waits on it
                        p.closing = true
                        p.strip.text = " stopping — saving state and releasing the display (this can take a few seconds on BLE)"
                        Thread({ onClose() }, "damage-close").start()
                    }
                })
                f.contentPane.background = Color.BLACK
                f.contentPane.layout = BorderLayout()
                f.contentPane.add(p, BorderLayout.CENTER)
                p.strip.foreground = Color(76, 178, 100)
                p.strip.background = Color.BLACK
                p.strip.isOpaque = true
                f.contentPane.add(p.strip, BorderLayout.SOUTH)
                f.isResizable = false
                f.pack()
                f.setLocationByPlatform(true)
                f.isVisible = true
                p.requestFocusInWindow()
            }
            return p
        }
    }
}
