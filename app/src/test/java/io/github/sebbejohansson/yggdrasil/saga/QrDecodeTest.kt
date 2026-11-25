package io.github.sebbejohansson.yggdrasil.saga

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    /** A QR code as the camera's brightness plane: dark 0, light 255, rows padded to [stride]. */
    private fun frame(text: String, scale: Int, stride: Int): Triple<ByteArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val margin = 4 * scale
        val w = m.width * scale + 2 * margin
        val h = m.height * scale + 2 * margin
        val out = ByteArray(stride * h) { 0xFF.toByte() }
        for (y in 0 until m.height * scale) for (x in 0 until m.width * scale) {
            if (m.get(x / scale, y / scale)) out[(y + margin) * stride + x + margin] = 0
        }
        return Triple(out, w, h)
    }

    @Test fun readsAPairingLinkFromAPaddedFrame() {
        val link = "yggdrasil://pair?v=1&id=vps&label=VPS&host=vps.tail1234.ts.net&token=abc123"
        val (luma, w, h) = frame(link, 4, 400)
        assertEquals(link, decodeQr(luma, 400, w, h))
    }

    @Test fun anEmptyFrameHoldsNothing() {
        assertNull(decodeQr(ByteArray(200 * 200) { 0x80.toByte() }, 200, 200, 200))
    }
}
