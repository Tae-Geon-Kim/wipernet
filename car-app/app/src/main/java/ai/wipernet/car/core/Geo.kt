package ai.wipernet.car.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

private const val CELL_LAT = 0.0045
private const val CELL_LNG = 0.0057
private const val EARTH_M = 6371.0 * 1000

internal fun cellOf(lat: Double, lng: Double): Pair<Long, Long> =
    floor(lat / CELL_LAT).toLong() to floor(lng / CELL_LNG).toLong()

internal fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lng2 - lng1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_M * asin(sqrt(a))
}

internal fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lng2 - lng1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) % 360 + 360) % 360
}

internal fun angularOffsetDeg(bearing: Double, heading: Double): Double =
    abs(((bearing - heading + 180) % 360 + 360) % 360 - 180)
