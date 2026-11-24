package app.zwiftclick.emulator

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import kotlin.math.roundToInt

internal object GlassUi {
    val text = 0xFFF3F8FF.toInt()
    val secondaryText = 0xFFB8CBDD.toInt()

    enum class Tone(val colors: IntArray, val rim: Int) {
        NEUTRAL(intArrayOf(0x554B6989, 0x43354C69, 0x4C18283E), 0x497F9EBE),
        BLUE(intArrayOf(0xD23377A6.toInt(), 0xD5255B85.toInt(), 0xDF153E67.toInt()), 0x8DA5DDFE.toInt()),
        GREEN(intArrayOf(0xF329996F.toInt(), 0xED17664F.toInt(), 0xF2114038.toInt()), 0x93A6F2D5.toInt()),
        RED(intArrayOf(0xF3B84958.toInt(), 0xED853144.toInt(), 0xF24D2031.toInt()), 0x93FFACAF.toInt())
    }

    fun styleButton(button: Button, tone: Tone, gear: Boolean = false) {
        val context = button.context
        val radius = dp(context, if (gear) 22 else 16)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val plate = StateListDrawable().apply {
            addState(disabled, surface(tone.colors.map { fade(it, 0.56f) }.toIntArray(), fade(tone.rim, 0.65f), radius, context))
            addState(intArrayOf(), surface(tone.colors, tone.rim, radius, context))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(Color.WHITE)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(0x36FFFFFF), plate, mask)
        button.backgroundTintList = null
        button.background = InsetDrawable(ripple, dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
        button.stateListAnimator = null
        button.setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(0xBFE4EFFA.toInt(), text)))
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.isAllCaps = false
        val padding = if (gear) 0 else dp(context, 12)
        button.setPadding(padding, 0, padding, 0)
    }

    fun panel(context: Context): Drawable = surface(
        intArrayOf(0x5A2D4666, 0x64202F46, 0x77131E32), 0x417B9AB9, dp(context, 19), context
    )

    fun dialog(context: Context): Drawable = InsetDrawable(
        surface(
            intArrayOf(0xFC2B435B.toInt(), 0xFC203348.toInt(), 0xFC18273A.toInt()),
            0x647C9BB8, dp(context, 22), context
        ),
        dp(context, 12)
    )

    private fun surface(colors: IntArray, rim: Int, radius: Int, context: Context) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(context, 1), rim)
        }

    private fun fade(color: Int, fraction: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * fraction).roundToInt() shl 24)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

/** The soft color fields stay fixed behind the translucent controls as screens change. */
internal class GlassBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layers: List<Shader> = emptyList()

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) {
            layers = emptyList()
            return
        }
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val extent = maxOf(width, height)
        layers = listOf(
            LinearGradient(0f, 0f, width * 0.7f, height, 0xFF081220.toInt(), 0xFF111B31.toInt(), Shader.TileMode.CLAMP),
            glow(width * 0.92f, height * 0.12f, extent * 0.55f, 0x8838779D.toInt()),
            glow(width * 0.02f, height * 0.55f, extent * 0.42f, 0x714B437B),
            glow(width * 0.91f, height * 1.03f, extent * 0.47f, 0x79317379)
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        for (layer in layers) {
            paint.shader = layer
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Opacity is no longer used by the framework")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private fun glow(x: Float, y: Float, radius: Float, color: Int) = RadialGradient(
        x, y, radius, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP
    )
}
