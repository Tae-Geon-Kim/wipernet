package ai.wipernet.car.core

import kotlin.math.abs

object UpcomingUnderpassDetector {
    private const val TBT_PREFIX = "TURN_ENTER_UNDER_"
    private const val LOOKAHEAD_M = 2000.0
    // Underpass coordinates are the middle of the underpass, not the entrance.
    private const val CENTER_SLACK_M = 500.0
    private const val CONE_DEG = 30.0

    fun detect(pos: Position, tbts: List<Tbt>, guiding: Boolean, underpasses: List<UnderpassRisk>): UpcomingUnderpass? {
        if (guiding) {
            val c = routeDistanceToUnderpass(tbts)?.takeIf { it <= LOOKAHEAD_M } ?: return null
            return underpasses
                .map { it to distanceM(pos.lat, pos.lng, it.lat, it.lng) }
                .filter { it.second <= c + CENTER_SLACK_M }
                .minByOrNull { abs(it.second - c) }
                ?.let { UpcomingUnderpass(it.first, c.toInt(), onRoute = true) }
        }
        return underpasses
            .map { it to distanceM(pos.lat, pos.lng, it.lat, it.lng) }
            .filter { (u, d) ->
                d <= LOOKAHEAD_M &&
                    angularOffsetDeg(bearingDeg(pos.lat, pos.lng, u.lat, u.lng), pos.headingDeg) <= CONE_DEG
            }
            .minByOrNull { it.second }
            ?.let { UpcomingUnderpass(it.first, Math.round(it.second).toInt(), onRoute = false) }
    }

    private fun routeDistanceToUnderpass(tbts: List<Tbt>): Double? {
        var sum = 0.0
        for (t in tbts) {
            sum += t.distanceM
            if (t.type.startsWith(TBT_PREFIX)) return sum
        }
        return null
    }
}
