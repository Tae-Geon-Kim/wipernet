package ai.wipernet.car.ui

import ai.wipernet.car.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val items = listOf(
    R.string.consent_collect_label to R.string.consent_collect,
    R.string.consent_purpose_label to R.string.consent_purpose,
    R.string.consent_anon_label to R.string.consent_anon,
    R.string.consent_keep_label to R.string.consent_keep,
    R.string.consent_revoke_label to R.string.consent_revoke,
)

@Composable
fun ConsentScreen(onAgree: () -> Unit) {
    Box(Modifier.fillMaxSize().background(WiperNetColors.background).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.consent_title), color = WiperNetColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            items.forEach { (label, body) ->
                Row {
                    Text(stringResource(label), color = WiperNetColors.muted, fontSize = 20.sp, modifier = Modifier.width(140.dp))
                    Text(stringResource(body), color = WiperNetColors.text, fontSize = 20.sp)
                }
            }
        }
        Button(onClick = onAgree, modifier = Modifier.align(Alignment.BottomEnd).width(200.dp)) {
            Text(stringResource(R.string.consent_agree), fontSize = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}
