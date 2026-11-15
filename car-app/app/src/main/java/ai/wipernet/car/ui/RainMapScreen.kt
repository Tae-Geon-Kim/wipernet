package ai.wipernet.car.ui

import ai.wipernet.car.R
import ai.wipernet.car.UiState
import ai.wipernet.car.core.MapTile
import ai.wipernet.car.core.tilesAround
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
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
private const val TILE_ZOOM = 14

private val tileHttp = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

/** OSM 타일 이용 정책상 User-Agent를 밝힌다. 실패한 타일은 조용히 건너뛴다. */
private fun fetchTile(tile: MapTile): ImageBitmap? = try {
    val req = Request.Builder().url(tile.url).header("User-Agent", "WiperNet/1.0 (contest demo app)").build()
    tileHttp.newCall(req).execute().use { res ->
        if (!res.isSuccessful) null else res.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
} catch (_: IOException) {
    null
}

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
        val centerTile = tilesAround(me.lat, me.lng, 0.0, TILE_ZOOM).single()
        val tiles = remember(centerTile) { tilesAround(me.lat, me.lng, RADIUS_M, TILE_ZOOM) }
        val loaded = remember { mutableStateMapOf<MapTile, ImageBitmap>() }
        LaunchedEffect(tiles) {
            for (t in tiles.filter { it !in loaded }) {
                launch { withContext(Dispatchers.IO) { fetchTile(t) }?.let { loaded[t] = it } }
            }
        }
        val shown = tiles.filter { it in loaded }
        val textMeasurer = rememberTextMeasurer()
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
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, (RADIUS_M * pxPerM).toFloat())) }
                clipPath(circle) {
                    for (t in shown) {
                        val nw = at(t.north, t.west)
                        val se = at(t.south, t.east)
                        val x0 = floor(nw.x).toInt()
                        val y0 = floor(nw.y).toInt()
                        drawImage(
                            loaded.getValue(t),
                            dstOffset = IntOffset(x0, y0),
                            dstSize = IntSize(ceil(se.x).toInt() - x0, ceil(se.y).toInt() - y0),
                        )
                    }
                }
                val cellAlpha = if (shown.isEmpty()) 0.85f else 0.55f
                for (c in ui.rainCells.filter { inside(it.lat, it.lng) }) {
                    val p = at(c.lat, c.lng)
                    drawRect(rainColor(c.mmph), Offset(p.x - cellW / 2, p.y - cellH / 2), Size(cellW, cellH), alpha = cellAlpha)
                }
                drawCircle(WiperNetColors.line, radius = (RADIUS_M * pxPerM).toFloat(), center = center, style = Stroke(2f))
                for (u in ui.underpasses.filter { inside(it.lat, it.lng) }) {
                    val p = at(u.lat, u.lng)
                    drawCircle(Color.Black, radius = 18f, center = p)
                    drawCircle(levelColor(u.level), radius = 14f, center = p)
                }
                for (u in ui.underpasses.filter { inside(it.lat, it.lng) }) {
                    val p = at(u.lat, u.lng)
                    val label = textMeasurer.measure(u.name, TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    val topLeft = Offset(p.x + 26f, p.y - label.size.height / 2f)
                    drawRoundRect(
                        Color(0xB3000000),
                        Offset(topLeft.x - 8f, topLeft.y - 4f),
                        Size(label.size.width + 16f, label.size.height + 8f),
                        CornerRadius(8f),
                    )
                    drawText(label, topLeft = topLeft)
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
            if (shown.isNotEmpty()) {
                Text(
                    stringResource(R.string.rain_map_attribution),
                    color = WiperNetColors.text,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
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
