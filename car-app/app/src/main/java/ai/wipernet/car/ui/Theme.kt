package ai.wipernet.car.ui

import ai.wipernet.car.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object WiperNetColors {
    val background = Color(0xFF0B0D0F)
    val surface = Color(0xFF12161A)
    val line = Color(0xFF262C32)
    val text = Color(0xFFE8EBE4)
    val muted = Color(0xFF8A9491)
    val accent = Color(0xFF00B8D4)
    val ok = Color(0xFF00E676)
    val demo = Color(0xFFFFAB00)
    val danger = Color(0xFFFF6E40)
}

@Composable
fun WiperNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WiperNetColors.accent,
            onPrimary = Color(0xFF001014),
            background = WiperNetColors.background,
            onBackground = WiperNetColors.text,
            surface = WiperNetColors.surface,
            onSurface = WiperNetColors.text,
            error = WiperNetColors.danger,
        ),
        content = content,
    )
}

@Composable
fun BrandSplash() {
    Box(Modifier.fillMaxSize().background(WiperNetColors.background), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.brand), color = WiperNetColors.text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
    }
}
