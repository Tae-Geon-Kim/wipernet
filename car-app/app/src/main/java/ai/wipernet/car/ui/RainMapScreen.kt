package ai.wipernet.car.ui

import ai.wipernet.car.R
import ai.wipernet.car.UiState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import ai.wipernet.car.core.distanceM
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos

private const val RADIUS_M = 3_000.0
private const val CELL_LAT = 0.0045
private const val CELL_LNG = 0.0057
private const val M_PER_DEG_LAT = 110_540.0
private const val M_PER_DEG_LNG = 111_320.0

/** 비 지도(A14). 기어 P에서만 연다. 내 위치 중심 3 km의 비 칸(뷰어와 같은 색 단계), 지하차도 마커(단계 색), 진행 방향. */
@Composable
fun RainMapScreen(ui: UiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(WiperNetColors.background).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = WiperNetColors.text, modifier = Modifier.size(36.dp))
            }
            Text(stringResource(R.string.rain_map_title), color = WiperNetColors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.rain_map_legend), color = WiperNetColors.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 32.dp))
        }
        val me = ui.position ?: return@Column
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val center = Offset(size.width / 2, size.height / 2)
                val pxPerM = (minOf(size.width, size.height) / 2 / RADIUS_M).toFloat()
                val cosLat = cos(Math.toRadians(me.lat))
                fun at(lat: Double, lng: Double) = Offset(
                    center.x + ((lng - me.lng) * cosLat * M_PER_DEG_LNG).toFloat() * pxPerM,
                    center.y - ((lat - me.lat) * M_PER_DEG_LAT).toFloat() * pxPerM,
                )
                val cellW = (CELL_LNG * cosLat * M_PER_DEG_LNG).toFloat() * pxPerM
                val cellH = (CELL_LAT * M_PER_DEG_LAT).toFloat() * pxPerM
                fun inside(lat: Double, lng: Double) = distanceM(me.lat, me.lng, lat, lng) <= RADIUS_M
                for (c in ui.rainCells.filter { inside(it.lat, it.lng) }) {
                    val p = at(c.lat, c.lng)
                    drawRect(rainColor(c.mmph), Offset(p.x - cellW / 2, p.y - cellH / 2), Size(cellW, cellH), alpha = 0.85f)
                }
                drawCircle(WiperNetColors.line, radius = (RADIUS_M * pxPerM).toFloat(), center = center, style = Stroke(2f))
                for (u in ui.underpasses.filter { inside(it.lat, it.lng) }) {
                    val p = at(u.lat, u.lng)
                    drawCircle(Color.Black, radius = 18f, center = p)
                    drawCircle(levelColor(u.level), radius = 14f, center = p)
                }
                rotate(me.headingDeg.toFloat(), center) {
                    val arrow = Path().apply {
                        moveTo(center.x, center.y - 28f)
                        lineTo(center.x + 18f, center.y + 18f)
                        lineTo(center.x, center.y + 8f)
                        lineTo(center.x - 18f, center.y + 18f)
                        close()
                    }
                    drawPath(arrow, Color.White)
                }
            }
        }
    }
}

private fun rainColor(mmph: Double): Color = when {
    mmph < 10 -> Color(0xFFCFE8FB)
    mmph < 30 -> Color(0xFF6FB1F0)
    mmph < 50 -> Color(0xFF2B4FB5)
    else -> Color(0xFF7B3FB8)
}
