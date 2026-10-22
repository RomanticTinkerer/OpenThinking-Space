package com.openthinking.blankassistant.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openthinking.blankassistant.R
import java.util.Locale

/**
 * Full-screen assistant overlay.
 *
 * When the screenshot arrives it is shown full-bleed (with a quick shutter flash), then zooms out
 * into a white-bordered, rounded thumbnail while a dimming scrim fades in. Below it sits a pill with
 * three actions: Edit (pencil), Record (mic) and Save.
 *
 * It is a plain [View] tree with no AndroidX/Material dependency, because it lives in the
 * assistant session window rather than in an Activity.
 */
@SuppressLint("ViewConstructor")
class CaptureOverlayView(
    context: Context,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onEdit()
        fun onRecordToggle()
        fun onRecordReset()
        fun onSave()
        fun onDismiss()
    }

    enum class NoteState { NONE, RECORDING, RECORDED }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val screenBounds = context.getSystemService(WindowManager::class.java)
        .maximumWindowMetrics.bounds
    private val screenW = screenBounds.width()
    private val screenH = screenBounds.height()

    private var bottomInset = 0

    // --- Views -------------------------------------------------------------------------------

    private val scrim = View(context).apply {
        setBackgroundColor(context.getColor(R.color.capture_scrim))
        alpha = 0f
        setOnClickListener { listener.onDismiss() }
    }

    private val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val borderView = BorderView(context).apply { alpha = 0f }

    /** Holds the screenshot + its white border; this is the thing that scales down. */
    private val frame = FrameLayout(context).apply {
        visibility = INVISIBLE
        // Swallow taps so touching the screenshot itself doesn't fall through to the dismiss scrim.
        isClickable = true
        addView(imageView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(borderView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val flash = View(context).apply {
        setBackgroundColor(Color.WHITE)
        alpha = 0f
    }

    private val message = TextView(context).apply {
        setTextColor(context.getColor(R.color.capture_label))
        textSize = 16f
        gravity = Gravity.CENTER
        visibility = GONE
        setPadding(dp(32f).toInt(), 0, dp(32f).toInt(), 0)
    }

    private val statusText = TextView(context).apply {
        setTextColor(context.getColor(R.color.capture_label))
        textSize = 14f
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val editButton = ActionButton(R.drawable.ic_edit, R.string.action_edit).apply {
        setOnClickListener { listener.onEdit() }
    }
    private val recordButton = ActionButton(R.drawable.ic_mic, R.string.action_record).apply {
        setOnClickListener { listener.onRecordToggle() }
    }
    private val saveButton = ActionButton(R.drawable.ic_save, R.string.action_save).apply {
        setOnClickListener { listener.onSave() }
    }

    // While recording the pill shows: [Stop] [Reset] [live level bars] instead of the three actions.
    private val stopButton = ActionButton(R.drawable.ic_stop, R.string.action_stop).apply {
        set(R.drawable.ic_stop, R.string.action_stop, active = true)
        setOnClickListener { listener.onRecordToggle() }
    }
    private val resetButton = ActionButton(R.drawable.ic_refresh, R.string.action_reset).apply {
        setOnClickListener { listener.onRecordReset() }
    }
    private val visualizer = VisualizerView(context)
    private val recordingRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
        addView(stopButton)
        addView(resetButton)
        addView(visualizer, LinearLayout.LayoutParams(dp(150f).toInt(), dp(56f).toInt()).apply {
            marginStart = dp(4f).toInt()
            marginEnd = dp(8f).toInt()
        })
    }

    private val pill = LinearLayout(context).apply {
        isClickable = true // same: gaps between the buttons must not dismiss
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(36f)
            setColor(context.getColor(R.color.capture_surface))
        }
        setPadding(dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt(), dp(10f).toInt())
        addView(editButton)
        addView(recordButton)
        addView(saveButton)
        addView(recordingRow)
    }

    /** Top-right corner of the framed screenshot: throw the capture away (nothing is saved until Save/Edit). */
    private val deleteButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_delete)
        contentDescription = context.getString(R.string.action_discard)
        val pad = dp(10f).toInt()
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.capture_surface)) },
            null,
        )
        alpha = 0f
        visibility = INVISIBLE
        setOnClickListener { listener.onDismiss() }
    }

    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = INVISIBLE
        alpha = 0f
        addView(
            statusText,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(12f).toInt() },
        )
        addView(pill)
    }

    private var frameCornerRadius = 0f
    private var shown = false

    init {
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(frame, LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START))
        addView(flash, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(deleteButton, LayoutParams(dp(DELETE_DP).toInt(), dp(DELETE_DP).toInt(), Gravity.TOP or Gravity.START))
        addView(message, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        addView(
            controls,
            // MATCH_PARENT width: with WRAP_CONTENT the pill got squeezed to the status text's width.
            LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = dp(28f).toInt()
            },
        )

        // The session's content frame is wrap_content high; claim the whole screen explicitly.
        frame.pivotX = screenW / 2f
        frame.pivotY = screenH / 2f

        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.navigationBars())
            bottomInset = bars.bottom
            (controls.layoutParams as LayoutParams).bottomMargin = dp(28f).toInt() + bottomInset
            controls.requestLayout()
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY))
    }

    // --- Public API --------------------------------------------------------------------------

    /** Resets to the empty "waiting for screenshot" state: just a gently dimmed screen. */
    fun prepare() {
        shown = false
        frame.visibility = INVISIBLE
        controls.visibility = INVISIBLE
        controls.alpha = 0f
        deleteButton.alpha = 0f
        deleteButton.visibility = INVISIBLE
        message.visibility = GONE
        borderView.alpha = 0f
        scrim.alpha = 0f
        setNoteState(NoteState.NONE)
        setBusy(false)
        if (ValueAnimator.areAnimatorsEnabled()) {
            ObjectAnimator.ofFloat(scrim, ALPHA, 0f, SCRIM_ALPHA_WAITING).setDuration(160).start()
        } else {
            scrim.alpha = SCRIM_ALPHA_WAITING
        }
    }

    /** Plays the capture animation: full-bleed screenshot -> zooms out into the white frame. */
    fun showScreenshot(bitmap: Bitmap) {
        if (shown) return
        shown = true
        message.visibility = GONE
        imageView.setImageBitmap(bitmap)

        val scale = targetScale()
        val translationY = targetTranslationY()
        frameCornerRadius = dp(CORNER_DP) / scale
        borderView.configure(strokePx = dp(BORDER_DP) / scale, radiusPx = frameCornerRadius)
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, frameCornerRadius)
            }
        }
        frame.clipToOutline = true

        frame.scaleX = 1f
        frame.scaleY = 1f
        frame.translationY = 0f
        frame.visibility = VISIBLE
        controls.visibility = VISIBLE

        // Pin the delete button to the top-right corner of the scaled-down frame.
        val frameW = screenW * scale
        val frameTop = screenH / 2f + translationY - screenH * scale / 2f
        deleteButton.x = (screenW - frameW) / 2f + frameW - dp(DELETE_DP) - dp(12f)
        deleteButton.y = frameTop + dp(12f)
        deleteButton.visibility = VISIBLE

        if (!ValueAnimator.areAnimatorsEnabled()) {
            deleteButton.alpha = 1f
            frame.scaleX = scale
            frame.scaleY = scale
            frame.translationY = translationY
            borderView.alpha = 1f
            scrim.alpha = SCRIM_ALPHA_FINAL
            controls.alpha = 1f
            return
        }

        val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        val shutter = ObjectAnimator.ofFloat(flash, ALPHA, 0.85f, 0f).setDuration(170)

        val zoomOut = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(frame, SCALE_X, 1f, scale),
                ObjectAnimator.ofFloat(frame, SCALE_Y, 1f, scale),
                ObjectAnimator.ofFloat(frame, TRANSLATION_Y, 0f, translationY),
                ObjectAnimator.ofFloat(borderView, ALPHA, 0f, 1f).apply { startDelay = 60 },
                ObjectAnimator.ofFloat(scrim, ALPHA, scrim.alpha, SCRIM_ALPHA_FINAL),
            )
            duration = 440
            interpolator = emphasizedDecelerate
        }

        val actionsIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(controls, ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(deleteButton, ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(controls, TRANSLATION_Y, dp(28f), 0f),
            )
            duration = 260
            startDelay = 220
            interpolator = emphasizedDecelerate
        }

        AnimatorSet().apply {
            playTogether(listOf<Animator>(shutter, zoomOut, actionsIn))
            start()
        }
    }

    /** No screenshot could be taken (setting off, secure screen...): explain, tap anywhere to close. */
    fun showUnavailable() {
        if (shown) return
        message.setText(R.string.msg_screenshot_unavailable)
        message.visibility = VISIBLE
        scrim.alpha = SCRIM_ALPHA_FINAL
    }

    fun setNoteState(state: NoteState, durationMs: Long = 0L) {
        val recording = state == NoteState.RECORDING
        recordingRow.visibility = if (recording) VISIBLE else GONE
        editButton.visibility = if (recording) GONE else VISIBLE
        recordButton.visibility = if (recording) GONE else VISIBLE
        saveButton.visibility = if (recording) GONE else VISIBLE
        when (state) {
            NoteState.NONE -> {
                recordButton.set(R.drawable.ic_mic, R.string.action_record, active = false)
                statusText.visibility = GONE
            }
            NoteState.RECORDING -> {
                visualizer.clear()
                statusText.text = context.getString(R.string.status_recording, formatDuration(durationMs))
                statusText.visibility = VISIBLE
            }
            NoteState.RECORDED -> {
                recordButton.set(R.drawable.ic_mic, R.string.action_rerecord, active = false)
                statusText.text = context.getString(R.string.status_note_attached, formatDuration(durationMs))
                statusText.visibility = VISIBLE
            }
        }
    }

    /** Live feedback while recording: timer text plus the level bars. */
    fun setRecordingProgress(durationMs: Long, amplitude: Int) {
        statusText.text = context.getString(R.string.status_recording, formatDuration(durationMs))
        visualizer.push((amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f))
    }

    fun setBusy(busy: Boolean) {
        controls.alpha = if (busy) 0.5f else if (controls.visibility == VISIBLE && shown) 1f else controls.alpha
        editButton.isEnabled = !busy
        recordButton.isEnabled = !busy
        saveButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        deleteButton.isEnabled = !busy
        resetButton.isEnabled = !busy
    }

    // --- Geometry ----------------------------------------------------------------------------

    private fun controlsReservePx(): Float = dp(CONTROLS_RESERVE_DP) + bottomInset
    private fun topMarginPx(): Float = dp(TOP_MARGIN_DP)

    /** Largest scale (capped) at which the framed screenshot still clears the action pill. */
    private fun targetScale(): Float {
        val available = screenH - controlsReservePx() - topMarginPx()
        return (available / screenH).coerceIn(0.4f, MAX_SCALE)
    }

    /** Vertical shift that centres the scaled frame in the space above the pill. */
    private fun targetTranslationY(): Float {
        val available = screenH - controlsReservePx() - topMarginPx()
        val centreY = topMarginPx() + available / 2f
        return centreY - screenH / 2f
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // --- Parts -------------------------------------------------------------------------------

    /** Draws the white border inside the frame's bounds (so it scales down with the frame). */
    private class BorderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = context.getColor(R.color.capture_border)
        }
        private val rect = RectF()
        private var radius = 0f

        fun configure(strokePx: Float, radiusPx: Float) {
            paint.strokeWidth = strokePx
            radius = radiusPx
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val half = paint.strokeWidth / 2f
            rect.set(half, half, width - half, height - half)
            canvas.drawRoundRect(rect, (radius - half).coerceAtLeast(0f), (radius - half).coerceAtLeast(0f), paint)
        }
    }

    /** Scrolling mic-level bars: newest level on the right. */
    private class VisualizerView(context: Context) : View(context) {
        private val levels = FloatArray(BARS)
        private var head = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.capture_label)
            strokeCap = Paint.Cap.ROUND
        }

        fun push(level: Float) {
            levels[head] = kotlin.math.sqrt(level) // sqrt: speech sits low on a linear scale
            head = (head + 1) % BARS
            invalidate()
        }

        fun clear() {
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val slot = width / BARS.toFloat()
            paint.strokeWidth = slot * 0.5f
            val cy = height / 2f
            for (i in 0 until BARS) {
                val h = maxOf(levels[(head + i) % BARS] * height, paint.strokeWidth)
                val x = slot * (i + 0.5f)
                canvas.drawLine(x, cy - h / 2f + paint.strokeWidth / 2f, x, cy + h / 2f - paint.strokeWidth / 2f, paint)
            }
        }

        private companion object {
            const val BARS = 28
        }
    }

    /** Round icon button with a caption underneath. */
    private inner class ActionButton(
        icon: Int,
        label: Int,
    ) : LinearLayout(context) {

        private val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.capture_button))
        }
        private val circle = FrameLayout(context)
        private val iconView = ImageView(context)
        private val labelView = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)

            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            circle.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), fill, mask)
            val iconSize = dp(24f).toInt()
            circle.addView(iconView, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            val circleSize = dp(56f).toInt()
            addView(circle, LayoutParams(circleSize, circleSize))

            labelView.setTextColor(context.getColor(R.color.capture_label))
            labelView.textSize = 12f
            labelView.gravity = Gravity.CENTER
            addView(labelView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6f).toInt() })

            set(icon, label, active = false)
        }

        fun set(icon: Int, label: Int, active: Boolean) {
            iconView.setImageResource(icon)
            labelView.setText(label)
            contentDescription = context.getString(label)
            fill.setColor(context.getColor(if (active) R.color.capture_button_active else R.color.capture_button))
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.6f
        }
    }

    private companion object {
        const val DELETE_DP = 44f
        const val BORDER_DP = 6f
        const val CORNER_DP = 20f
        const val TOP_MARGIN_DP = 56f
        const val CONTROLS_RESERVE_DP = 176f
        const val MAX_SCALE = 0.66f
        const val SCRIM_ALPHA_WAITING = 0.25f
        const val SCRIM_ALPHA_FINAL = 0.72f
        const val MAX_AMPLITUDE = 32767f
    }
}
