package com.offline.pocketagent.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfSandboxProtocolTest {
    @Test fun pixelTransportHasAnExactBoundedByteCount() {
        assertEquals(4, PdfSandboxProtocol.pixelBytes(1, 1, 1))
        assertEquals(16_777_216, PdfSandboxProtocol.pixelBytes(2_048, 2_048, 2_048))
        assertEquals(4_000, PdfSandboxProtocol.pixelBytes(100, 10, 1_024))
    }

    @Test fun aCompromisedRendererCannotRequestAnOversizedHostBitmap() {
        val dimensions = listOf(
            Triple(0, 1, 2_048), Triple(1, -1, 2_048),
            Triple(2_049, 1, 2_048), Triple(Int.MAX_VALUE, Int.MAX_VALUE, 2_048),
            Triple(100, 100, 99), Triple(1, 1, 0), Triple(1, 1, Int.MAX_VALUE),
        )
        for ((width, height, edge) in dimensions) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfSandboxProtocol.pixelBytes(width, height, edge)
            }
        }
    }
}
