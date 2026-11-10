package ai.wipernet.car.core

object UpcomingUnderpassDetector {
    private const val TBT_PREFIX = "TURN_ENTER_UNDER_"
    private const val TBT_MATCH_M = 300.0
    private const val LOOKAHEAD_M = 2000.0
    private const val CONE_DEG = 30.0

    fun detect(pos: Position, tbt: Tbt?, underpasses: List<UnderpassRisk>): UpcomingUnderpass? {
        if (tbt != null && tbt.type.startsWith(TBT_PREFIX)) {
            val target = destination(pos.lat, pos.lng, pos.headingDeg, tbt.distanceM.toDouble())
            return underpasses
                .map { it to distanceM(target.lat, target.lng, it.lat, it.lng) }
                .filter { it.second <= TBT_MATCH_M }
                .minByOrNull { it.second }
                ?.let { UpcomingUnderpass(it.first, tbt.distanceM) }
        }
        return underpasses
            .map { it to distanceM(pos.lat, pos.lng, it.lat, it.lng) }
            .filter { (u, d) ->
                d <= LOOKAHEAD_M &&
                    angularOffsetDeg(bearingDeg(pos.lat, pos.lng, u.lat, u.lng), pos.headingDeg) <= CONE_DEG
            }
            .minByOrNull { it.second }
            ?.let { UpcomingUnderpass(it.first, Math.round(it.second).toInt()) }
    }
}
