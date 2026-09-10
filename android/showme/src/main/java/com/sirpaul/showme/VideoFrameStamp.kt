package com.sirpaul.showme

/**
 * A CRC-protected frame identity carried INSIDE each encoded video frame, in a
 * footer cropped out by the viewer. It survives codec/network frame drops and
 * avoids guessing the relation between RTP timestamps and the AR clock.
 * Two rows are inverse copies. Bad/ambiguous stamps disable placement, not video.
 */
object VideoFrameStamp {
    const val CELLS = 64
    const val FOOTER_HEIGHT = 32
    fun bytes(id: Long, epoch: Int): ByteArray {
        require(id in 1..0xffffffffL)
        val out = byteArrayOf(0xa7.toByte(), (id shr 24).toByte(), (id shr 16).toByte(),
            (id shr 8).toByte(), id.toByte(), epoch.toByte(), 0, 0)
        val crc = crc16(out, 6)
        out[6] = (crc shr 8).toByte(); out[7] = crc.toByte()
        return out
    }
    fun decode(bytes: ByteArray): Pair<Long, Int>? {
        if (bytes.size != 8 || (bytes[0].toInt() and 255) != 0xa7) return null
        if (crc16(bytes, 6) != ((bytes[6].toInt() and 255) shl 8 or (bytes[7].toInt() and 255))) return null
        var id = 0L
        for (i in 1..4) id = (id shl 8) or (bytes[i].toLong() and 255)
        return if (id == 0L) null else id to (bytes[5].toInt() and 255)
    }
    fun bit(bytes: ByteArray, cell: Int): Boolean =
        ((bytes[cell / 8].toInt() ushr (7 - cell % 8)) and 1) != 0
    private fun crc16(bytes: ByteArray, length: Int): Int {
        var crc = 0xffff
        for (i in 0 until length) {
            crc = crc xor ((bytes[i].toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if ((crc and 0x8000) != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }
}
