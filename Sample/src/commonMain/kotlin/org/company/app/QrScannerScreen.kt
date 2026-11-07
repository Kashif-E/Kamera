package org.company.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kashif.qrscannerplugin.BarcodeFormat
import com.kashif.qrscannerplugin.QRScannerPlugin
import com.kashif.qrscannerplugin.rememberQRScannerPlugin
import org.company.app.designsystem.Eyebrow
import org.company.app.designsystem.PrimaryButton
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.ScanReticle
import org.company.app.designsystem.SecondaryButton
import org.company.app.designsystem.Sheet
import org.company.app.designsystem.SheetHeader
import org.company.app.designsystem.SheetHeaderEmphasis
import org.company.app.designsystem.SheetMeta

/** The Sample's QR scanner: the five most recent distinct codes, the newest in the QR mode's sheet. */
internal class QrScannerFeature(val plugin: QRScannerPlugin) {
    /** The formats the scanner reads, as the QR mode lists them: "QR · Data Matrix". */
    val formatsLine: String get() = plugin.config.formats.joinToString(" · ") { it.displayName() }

    /** What the QR mode asks the user to hold up: a QR code, or any barcode when it reads others. */
    val hint: String
        get() = if (plugin.config.formats == setOf(BarcodeFormat.QR_CODE)) {
            "Point at a QR code"
        } else {
            "Point at a QR code or barcode"
        }
}

/** A code the scanner read: its text and its format's name. */
internal data class ScannedCode(val value: String, val format: String)

@Composable
internal fun rememberQrScannerFeature(): QrScannerFeature {
    val plugin = rememberQRScannerPlugin()
    return remember(plugin) { QrScannerFeature(plugin) }
}

/** The five most recent distinct codes, newest first. */
@Composable
internal fun rememberQrCodes(qrScanner: QrScannerFeature): List<ScannedCode> {
    var qrCodes by remember { mutableStateOf(listOf<ScannedCode>()) }
    LaunchedEffect(qrScanner) {
        qrScanner.plugin.results.collect { barcode ->
            val value = barcode.rawValue ?: return@collect
            if (qrCodes.none { it.value == value }) {
                qrCodes = (listOf(ScannedCode(value, barcode.format.displayName())) + qrCodes).take(MAX_CODES)
            }
        }
    }
    return qrCodes
}

@Composable
internal fun QrScanningEffect(qrScanner: QrScannerFeature, isQRScanningEnabled: Boolean) {
    LaunchedEffect(isQRScanningEnabled) {
        if (isQRScanningEnabled) qrScanner.plugin.resume() else qrScanner.plugin.pause()
    }
}

/** The QR mode's preview overlay: the reticle, what to point at, and the formats read. */
@Composable
internal fun QrOverlay(qrScanner: QrScannerFeature, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SampleTheme.spacing.lg),
    ) {
        ScanReticle()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = qrScanner.hint, style = SampleTheme.typography.bodySmall, color = SampleTheme.colors.ink)
            Text(
                text = qrScanner.formatsLine,
                style = SampleTheme.typography.caption,
                color = SampleTheme.colors.inkOnPreview,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The QR mode's result sheet: the newest code with its format, the earlier ones under it, Copy, and
 * Open link when the code is a web address.
 */
@Composable
internal fun QrResultSheet(
    codes: List<ScannedCode>,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Sheet(modifier = modifier) {
        val latest = codes.firstOrNull()
        if (latest == null) {
            SheetHeader(title = "No code yet", emphasis = SheetHeaderEmphasis.Eyebrow)
            Text(
                text = "A code shows here as soon as it is read.",
                style = SampleTheme.typography.body,
                color = SampleTheme.colors.inkSecondary,
            )
            return@Sheet
        }
        SheetHeader(title = latest.format, emphasis = SheetHeaderEmphasis.Eyebrow) {
            if (codes.size > 1) SheetMeta("newest of ${codes.size}")
        }
        Text(text = latest.value, style = SampleTheme.typography.value, color = SampleTheme.colors.ink)
        codes.drop(1).forEach { earlier ->
            Row(horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.sm)) {
                Eyebrow(text = earlier.format)
                Text(
                    text = earlier.value,
                    style = SampleTheme.typography.caption,
                    color = SampleTheme.colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.md)) {
            if (latest.isLink()) {
                SecondaryButton(text = "Copy", onClick = { onCopy(latest.value) }, modifier = Modifier.weight(1f))
                PrimaryButton(text = "Open link", onClick = { onOpen(latest.value) }, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton(text = "Copy", onClick = { onCopy(latest.value) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Whether the code is a web address the system can open. */
internal fun ScannedCode.isLink(): Boolean = value.startsWith("https://") || value.startsWith("http://")

// QR_CODE reads "QR code", DATA_MATRIX "Data matrix".
private fun BarcodeFormat.displayName(): String = when (this) {
    BarcodeFormat.QR_CODE -> "QR code"
    BarcodeFormat.EAN_13 -> "EAN-13"
    BarcodeFormat.EAN_8 -> "EAN-8"
    BarcodeFormat.UPC_A -> "UPC-A"
    BarcodeFormat.UPC_E -> "UPC-E"
    BarcodeFormat.PDF_417 -> "PDF417"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private const val MAX_CODES = 5
