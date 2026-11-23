package app.zwiftclick.emulator

internal enum class ClickButton {
    PLUS,
    MINUS
}

internal object ClickButtons {
    fun frame(pressed: ClickButton? = null): ByteArray = byteArrayOf(
        0x37,
        0x08, if (pressed == ClickButton.PLUS) 0 else 1,
        0x10, if (pressed == ClickButton.MINUS) 0 else 1
    )
}
