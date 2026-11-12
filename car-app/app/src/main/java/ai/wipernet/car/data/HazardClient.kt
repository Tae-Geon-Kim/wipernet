package ai.wipernet.car.data

import ai.wipernet.car.core.Hazards
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Level
import ai.wipernet.car.core.RainCell
import ai.wipernet.car.core.UnderpassRisk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface HazardClient {
    val hazards: StateFlow<Hazards>
}

internal val wireJson = Json { ignoreUnknownKeys = true }

@Serializable
data class LatLngJson(val lat: Double, val lng: Double)

@Serializable
data class RainCellJson(val lat: Double, val lng: Double, val mmph: Double) {
    fun toModel() = RainCell(lat, lng, mmph)
}

@Serializable
data class UnderpassRiskJson(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val level: Level,
    @SerialName("bypass_waypoint") val bypassWaypoint: LatLngJson? = null,
    val reasons: List<String> = emptyList(),
) {
    fun toModel() = UnderpassRisk(id, name, lat, lng, level, bypassWaypoint?.let { LatLng(it.lat, it.lng) }, reasons)
}
