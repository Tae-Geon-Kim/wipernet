package ai.wipernet.car.ui

import ai.wipernet.car.R
import ai.wipernet.car.settings.Mode
import ai.wipernet.car.settings.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(WiperNetColors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = WiperNetColors.text, modifier = Modifier.size(32.dp))
            }
            Text(stringResource(R.string.settings_title), color = WiperNetColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }

        SectionLabel(R.string.settings_prep_section)
        ToggleRow(R.string.settings_prep_windows, settings.prepCloseWindows) { v -> onChange { it.copy(prepCloseWindows = v) } }
        ToggleRow(R.string.settings_prep_defog, settings.prepDefog) { v -> onChange { it.copy(prepDefog = v) } }
        ToggleRow(R.string.settings_prep_headlights, settings.prepHeadlights) { v -> onChange { it.copy(prepHeadlights = v) } }

        SectionLabel(R.string.settings_privacy_section)
        Text(
            stringResource(R.string.settings_revoke),
            color = WiperNetColors.danger,
            fontSize = 22.sp,
            modifier = Modifier.fillMaxWidth().clickable { confirmRevoke = true }.padding(vertical = 16.dp),
        )

        DevSection(settings, onChange)
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.settings_revoke_confirm_title), fontSize = 26.sp) },
            text = { Text(stringResource(R.string.settings_revoke_confirm_body), fontSize = 20.sp) },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; onChange { it.copy(consented = false) } }) {
                    Text(stringResource(R.string.settings_revoke_confirm_ok), color = WiperNetColors.danger, fontSize = 22.sp)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(R.string.cancel), fontSize = 22.sp) } },
        )
    }
}

@Composable
private fun DevSection(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    var url by rememberSaveable { mutableStateOf(settings.serverUrl) }
    Column(
        Modifier.padding(top = 28.dp).fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = WiperNetColors.line,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_dev_section), color = WiperNetColors.muted, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_mode), color = WiperNetColors.text, fontSize = 22.sp, modifier = Modifier.weight(1f))
            val modes = listOf(Mode.DEMO to R.string.mode_demo, Mode.LIVE to R.string.mode_live)
            SingleChoiceSegmentedButtonRow(Modifier.width(280.dp)) {
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { onChange { it.copy(mode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(stringResource(label), fontSize = 18.sp) }
                }
            }
        }
        if (settings.mode == Mode.LIVE) {
            OutlinedTextField(
                value = url,
                onValueChange = { v -> url = v; onChange { it.copy(serverUrl = v) } },
                label = { Text(stringResource(R.string.settings_server_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: Int) {
    Text(stringResource(text), color = WiperNetColors.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), color = WiperNetColors.text, fontSize = 22.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = WiperNetColors.line)
}
