package ai.wipernet.car.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

private const val M_PER_DEG_LAT = 110_540.0
private const val M_PER_DEG_LNG = 111_320.0

/** OpenStreetMap 슬리피맵 타일(Web Mercator). 경계는 도 단위. */
data class MapTile(val z: Int, val x: Int, val y: Int) {
    val west: Double get() = lngOf(x)
    val east: Double get() = lngOf(x + 1)
    val north: Double get() = latOf(y)
    val south: Double get() = latOf(y + 1)
    val url: String get() = "https://tile.openstreetmap.org/$z/$x/$y.png"

    private fun lngOf(tx: Int) = tx.toDouble() / (1 shl z) * 360.0 - 180.0
    private fun latOf(ty: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * ty / (1 shl z)))))
}

private fun tileX(lng: Double, zoom: Int) = floor((lng + 180.0) / 360.0 * (1 shl zoom)).toInt()

private fun tileY(lat: Double, zoom: Int): Int {
    val r = Math.toRadians(lat)
    return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl zoom)).toInt()
}

/** 중심에서 반경 radiusM 원의 바깥 사각형과 겹치는 타일 전부. */
fun tilesAround(lat: Double, lng: Double, radiusM: Double, zoom: Int): List<MapTile> {
    val dLat = radiusM / M_PER_DEG_LAT
    val dLng = radiusM / (M_PER_DEG_LNG * cos(Math.toRadians(lat)))
    val x0 = tileX(lng - dLng, zoom)
    val x1 = tileX(lng + dLng, zoom)
    val y0 = tileY(lat + dLat, zoom)
    val y1 = tileY(lat - dLat, zoom)
    return (y0..y1).flatMap { y -> (x0..x1).map { x -> MapTile(zoom, x, y) } }
}
