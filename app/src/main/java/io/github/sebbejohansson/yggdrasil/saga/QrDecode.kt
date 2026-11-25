package io.github.sebbejohansson.yggdrasil.saga

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code out of one camera frame's brightness plane (the Y of YUV),
 * which is what the camera hands over, so no colour conversion is needed.
 * [rowStride] can be wider than [width]: the camera pads its rows. Null when
 * the frame holds no readable code, which is most frames. Apart from the
 * camera so it is tested.
 */
fun decodeQr(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: NotFoundException) {
        null
    } catch (_: com.google.zxing.ChecksumException) {
        null
    } catch (_: com.google.zxing.FormatException) {
        null
    }
}
