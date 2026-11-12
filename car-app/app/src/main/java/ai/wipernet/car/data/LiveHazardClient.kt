package ai.wipernet.car.data

import ai.wipernet.car.core.Hazards
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.distanceM
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import java.io.IOException

private const val POLL_MS = 5_000L
private const val FAILURES_TO_OFFLINE = 2
private const val KEEP_RADIUS_M = 3_000.0

/** 5초마다 서버 시계·비 지도·지하차도 위험을 받는다. 2번 연속 실패하면 오프라인이고, 그동안 마지막 값은 그대로 둔다. */
class LiveHazardClient(private val api: ServerApi, private val myPosition: () -> LatLng?) : HazardClient {
    private val state = MutableStateFlow(Hazards(emptyList(), emptyList(), offline = false))
    override val hazards: StateFlow<Hazards> = state.asStateFlow()
    private var failures = 0

    suspend fun run() {
        while (true) {
            poll()
            delay(POLL_MS)
        }
    }

    suspend fun poll() {
        try {
            val clock = api.clock()
            val cells = api.rainMap()
            val underpasses = api.underpassRisk()
            val me = myPosition()
            failures = 0
            state.value = Hazards(
                rainCells = cells.filter { me == null || distanceM(me.lat, me.lng, it.lat, it.lng) <= KEEP_RADIUS_M }
                    .map { it.toModel() },
                underpasses = underpasses.map { it.toModel() },
                offline = false,
                serverClock = clock,
            )
        } catch (e: IOException) {
            fail()
        } catch (e: SerializationException) {
            fail()
        }
    }

    private fun fail() {
        failures++
        if (failures >= FAILURES_TO_OFFLINE) state.update { it.copy(offline = true) }
    }
}
