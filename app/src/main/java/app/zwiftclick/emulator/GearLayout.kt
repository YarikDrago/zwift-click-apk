package app.zwiftclick.emulator

// Positions and sizes are percentages of the area shared by Go and Adjust.
internal data class GearBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val fits: Boolean get() = left in 0..99 && top in 0..99 &&
        width in 1..(100 - left) && height in 1..(100 - top)
}

internal data class GearLayout(val minus: GearBounds, val plus: GearBounds) {
    operator fun get(button: ClickButton): GearBounds = if (button == ClickButton.MINUS) minus else plus

    fun move(button: ClickButton, left: Int, top: Int): GearLayout =
        replace(button, this[button].copy(left = left, top = top))

    fun resize(button: ClickButton, width: Int, height: Int): GearLayout =
        replace(button, this[button].copy(width = width, height = height))

    private fun replace(button: ClickButton, bounds: GearBounds): GearLayout {
        if (bounds.width !in 1..100 || bounds.height !in 1..100) return this
        val clamped = bounds.copy(
            left = bounds.left.coerceIn(0, 100 - bounds.width),
            top = bounds.top.coerceIn(0, 100 - bounds.height)
        )
        return if (button == ClickButton.MINUS) copy(minus = clamped) else copy(plus = clamped)
    }

    fun encode(): String = listOf(minus, plus)
        .flatMap { listOf(it.left, it.top, it.width, it.height) }
        .joinToString(",")

    companion object {
        val DEFAULT = GearLayout(GearBounds(0, 0, 50, 100), GearBounds(50, 0, 50, 100))

        fun decode(value: String?): GearLayout {
            val parts = value?.split(',') ?: return DEFAULT
            if (parts.size != 8) return DEFAULT
            val sizes = parts.map { it.toIntOrNull() ?: return DEFAULT }
            val minus = GearBounds(sizes[0], sizes[1], sizes[2], sizes[3])
            val plus = GearBounds(sizes[4], sizes[5], sizes[6], sizes[7])
            return if (minus.fits && plus.fits) GearLayout(minus, plus) else DEFAULT
        }
    }
}
