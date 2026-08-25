package wm.damage.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import wm.damage.core.geom.Geometry
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.wire.EvenHubMsg

/**
 * The on-phone lens replica + touch ring. Renders one lens of the transport's
 * MIRROR (HANDOFF.md §8.2) with the green micro-LED mapping — the firmware
 * model fed the exact bytes the transport wrote, whether the driver is the
 * phone's own shell or a PC shell over the seam. The phone screen is
 * high-DPI, so the 640x480 frame scales by an INTEGER factor to fit — labeled
 * on screen, because scaled previews flatter type and only the 1x desktop view
 * or glass itself judges legibility (DESIGN.md §Type).
 *
 * Touch = the ring: tap / double-tap / long-press map 1:1; a vertical drag
 * past one row-height is a scroll notch per step.
 */
@SuppressLint("ViewConstructor")
class LensView(
    context: Context,
    private val panels: LensPanels,
    /** What the mirror stands for, for the on-screen label ("sim", "glasses"). */
    private val caption: String,
    private val onGesture: (Int) -> Unit,
) : View(context) {

    private val img = Bitmap.createBitmap(Geometry.PANEL_W, Geometry.PANEL_H, Bitmap.Config.ARGB_8888)
    private val rowBuf = IntArray(Geometry.PANEL_W)
    private val matrix = Matrix()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 160, 90)
        textSize = 24f
    }
    @Volatile private var arm = Arm.LEFT

    private var accumulatedDrag = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onGesture(EvenHubMsg.EV_CLICK); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onGesture(EvenHubMsg.EV_DOUBLE_CLICK); return true
        }

        override fun onLongPress(e: MotionEvent) {
            onGesture(EvenHubMsg.EV_RING_LONG_PRESS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            accumulatedDrag += dy
            val step = 48f * resources.displayMetrics.density / 2
            while (accumulatedDrag >= step) {
                accumulatedDrag -= step
                onGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            }
            while (accumulatedDrag <= -step) {
                accumulatedDrag += step
                onGesture(EvenHubMsg.EV_SCROLL_TOP)
            }
            return true
        }
    })

    private val listener = LensPanels.LensListener { a -> if (a == arm) postInvalidateOnAnimation() }

    init {
        panels.addListener(listener)
    }

    /** Unhook from the mirror — a rebuilt stack replaces this view, and the
     *  old mirror's listener list must not keep it (and its Activity) alive. */
    fun detach() {
        panels.removeListener(listener)
    }

    fun toggleArm() {
        arm = if (arm == Arm.LEFT) Arm.RIGHT else Arm.LEFT
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) accumulatedDrag = 0f
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val panel = panels.panel(arm)
        val stride = panels.stride
        for (y in 0 until Geometry.PANEL_H) {
            for (x in 0 until Geometry.PANEL_W) {
                val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
                val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                val v = n * 17
                rowBuf[x] = Color.rgb((v * 0.16).toInt(), minOf(255, (v * 1.05).toInt()), (v * 0.34).toInt())
            }
            img.setPixels(rowBuf, 0, Geometry.PANEL_W, 0, y, Geometry.PANEL_W, 1)
        }
        val sx = maxOf(1, width / Geometry.PANEL_W)
        val sy = maxOf(1, height / Geometry.PANEL_H)
        val s = minOf(sx, sy)
        matrix.reset()
        matrix.postScale(s.toFloat(), s.toFloat())
        matrix.postTranslate(
            ((width - Geometry.PANEL_W * s) / 2f),
            ((height - Geometry.PANEL_H * s) / 2f),
        )
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(img, matrix, null)
        val exact = if (panels.exact) "" else " · lags"
        canvas.drawText("${arm.name} lens · $caption$exact · preview x$s (not for legibility calls)",
            16f, height - 16f, label)
    }
}
