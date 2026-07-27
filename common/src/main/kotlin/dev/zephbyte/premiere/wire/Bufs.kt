package dev.zephbyte.premiere.wire

import io.netty.buffer.ByteBuf

/**
 * Minecraft-compatible primitive encodings on raw netty [ByteBuf], so the wire
 * layer works identically under Fabric (FriendlyByteBuf *is a* ByteBuf) and
 * Paper (plugin-message byte arrays). Same varint/UTF scheme vanilla uses.
 */
object Bufs {
    private const val MAX_UTF_BYTES = 32767

    fun writeVarInt(buf: ByteBuf, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                buf.writeByte(v)
                return
            }
            buf.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    fun readVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.readByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "VarInt too long" }
        }
    }

    fun writeVarLong(buf: ByteBuf, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buf.writeByte(v.toInt())
                return
            }
            buf.writeByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun readVarLong(buf: ByteBuf): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.readByte().toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 70) { "VarLong too long" }
        }
    }

    fun writeUtf(buf: ByteBuf, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_UTF_BYTES) { "String too long: ${bytes.size} bytes" }
        writeVarInt(buf, bytes.size)
        buf.writeBytes(bytes)
    }

    fun readUtf(buf: ByteBuf): String {
        val length = readVarInt(buf)
        require(length in 0..MAX_UTF_BYTES) { "Bad string length: $length" }
        val bytes = ByteArray(length)
        buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
