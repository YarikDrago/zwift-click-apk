package app.zwiftclick.emulator

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Both screens construct the pad in code with their own click action.
internal class GearPad(context: Context, onPress: (ClickButton) -> Unit) : ViewGroup(context) {
    private val minus = createButton(R.string.click_minus, R.string.gear_down_accessibility, GlassUi.Tone.RED)
    private val plus = createButton(R.string.click_plus, R.string.gear_up_accessibility, GlassUi.Tone.GREEN)

    var arrangement = GearLayout.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        // Equal elevation and this child order put + on top for drawing and touch dispatch.
        for (choice in listOf(ClickButton.MINUS, ClickButton.PLUS)) {
            val control = button(choice)
            control.setOnClickListener { onPress(choice) }
            addView(control, LayoutParams(0, 0))
        }
    }

    fun button(choice: ClickButton): Button = if (choice == ClickButton.MINUS) minus else plus

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (choice in ClickButton.entries) {
            val child = button(choice)
            val bounds = arrangement[choice]
            val childWidth = pixels(bounds.right, width) - pixels(bounds.left, width)
            val childHeight = pixels(bounds.bottom, height) - pixels(bounds.top, height)
            val preferredTextSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 64f, resources.displayMetrics
            )
            val textSize = minOf(preferredTextSize, minOf(childWidth, childHeight) * 0.44f).coerceAtLeast(1f)
            if (child.textSize != textSize) child.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (choice in ClickButton.entries) {
            val child = button(choice)
            val bounds = arrangement[choice]
            val x = pixels(bounds.left, width)
            val y = pixels(bounds.top, height)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private fun createButton(label: Int, description: Int, tone: GlassUi.Tone) = Button(context).apply {
        GlassUi.styleButton(this, tone, gear = true)
        setText(label)
        contentDescription = context.getString(description)
        elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
    }

    private fun pixels(percent: Int, extent: Int): Int = (percent * (extent / 100f)).roundToInt()
}
