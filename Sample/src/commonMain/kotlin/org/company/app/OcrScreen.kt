package org.company.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.composables.icons.lucide.Type
import com.kashif.ocrplugin.OcrPlugin
import com.kashif.ocrplugin.rememberOcrPlugin

/** The Sample's text recognition: the latest recognized text, shown in a banner. */
internal class OcrFeature(val plugin: OcrPlugin)

@Composable
internal fun rememberOcrFeature(): OcrFeature {
    val plugin = rememberOcrPlugin()
    return remember(plugin) { OcrFeature(plugin) }
}

@Composable
internal fun rememberRecognizedText(ocr: OcrFeature): String? {
    var recognizedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ocr) {
        ocr.plugin.results.collect { recognizedText = it.text }
    }
    return recognizedText
}

@Composable
internal fun OcrRecognitionEffect(ocr: OcrFeature, isOCREnabled: Boolean) {
    LaunchedEffect(ocr, isOCREnabled) {
        if (isOCREnabled) ocr.plugin.resume() else ocr.plugin.pause()
    }
}

@Composable
internal fun OcrBanner(modifier: Modifier = Modifier, text: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = Color(0xFF1A237E).copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Type,
                    contentDescription = null,
                    tint = Color(0xFF82B1FF),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Text Detected",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF82B1FF),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "Less" else "More",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = if (expanded) 10 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
