package ai.wipernet.car.data

import ai.wipernet.car.core.Tbt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 데모 시나리오(기능지도 C3). 시뮬레이터 export_demo.py가 만든다. */
@Serializable
data class Scenario(
    val start: String,
    @SerialName("step_s") val stepS: Int,
    @SerialName("route_underpass") val routeUnderpass: String,
    @SerialName("my_car") val myCar: List<CarFrame>,
    val bypass: Bypass,
    val hazards: List<HazardFrame>,
) {
    companion object {
        fun parse(json: String): Scenario = wireJson.decodeFromString(json)
    }
}

@Serializable
data class CarFrame(
    val t: Int,
    val lat: Double,
    val lng: Double,
    val heading: Double,
    @SerialName("speed_mps") val speedMps: Double,
    val gear: String,
    val wiper: String,
    @SerialName("window_open") val windowOpen: Boolean,
    val tbt: TbtJson? = null,
)

@Serializable
data class TbtJson(val type: String, @SerialName("distance_m") val distanceM: Int) {
    fun toModel() = Tbt(type, distanceM)
}

@Serializable
data class Bypass(@SerialName("from_t") val fromT: Int, @SerialName("my_car") val myCar: List<CarFrame>)

@Serializable
data class HazardFrame(
    val t: Int,
    @SerialName("rain_cells") val rainCells: List<RainCellJson>,
    val underpasses: List<UnderpassRiskJson>,
)
