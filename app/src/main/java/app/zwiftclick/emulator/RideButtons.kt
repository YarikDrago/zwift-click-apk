package app.zwiftclick.emulator

import java.io.ByteArrayOutputStream

internal enum class RideButton(val mask: Int) {
    LS1(0x0100),
    LS2(0x0200),
    RS1(0x1000),
    RS2(0x2000),
    LEFT_AUX(0x0400)
}

internal object RideButtons {
    fun frame(pressed: RideButton? = null): ByteArray {
        // The Ride button bitmap is active-low and encoded as a Protobuf uint32.
        var bitmap = (pressed?.mask ?: 0).inv()
        val frame = ByteArrayOutputStream(7)
        frame.write(0x23)
        frame.write(0x08)
        while (bitmap ushr 7 != 0) {
            frame.write((bitmap and 0x7F) or 0x80)
            bitmap = bitmap ushr 7
        }
        frame.write(bitmap)
        return frame.toByteArray()
    }
}
