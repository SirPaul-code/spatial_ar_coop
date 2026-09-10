package com.sirpaul.showme

import org.junit.Assert.*
import org.junit.Test

class VideoFrameStampTest {
    @Test fun identitiesRoundTripIncludingUnsignedHighBitAndEpochWrap() {
        for (id in listOf(1L, 255L, 256L, 65536L, 0x7fffffffL, 0x80000000L, 0xffffffffL)) {
            assertEquals(id to 3, VideoFrameStamp.decode(VideoFrameStamp.bytes(id,259)))
        }
    }
    @Test fun everySingleBitCorruptionIsRejected() {
        val original=VideoFrameStamp.bytes(891234L,12)
        for(bit in 0 until 64) {
            val bytes=original.copyOf()
            bytes[bit/8]=(bytes[bit/8].toInt() xor (1 shl(bit%8))).toByte()
            assertNull(VideoFrameStamp.decode(bytes))
        }
    }
    @Test fun browserAndAndroidUseTheSameBinaryLayout() {
        val bytes=VideoFrameStamp.bytes(0x12345678L,0x42)
        assertEquals(0xa7,bytes[0].toInt() and 255)
        assertEquals(0x12,bytes[1].toInt() and 255)
        assertEquals(0x78,bytes[4].toInt() and 255)
        assertEquals(0x42,bytes[5].toInt() and 255)
        assertTrue(VideoFrameStamp.bit(bytes,0))
        assertFalse(VideoFrameStamp.bit(bytes,1))
    }
}
