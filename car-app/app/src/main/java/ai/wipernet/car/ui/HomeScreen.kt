package ai.wipernet.car.ui

import ai.wipernet.car.R
import ai.wipernet.car.settings.Mode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(mode: Mode, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(WiperNetColors.background).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            if (mode == Mode.DEMO) {
                Text(
                    stringResource(R.string.home_demo_badge),
                    color = WiperNetColors.demo,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .border(1.dp, WiperNetColors.demo, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
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
                Box(Modifier.size(18.dp).background(WiperNetColors.ok, CircleShape))
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
    }
}
