package wm.damage.desktop

import java.awt.Color
import wm.damage.core.transport.Arm
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import wm.damage.core.geom.Geometry
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.wire.EvenHubMsg

/**
 * The 1x lens preview — strictly native 640x480, no upscaling: 2x flattered
 * delicate type and misled the design for several passes (DESIGN.md §Type).
 * Green micro-LED simulation matches design/render_shots.py's green().
 *
 * Keyboard = the ring, for the desk: ↑/↓ scroll · Enter tap · Backspace
 * double-tap · Space long-press · Tab toggles lens · F prints sim flags.
 */
class Preview(
    private val sim: GlassFirmwareSim,
    private val onGesture: (Int) -> Unit,
) : JPanel() {

    private var arm = Arm.LEFT
    private val img = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)

    init {
        preferredSize = Dimension(Geometry.PANEL_W, Geometry.PANEL_H)
        background = Color.BLACK
        isFocusable = true
        // Swing consumes Tab for focus traversal before KeyListeners see it;
        // without this the advertised lens toggle is dead (review round 1)
        setFocusTraversalKeysEnabled(false)
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {}
            override fun notify(arm: Arm, packet: ByteArray) {}
            override fun panelChanged(arm: Arm) {
                SwingUtilities.invokeLater { refresh() }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> onGesture(EvenHubMsg.EV_SCROLL_TOP)
                    KeyEvent.VK_DOWN -> onGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                    KeyEvent.VK_ENTER -> onGesture(EvenHubMsg.EV_CLICK)
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> onGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    KeyEvent.VK_SPACE -> onGesture(EvenHubMsg.EV_RING_LONG_PRESS)
                    KeyEvent.VK_R -> onGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE)
                    KeyEvent.VK_TAB -> {
                        arm = if (arm == Arm.LEFT) Arm.RIGHT
                        else Arm.LEFT
                        topFrame()?.title = title()
                        refresh()
                    }
                    KeyEvent.VK_F -> println("sim flags L=${sim.flags(Arm.LEFT)} " +
                        "R=${sim.flags(Arm.RIGHT)}")
                }
            }
        })
        refresh()
    }

    private fun topFrame(): JFrame? = SwingUtilities.getWindowAncestor(this) as? JFrame

    private fun title() = "Damage — ${arm.name} lens · sim · 1x (Tab switches lens)"

    private fun refresh() {
        val ctx = if (arm == Arm.LEFT) sim.left else sim.right
        val stride = ctx.stride
        for (y in 0 until Geometry.PANEL_H) {
            for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * stride + (x shr 1)].toInt() and 0xFF
                val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                val v = n * 17
                // green micro-LED simulation (render_shots.py green())
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
        g.drawImage(img, 0, 0, null)
    }

    companion object {
        fun show(sim: GlassFirmwareSim, onGesture: (Int) -> Unit): Preview {
            val p = Preview(sim, onGesture)
            SwingUtilities.invokeLater {
                val f = JFrame(p.title())
                f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                f.contentPane.add(p)
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
