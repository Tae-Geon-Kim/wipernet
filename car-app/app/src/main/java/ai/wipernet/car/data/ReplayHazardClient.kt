package ai.wipernet.car.data

import ai.wipernet.car.core.Hazards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 시나리오 시각 t 이하에서 가장 늦은 hazards 장면을 낸다. t는 ReplayVehicleSignals.t를 넘긴다. */
class ReplayHazardClient(scenario: Scenario, t: Flow<Int>, scope: CoroutineScope) : HazardClient {
    private val frames = scenario.hazards.sortedBy { it.t }.map { f ->
        f.t to Hazards(f.rainCells.map { it.toModel() }, f.underpasses.map { it.toModel() }, offline = false)
    }

    override val hazards: StateFlow<Hazards> =
        t.map(::at).stateIn(scope, SharingStarted.Eagerly, frames.first().second)

    private fun at(t: Int): Hazards = (frames.lastOrNull { it.first <= t } ?: frames.first()).second
}
