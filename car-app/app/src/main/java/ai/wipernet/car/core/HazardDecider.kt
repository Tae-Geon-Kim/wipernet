package ai.wipernet.car.core

import ai.wipernet.car.settings.Settings

class HazardDecider(private val clock: () -> Long) {
    private class UnderpassState(
        var announced: Level? = null,
        var announcedAt: Long = 0,
        var lastSeenAt: Long = 0,
        var declined: Level? = null,
        var accepted: Boolean = false,
    )

    private val underpasses = mutableMapOf<String, UnderpassState>()
    private var wasOffline = false
    private var cautionMuted = false
    private var previousUpcomingId: String? = null
    private var prepTier: Double? = null
    private var prepAt = 0L

    fun decide(pos: Position, gear: String, upcoming: UpcomingUnderpass?, hazards: Hazards, settings: Settings): List<Action> {
        if (gear == "P") {
            cautionMuted = false
            underpasses.values.forEach { it.declined = null }
            return emptyList()
        }
        val now = clock()
        val actions = mutableListOf<Action>()
        if (hazards.offline && !wasOffline) actions += Action.ShowOffline
        wasOffline = hazards.offline
        val inRange = upcoming?.takeIf { it.distanceM <= UNDERPASS_RANGE_M }
        // A heading-only match must repeat once, so a brief catch while turning stays quiet.
        if (inRange != null && (inRange.onRoute || inRange.risk.id == previousUpcomingId)) {
            actions += underpassActions(inRange, now)
        }
        previousUpcomingId = inRange?.risk?.id
        prepareForRain(pos, hazards.rainCells, settings, now)?.let { actions += it }
        return actions
    }

    fun onRerouteDeclined(id: String, level: Level) {
        underpasses.getOrPut(id) { UnderpassState() }.declined = level
    }

    fun onRerouteAccepted(id: String) {
        underpasses.getOrPut(id) { UnderpassState() }.accepted = true
    }

    fun onCautionMuted() {
        cautionMuted = true
    }

    private fun underpassActions(upcoming: UpcomingUnderpass, now: Long): List<Action> {
        val risk = upcoming.risk
        val s = underpasses.getOrPut(risk.id) { UnderpassState() }
        val newApproach = now - s.lastSeenAt > APPROACH_GAP_MS
        if (newApproach) s.accepted = false
        s.lastSeenAt = now
        if (s.accepted) return emptyList()
        val rise = s.announced.let { it == null || risk.level > it }
        val announce = when {
            risk.level >= Level.WARNING ->
                s.declined.let { it == null || risk.level > it } && (rise || now - s.announcedAt >= WARNING_REPEAT_MS)
            risk.level == Level.CAUTION -> !cautionMuted && (rise || newApproach)
            else -> false
        }
        if (!announce) return emptyList()
        s.announced = risk.level
        s.announcedAt = now
        return when {
            risk.level == Level.CAUTION -> listOf(Action.InformUnderpass(upcoming))
            risk.bypass != null -> listOf(Action.WarnUnderpass(upcoming), Action.OfferReroute(upcoming))
            else -> listOf(Action.WarnUnderpass(upcoming))
        }
    }

    // Same rule as backend/wipernet_sim/export_demo.py prep_level.
    private fun prepareForRain(pos: Position, cells: List<RainCell>, settings: Settings, now: Long): Action? {
        val here = cellOf(pos.lat, pos.lng)
        val hereMmph = cells.filter { cellOf(it.lat, it.lng) == here }.maxOfOrNull { it.mmph } ?: 0.0
        val near = pos.speedMps * PREP_NEAR_S
        val far = pos.speedMps * PREP_FAR_S
        val ahead = cells.filter {
            val d = distanceM(pos.lat, pos.lng, it.lat, it.lng)
            val off = angularOffsetDeg(bearingDeg(pos.lat, pos.lng, it.lat, it.lng), pos.headingDeg)
            d in near..far && off <= PREP_CONE_DEG
        }.maxOfOrNull { it.mmph } ?: 0.0
        val tier = PREP_TIERS.lastOrNull { hereMmph < it && it <= ahead } ?: return null
        val last = prepTier
        if (last != null && tier <= last && now - prepAt < PREP_REPEAT_MS) return null
        prepTier = tier
        prepAt = now
        return Action.PrepareForRain(
            heavier = tier == PREP_TIERS.last(),
            closeWindows = settings.prepCloseWindows,
            defog = settings.prepDefog,
            headlights = settings.prepHeadlights,
        )
    }

    private companion object {
        const val UNDERPASS_RANGE_M = 2000
        const val APPROACH_GAP_MS = 60_000L
        const val WARNING_REPEAT_MS = 300_000L
        const val PREP_REPEAT_MS = 600_000L
        const val PREP_NEAR_S = 60
        const val PREP_FAR_S = 180
        const val PREP_CONE_DEG = 30.0
        val PREP_TIERS = listOf(10.0, 30.0)
    }
}
