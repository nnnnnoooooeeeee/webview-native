package com.webview.webview_app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

//========== PULL TO REFRESH LAYOUT ==========

/**
 * Pull-to-refresh in the style of Chrome for Android, without AndroidX.
 *
 * Wraps one scrolling child (the WebView). When the child is scrolled to the top,
 * dragging down pulls a round indicator in from the top edge. Once the drag passes
 * the trigger distance the indicator is "armed"; releasing it then starts a refresh.
 * Dragging back up above the trigger distance before letting go disarms it, and
 * the release only slides the indicator away (the refresh is cancelled).
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Called once when a pull is released while armed. Call [finishRefreshing] when done. */
    var onRefresh: (() -> Unit)? = null

    /**
     * Set by the page (see MainActivity) when the touched element sits inside an
     * inner scroll area that is not at its top. Written from a WebView binder
     * thread, read on the UI thread.
     */
    @Volatile
    var innerScrolled = false

    var isRefreshing = false
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val indicatorSize = (40 * density).toInt()

    // Distances are in "indicator travel" px, i.e. how far the indicator moved down.
    private val triggerDistance = 80 * density
    private val maxDistance = 128 * density

    private val indicator = RefreshIndicator(context)
    private var target: View? = null

    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var armed = false
    private var settleAnimator: ValueAnimator? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        target = getChildAt(0)
        // Added last so it is drawn above the WebView.
        addView(indicator, LayoutParams(indicatorSize, indicatorSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        hideIndicator()
    }

    //========== TOUCH HANDLING ==========

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || isRefreshing || target == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                dragging = false
                innerScrolled = false // the page answers again for this touch
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                val dy = ev.y - startY
                val dx = ev.x - startX
                // Only a mostly vertical downward drag, past the slop, at the very top.
                if (dy > touchSlop && dy > abs(dx) && canStartPull()) {
                    dragging = true
                    startY += touchSlop // the pull starts at zero where the slop ended
                    settleAnimator?.cancel()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return ev.actionMasked == MotionEvent.ACTION_DOWN
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> moveTo(ev.y - startY)
            MotionEvent.ACTION_UP -> {
                dragging = false
                release()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                armed = false
                release()
            }
        }
        return true
    }

    private fun canStartPull(): Boolean {
        val child = target ?: return false
        return !child.canScrollVertically(-1) && !innerScrolled
    }

    //========== INDICATOR POSITION ==========

    /** Follows the finger. [finger] is how far the finger moved down since the pull began. */
    private fun moveTo(finger: Float) {
        // Half of the finger movement, like Chrome, so the indicator feels "heavy".
        val pull = finger * DRAG_RATE
        if (pull <= 0f) {
            // Back above the start: nothing is pulled any more.
            armed = false
            hideIndicator()
            return
        }
        // Rubber band past the trigger: each extra px moves the indicator 40% as far.
        val travel = if (pull < triggerDistance) pull
        else min(maxDistance, triggerDistance + (pull - triggerDistance) * 0.4f)

        armed = pull >= triggerDistance
        val progress = min(1f, pull / triggerDistance)
        indicator.visibility = VISIBLE
        indicator.translationY = travel - indicatorSize
        // Grows from 60% to 100% over the first half of the pull.
        val scale = 0.6f + 0.4f * min(1f, progress * 2f)
        indicator.scaleX = scale
        indicator.scaleY = scale
        indicator.setPull(progress, armed, rotation = pull / maxDistance * 360f)
    }

    private fun release() {
        if (armed) {
            armed = false
            isRefreshing = true
            settleTo(triggerDistance * 0.75f - indicatorSize) { }
            indicator.startSpinning()
            onRefresh?.invoke()
        } else {
            settleTo(-indicatorSize.toFloat()) { hideIndicator() }
        }
    }

    /** Ends a refresh: the indicator shrinks away. Safe to call when not refreshing. */
    fun finishRefreshing() {
        if (!isRefreshing) return
        isRefreshing = false
        indicator.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
            indicator.stopSpinning()
            hideIndicator()
        }.start()
    }

    private fun settleTo(y: Float, end: () -> Unit) {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(indicator.translationY, y).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { indicator.translationY = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun hideIndicator() {
        indicator.visibility = INVISIBLE
        indicator.translationY = -indicatorSize.toFloat()
        indicator.scaleX = 1f
        indicator.scaleY = 1f
    }

    private companion object {
        const val DRAG_RATE = 0.5f
    }
}

//========== REFRESH INDICATOR ==========

/** The round button with the circular arrow; spins while a refresh runs. */
private class RefreshIndicator(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val color = context.getColor(R.color.primary)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        this.color = this@RefreshIndicator.color
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = this@RefreshIndicator.color
    }
    private val arcBounds = RectF()
    private val head = Path()

    private var progress = 0f
    private var startAngle = 0f
    private var spinner: ValueAnimator? = null
    private var sweep = 0f

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.indicator_background))
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        elevation = 6 * density
    }

    /** While pulling: the arc grows with [progress] (0..1) and turns with the finger. */
    fun setPull(progress: Float, armed: Boolean, rotation: Float) {
        this.progress = progress
        startAngle = rotation - 90f
        // Grey-ish (translucent) until armed, full color once a release would refresh.
        val alpha = if (armed) 255 else (90 + 120 * progress).toInt()
        arcPaint.alpha = alpha
        headPaint.alpha = alpha
        sweep = 270f * progress
        invalidate()
    }

    fun startSpinning() {
        arcPaint.alpha = 255
        spinner?.cancel()
        spinner = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1333
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val t = it.animatedValue as Float
                // Two turns per cycle while the arc length breathes between 30 and 270 degrees.
                startAngle = t * 720f - 90f
                sweep = 30f + 240f * (0.5f - 0.5f * cos(t * 2 * Math.PI).toFloat())
                progress = 0f // no arrow head while spinning
                invalidate()
            }
            start()
        }
    }

    fun stopSpinning() {
        spinner?.cancel()
        spinner = null
    }

    override fun onDraw(canvas: Canvas) {
        val inset = width * 0.28f
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, startAngle, sweep, false, arcPaint)
        if (progress > 0f) drawArrowHead(canvas)
    }

    /** A small triangle at the end of the arc, pointing along it. */
    private fun drawArrowHead(canvas: Canvas) {
        val radius = arcBounds.width() / 2f
        val cx = arcBounds.centerX()
        val cy = arcBounds.centerY()
        // End point of the arc on the circle: (cx + r*cos(a), cy + r*sin(a)).
        val end = Math.toRadians((startAngle + sweep).toDouble())
        val ex = cx + radius * cos(end).toFloat()
        val ey = cy + radius * sin(end).toFloat()
        // Tangent (direction of travel) is the radius direction turned by 90 degrees.
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val size = 3.5f * density * progress
        head.reset()
        head.moveTo(ex + tx * size, ey + ty * size) // tip, ahead of the arc end
        head.lineTo(ex - ty * size, ey + tx * size) // outer corner
        head.lineTo(ex + ty * size, ey - tx * size) // inner corner
        head.close()
        canvas.drawPath(head, headPaint)
    }

    override fun onDetachedFromWindow() {
        stopSpinning()
        super.onDetachedFromWindow()
    }
}
