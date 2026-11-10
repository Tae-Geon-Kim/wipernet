package ai.wipernet.car.core

enum class Level { NONE, CAUTION, WARNING, DANGER }

data class LatLng(val lat: Double, val lng: Double)

data class Position(val lat: Double, val lng: Double, val headingDeg: Double, val speedMps: Double)

data class Tbt(val type: String, val distanceM: Int)

data class UnderpassRisk(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val level: Level,
    val bypass: LatLng?,
    val reasons: List<String> = emptyList(),
)

data class RainCell(val lat: Double, val lng: Double, val mmph: Double)

data class Hazards(
    val rainCells: List<RainCell>,
    val underpasses: List<UnderpassRisk>,
    val offline: Boolean,
    val serverClock: String? = null,
)

data class UpcomingUnderpass(val risk: UnderpassRisk, val distanceM: Int)

sealed interface Action {
    data class WarnUnderpass(val u: UpcomingUnderpass) : Action
    data class OfferReroute(val u: UpcomingUnderpass) : Action
    data class InformUnderpass(val u: UpcomingUnderpass) : Action
    data class PrepareForRain(
        val heavier: Boolean,
        val closeWindows: Boolean,
        val defog: Boolean,
        val headlights: Boolean,
    ) : Action

    data object ShowOffline : Action
}
