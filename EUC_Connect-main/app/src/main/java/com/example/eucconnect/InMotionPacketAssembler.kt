package com.example.eucconnect

import java.io.ByteArrayOutputStream

class InMotionPacketAssembler {
    private val buffer = ByteArrayOutputStream()
    private var previous = 0
    private var collecting = false

    fun add(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        for (rawByte in bytes) {
            val value = rawByte.toInt() and 0xFF

            if (!collecting) {
                if (previous == 0xAA && value == 0xAA) {
                    buffer.reset()
                    buffer.write(0xAA)
                    buffer.write(0xAA)
                    collecting = true
                }
                previous = value
                continue
            }

            buffer.write(value)
            if (previous == 0x55 && value == 0x55) {
                packets.add(buffer.toByteArray())
                buffer.reset()
                collecting = false
                previous = 0
                continue
            }

            if (buffer.size() > 300) {
                buffer.reset()
                collecting = false
            }

            previous = value
        }

        return packets
    }

    fun reset() {
        buffer.reset()
        previous = 0
        collecting = false
    }
}
