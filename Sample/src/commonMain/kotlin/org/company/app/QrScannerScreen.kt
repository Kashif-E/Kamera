package org.company.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanLine
import com.kashif.qrscannerplugin.QRScannerPlugin
import com.kashif.qrscannerplugin.rememberQRScannerPlugin

/** The Sample's QR scanner: the five most recent distinct codes, shown as chips. */
internal class QrScannerFeature(val plugin: QRScannerPlugin)

@Composable
internal fun rememberQrScannerFeature(): QrScannerFeature {
    val plugin = rememberQRScannerPlugin()
    return remember(plugin) { QrScannerFeature(plugin) }
}

@Composable
internal fun rememberQrCodes(qrScanner: QrScannerFeature): List<String> {
    var qrCodes by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(qrScanner) {
        qrScanner.plugin.getQrCodeFlow().collect { qr ->
            if (qr !in qrCodes) {
                qrCodes = (listOf(qr) + qrCodes).take(5)
            }
        }
    }
    return qrCodes
}

@Composable
internal fun QrScanningEffect(qrScanner: QrScannerFeature, isQRScanningEnabled: Boolean) {
    LaunchedEffect(isQRScanningEnabled) {
        try {
            if (isQRScanningEnabled) {
                qrScanner.plugin.startScanning()
            } else {
                qrScanner.plugin.pauseScanning()
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
internal fun QrChipRow(qrCodes: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(qrCodes) { code ->
            Surface(
                color = Color(0xFF4CAF50).copy(alpha = 0.9f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ScanLine,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (code.length > 30) code.take(30) + "..." else code,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
