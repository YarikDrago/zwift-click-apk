package app.zwiftclick.emulator

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class GearLayoutEditor(
    private val pad: GearPad,
    private val save: (GearLayout) -> Unit
) {
    private val context = pad.context
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var drag: Drag? = null
    private var resizeDialog: AlertDialog? = null

    init {
        for (choice in ClickButton.entries) attachDrag(choice)
    }

    fun cancelEdit() {
        finishDrag(commit = false)
        val dialog = resizeDialog
        resizeDialog = null
        dialog?.dismiss()
    }

    fun resize(choice: ClickButton) {
        if (pad.width == 0 || pad.height == 0 || resizeDialog != null) return
        val original = pad.arrangement
        val bounds = original[choice]
        var draft = original
        val minimumWidth = minimumPercent(pad.width, bounds.width)
        val minimumHeight = minimumPercent(pad.height, bounds.height)
        val labelWidth = TextView(context)
        val labelHeight = TextView(context)
        val widthSlider = SeekBar(context).apply {
            contentDescription = context.getString(R.string.adjust_width)
            max = 100 - minimumWidth
            progress = bounds.width - minimumWidth
        }
        val heightSlider = SeekBar(context).apply {
            contentDescription = context.getString(R.string.adjust_height)
            max = 100 - minimumHeight
            progress = bounds.height - minimumHeight
        }
        val dialogPadding = (24 * context.resources.displayMetrics.density).roundToInt()
        val inputs = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialogPadding, dialogPadding / 2, dialogPadding, 0)
            addView(labelWidth)
            addView(widthSlider)
            addView(labelHeight)
            addView(heightSlider)
        }

        fun updatePreview() {
            val resized = draft[choice]
            labelWidth.text = context.getString(R.string.adjust_size_value, widthSlider.contentDescription, resized.width)
            labelHeight.text = context.getString(R.string.adjust_size_value, heightSlider.contentDescription, resized.height)
            pad.arrangement = draft
        }

        val resizeFromSliders = {
            draft = original.resize(choice, minimumWidth + widthSlider.progress, minimumHeight + heightSlider.progress)
            updatePreview()
        }
        widthSlider.onUserChange(resizeFromSliders)
        heightSlider.onUserChange(resizeFromSliders)
        updatePreview()

        var confirmed = false
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.adjust_resize, pad.button(choice).text))
            .setView(ScrollView(context).apply { addView(inputs) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { pressedDialog, _ ->
                if (resizeDialog === pressedDialog) {
                    confirmed = true
                    save(draft)
                }
            }
            .create()
        dialog.setOnDismissListener {
            if (resizeDialog === dialog) {
                resizeDialog = null
                if (!confirmed) pad.arrangement = original
            }
        }
        resizeDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawable(GlassUi.dialog(context))
    }

    @SuppressLint("ClickableViewAccessibility") // A stationary tap delegates to the button's performClick().
    private fun attachDrag(choice: ClickButton) {
        pad.button(choice).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = Drag(choice, pad.arrangement, event.rawX, event.rawY)
                    view.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> drag?.let { move(it, event) }
                MotionEvent.ACTION_UP -> {
                    val current = drag
                    if (current != null) {
                        move(current, event)
                        finishDrag(commit = true)
                        if (!current.moving) view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishDrag(commit = false)
            }
            true
        }
    }

    private fun move(drag: Drag, event: MotionEvent) {
        val dx = event.rawX - drag.startX
        val dy = event.rawY - drag.startY
        if (!drag.moving && maxOf(abs(dx), abs(dy)) <= touchSlop) return
        if (pad.width == 0 || pad.height == 0) return
        drag.moving = true
        pad.button(drag.choice).isPressed = false
        val initial = drag.original[drag.choice]
        val x = initial.left + (dx * 100 / pad.width).roundToInt()
        val y = initial.top + (dy * 100 / pad.height).roundToInt()
        pad.arrangement = pad.arrangement.move(drag.choice, x, y)
    }

    private fun finishDrag(commit: Boolean) {
        val current = drag ?: return
        drag = null
        pad.button(current.choice).isPressed = false
        if (!commit) pad.arrangement = current.original
        if (commit && pad.arrangement != current.original) save(pad.arrangement)
    }

    private fun minimumPercent(extent: Int, current: Int): Int {
        val target = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics)
        return ceil(target / extent * 100).toInt().coerceIn(1, current)
    }

    private fun SeekBar.onUserChange(change: () -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) change()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private data class Drag(
        val choice: ClickButton,
        val original: GearLayout,
        val startX: Float,
        val startY: Float,
        var moving: Boolean = false
    )
}
