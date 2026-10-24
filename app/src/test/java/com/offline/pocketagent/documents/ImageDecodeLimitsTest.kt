package com.offline.pocketagent.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodeLimitsTest {
    @Test fun `rejects extreme header dimensions before allocating a bitmap`() {
        assertTrue(runCatching { ImageDecodeLimits.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 2_048) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ImageDecodeLimits.sampleSize(32_768, 32_768, 2_048) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ImageDecodeLimits.sampleSize(0, 100, 2_048) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `allows Samsung 200MP images while keeping the decoded bitmap bounded`() {
        val sample = ImageDecodeLimits.sampleSize(16_320, 12_240, 2_048)
        assertEquals(8, sample)
        assertTrue((16_320L + sample - 1) / sample <= 2_048)
    }

    @Test fun `rounds sample planning up at a decoder boundary`() {
        assertEquals(4, ImageDecodeLimits.sampleSize(4_097, 100, 2_048))
    }

    @Test fun `rejects a decoder result that ignored the requested bounds`() {
        assertTrue(runCatching { ImageDecodeLimits.validateDecoded(4_096, 2_048, 32_000_000, 2_048) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ImageDecodeLimits.validateDecoded(2_048, 2_048, 100_000_000, 2_048) }.exceptionOrNull() is IllegalArgumentException)
        ImageDecodeLimits.validateDecoded(2_048, 2_048, 16_777_216, 2_048)
    }

    @Test fun `spoofed image MIME cannot send Office or PDF bytes to an image decoder`() {
        assertFalse(ImageDecodeLimits.supportsSignature("%PDF-1.7".toByteArray()))
        assertFalse(ImageDecodeLimits.supportsSignature(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertFalse(ImageDecodeLimits.supportsSignature("GIF89a".toByteArray()))
        assertTrue(ImageDecodeLimits.supportsSignature(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertTrue(ImageDecodeLimits.supportsSignature("RIFFxxxxWEBP".toByteArray()))
    }
}
