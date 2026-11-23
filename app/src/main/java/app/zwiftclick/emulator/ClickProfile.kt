package app.zwiftclick.emulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import java.util.UUID

internal object ClickProfile {
    // Keep Zwahoo's advertising identity and use the GATT layout from the Ride capture.
    const val NAME = "Zwift SF2"
    val SERVICE: UUID = UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
    val WRITE: UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
    val INDICATE: UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val RIDE_ON = "RideOn".toByteArray(Charsets.US_ASCII)

    fun advertisement(): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(SERVICE))
        .addManufacturerData(0x094A, byteArrayOf(0x09, 0x42, 0x42))
        .build()

    fun service(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(withSubscription(NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(withSubscription(INDICATE, BluetoothGattCharacteristic.PROPERTY_INDICATE))
        return service
    }

    private fun withSubscription(uuid: UUID, property: Int): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(uuid, property, 0).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

    fun isRideOn(value: ByteArray): Boolean =
        value.size >= RIDE_ON.size && RIDE_ON.indices.all { value[it] == RIDE_ON[it] }
}
