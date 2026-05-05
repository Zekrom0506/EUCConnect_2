package com.example.eucconnect

import java.io.ByteArrayOutputStream

object InMotionProtocol {
    private const val CHANNEL = 5
    private const val DATA_FRAME = 0
    private const val STANDARD_FORMAT = 0

    private const val ID_GET_FAST_INFO = 0x0F550113
    private const val ID_LIGHT = 0x0F55010D
    private const val ID_REMOTE_CONTROL = 0x0F550116
    private const val ID_PIN_CODE = 0x0F550307
    private const val ID_PLAY_SOUND = 0x0F550609

    fun password(password: String = "000000"): ByteArray {
        val safePassword = password.padEnd(6, '0').take(6).encodeToByteArray()
        return frame(
            id = ID_PIN_CODE,
            data = byteArrayOf(
                safePassword[0],
                safePassword[1],
                safePassword[2],
                safePassword[3],
                safePassword[4],
                safePassword[5],
                0,
                0
            )
        )
    }

    fun requestFastTelemetry(): ByteArray {
        return frame(
            id = ID_GET_FAST_INFO,
            data = byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)
        )
    }

    fun setHeadlight(on: Boolean): ByteArray {
        return frame(
            id = ID_LIGHT,
            data = byteArrayOf(if (on) 1 else 0, 0, 0, 0, 0, 0, 0, 0)
        )
    }

    fun setSideLights(on: Boolean): ByteArray {
        return frame(
            id = ID_REMOTE_CONTROL,
            data = byteArrayOf(0xB2.toByte(), 0, 0, 0, if (on) 0x0F else 0x10, 0, 0, 0)
        )
    }

    fun playBell(): ByteArray {
        return frame(
            id = ID_REMOTE_CONTROL,
            data = byteArrayOf(0xB2.toByte(), 0, 0, 0, 0x11, 0, 0, 0)
        )
    }

    fun playLegacyBell(): ByteArray {
        return frame(
            id = ID_PLAY_SOUND,
            data = byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0)
        )
    }

    private fun frame(id: Int, data: ByteArray, type: Int = DATA_FRAME): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(id and 0xFF)
        payload.write((id shr 8) and 0xFF)
        payload.write((id shr 16) and 0xFF)
        payload.write((id shr 24) and 0xFF)
        payload.write(data)
        payload.write(data.size)
        payload.write(CHANNEL)
        payload.write(STANDARD_FORMAT)
        payload.write(type)

        val payloadBytes = payload.toByteArray()
        val result = ByteArrayOutputStream()
        result.write(0xAA)
        result.write(0xAA)
        result.write(escape(payloadBytes))
        result.write(checksum(payloadBytes).toInt())
        result.write(0x55)
        result.write(0x55)
        return result.toByteArray()
    }

    private fun escape(buffer: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (byte in buffer) {
            if (byte == 0xAA.toByte() || byte == 0x55.toByte() || byte == 0xA5.toByte()) {
                out.write(0xA5)
            }
            out.write(byte.toInt())
        }
        return out.toByteArray()
    }

    private fun checksum(buffer: ByteArray): Byte {
        var check = 0
        for (byte in buffer) {
            check = (check + (byte.toInt() and 0xFF)) and 0xFF
        }
        return check.toByte()
    }
    // In InmotionProtocol.kt
    fun createLedCommand(mode: Int): ByteArray {
        val command = ByteArray(20)
        command[0] = 0xAA.toByte()
        command[1] = 0xAA.toByte()
        command[2] = 0x13.toByte() // LED Control Command for V8
        command[3] = mode.toByte() // The pattern ID

        // Fill 4-18 with 0
        for (i in 4..18) command[i] = 0x00

        // Calculate Checksum
        var checksum: Int = 0
        for (i in 0..18) {
            checksum += command[i].toInt() and 0xFF
        }
        command[19] = (checksum and 0xFF).toByte()

        return command
    }

}
