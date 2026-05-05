package com.example.eucconnect

import java.util.Locale
import java.util.UUID

data class TelemetryPacket(
    val characteristicUuid: UUID,
    val rawHex: String,
    val batteryPercent: Int? = null,
    val remainingRangeKm: Double? = null,
    val speedKmh: Double? = null,
    val voltage: Double? = null,
    val temperatureC: Int? = null
)

object TelemetryDecoder {
    private const val INMOTION_V8F_ESTIMATED_FULL_RANGE_KM = 35.0
    private const val INMOTION_SPEED_FACTOR = 3812.0
    private const val ID_GET_FAST_INFO = 0x0F550113
    private val batteryLevelUuid: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    fun decode(characteristicUuid: UUID, value: ByteArray): TelemetryPacket {
        val fastInfo = decodeInMotionFastInfo(value)
        val batteryPercent = fastInfo?.batteryPercent ?: decodeBatteryPercent(characteristicUuid, value)
        return TelemetryPacket(
            characteristicUuid = characteristicUuid,
            rawHex = value.toHexString(),
            batteryPercent = batteryPercent,
            remainingRangeKm = batteryPercent?.let {
                INMOTION_V8F_ESTIMATED_FULL_RANGE_KM * it / 100.0
            },
            speedKmh = fastInfo?.speedKmh,
            voltage = fastInfo?.voltage,
            temperatureC = fastInfo?.temperatureC
        )
    }

    private fun decodeBatteryPercent(characteristicUuid: UUID, value: ByteArray): Int? {
        if (value.isEmpty()) return null

        if (characteristicUuid == batteryLevelUuid) {
            return value[0].toInt() and 0xFF
        }

        return value
            .asSequence()
            .map { it.toInt() and 0xFF }
            .firstOrNull { it in 0..100 }
    }

    private fun decodeInMotionFastInfo(value: ByteArray): FastInfo? {
        val packet = normalizeInMotionPacket(value) ?: return null
        if (packet.size < 37) return null
        if (readIntLe(packet, 2) != ID_GET_FAST_INFO) return null

        val data = packet.copyOfRange(6, 14)
        val len = packet[14].toInt() and 0xFF
        if (len != 0xFE) return null

        val extendedLength = readIntLe(data, 0)
        val extendedStart = 18
        val extendedEnd = extendedStart + extendedLength
        if (extendedEnd > packet.size - 3 || extendedLength < 35) return null

        val extendedData = packet.copyOfRange(extendedStart, extendedEnd)
        val speedRaw = readIntLe(extendedData, 12) + readIntLe(extendedData, 16)
        val speedKmh = kotlin.math.abs(speedRaw / (INMOTION_SPEED_FACTOR * 2.0) * 3.6)
        val voltageRaw = readIntLe(extendedData, 24)
        val voltage = voltageRaw / 100.0

        return FastInfo(
            speedKmh = speedKmh,
            voltage = voltage,
            batteryPercent = batteryFromVoltage(voltage),
            temperatureC = extendedData[32].toInt()
        )
    }

    private fun normalizeInMotionPacket(value: ByteArray): ByteArray? {
        if (value.size < 20 || value[0] != 0xAA.toByte() || value[1] != 0xAA.toByte()) {
            return null
        }

        val output = ArrayList<Byte>(value.size)
        var escaping = false
        for (index in value.indices) {
            val byte = value[index]
            if (index >= 2 && byte == 0xA5.toByte() && !escaping) {
                escaping = true
                continue
            }
            output.add(byte)
            escaping = false
        }

        val packet = output.toByteArray()
        if (packet.size < 20 || packet[packet.lastIndex] != 0x55.toByte() || packet[packet.lastIndex - 1] != 0x55.toByte()) {
            return null
        }

        val checksumIndex = packet.lastIndex - 2
        val body = packet.copyOfRange(2, checksumIndex)
        val expected = body.fold(0) { sum, byte -> (sum + (byte.toInt() and 0xFF)) and 0xFF }
        val actual = packet[checksumIndex].toInt() and 0xFF
        return if (expected == actual) packet else null
    }

    private fun batteryFromVoltage(voltage: Double): Int {
        val percent = when {
            voltage > 84.0 -> 100.0
            voltage > 68.5 -> ((voltage - 68.5) / 15.5) * 100.0
            else -> 0.0
        }
        return percent.coerceIn(0.0, 100.0).toInt()
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = " ") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
    }

    private data class FastInfo(
        val speedKmh: Double,
        val voltage: Double,
        val batteryPercent: Int,
        val temperatureC: Int
    )
}
