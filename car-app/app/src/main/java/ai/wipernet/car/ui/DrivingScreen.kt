package ai.wipernet.car.ui

import ai.wipernet.car.R
import ai.wipernet.car.UiState
import ai.wipernet.car.core.Level
import ai.wipernet.car.settings.Mode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 주행 화면(A13). 평소에는 작은 상태 표시만, 앞쪽 지하차도가 주의 이상일 때만 위쪽 큰 띠. 긴 문장은 쓰지 않는다. */
@Composable
fun DrivingScreen(ui: UiState, onOpenSettings: () -> Unit, onOpenRainMap: () -> Unit, onRestartDemo: () -> Unit) {
    Column(Modifier.fillMaxSize().background(WiperNetColors.background)) {
        val up = ui.upcoming?.takeIf { it.risk.level >= Level.CAUTION }
        if (up != null) {
            Row(
                Modifier.fillMaxWidth().background(levelColor(up.risk.level)).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(levelName(up.risk.level), color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.drive_ahead, up.risk.name, String.format(Locale.US, "%.1f", up.distanceM / 1000.0)),
                    color = Color.Black,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 32.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                if (ui.mode == Mode.DEMO) {
                    Text(
                        stringResource(R.string.home_demo_badge),
                        color = WiperNetColors.demo,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .border(1.dp, WiperNetColors.demo, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                } else {
                    Text(
                        stringResource(if (ui.offline) R.string.drive_offline else R.string.drive_connected),
                        color = if (ui.offline) WiperNetColors.danger else WiperNetColors.muted,
                        fontSize = 18.sp,
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.open_settings),
                        tint = WiperNetColors.muted,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).background(if (ui.offline) WiperNetColors.danger else WiperNetColors.ok, CircleShape))
                    Text(
                        stringResource(R.string.home_watching),
                        color = WiperNetColors.text,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Text(stringResource(R.string.home_body), color = WiperNetColors.muted, fontSize = 22.sp)
                Text(stringResource(R.string.home_close_ok), color = WiperNetColors.text, fontSize = 22.sp)
            }
            Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (ui.gear == "P") BigButton(stringResource(R.string.drive_rain_map), onOpenRainMap)
                if (ui.mode == Mode.DEMO) BigButton(stringResource(R.string.drive_restart_demo), onRestartDemo)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
        Text(text, fontSize = 22.sp, color = WiperNetColors.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

fun levelColor(level: Level): Color = when (level) {
    Level.DANGER -> Color(0xFFE0312D)
    Level.WARNING -> Color(0xFFF08A24)
    Level.CAUTION -> Color(0xFFF2C230)
    Level.NONE -> Color(0xFF8A8F98)
}

@Composable
fun levelName(level: Level): String = when (level) {
    Level.DANGER -> stringResource(R.string.level_danger)
    Level.WARNING -> stringResource(R.string.level_warning)
    Level.CAUTION -> stringResource(R.string.level_caution)
    Level.NONE -> ""
}
