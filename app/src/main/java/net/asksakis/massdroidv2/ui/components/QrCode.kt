package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * A QR code for [content]. Only the dark modules are drawn, in black, and the four-module
 * quiet zone a scanner needs is left empty around them, so the caller puts this on white
 * whatever the app theme. Nothing is drawn while the code is being made, or when [content]
 * is too long for a QR code.
 */
@Composable
fun QrCode(content: String, description: String, modifier: Modifier = Modifier) {
    val matrix by produceState<BitMatrix?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) { encodeQr(content) }
    }
    Canvas(modifier = modifier.semantics { this.contentDescription = description }) {
        val modules = matrix ?: return@Canvas
        // Whole pixels per module, so neighbouring modules meet without a hairline between them.
        val cell = floor(size.minDimension / modules.width)
        if (cell < 1f) return@Canvas
        val origin = Offset((size.width - cell * modules.width) / 2f, (size.height - cell * modules.height) / 2f)
        val moduleSize = Size(cell, cell)
        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (modules[x, y]) {
                    drawRect(Color.Black, topLeft = origin + Offset(x * cell, y * cell), size = moduleSize)
                }
            }
        }
    }
}

private fun encodeQr(content: String): BitMatrix? = try {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES
        )
    )
} catch (_: WriterException) {
    null
}

private const val QUIET_ZONE_MODULES = 4
