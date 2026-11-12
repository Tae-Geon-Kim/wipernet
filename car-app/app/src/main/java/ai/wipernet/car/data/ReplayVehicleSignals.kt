package ai.wipernet.car.data

import ai.wipernet.car.core.Position
import ai.wipernet.car.core.Tbt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** 시나리오의 my_car를 시각대로 재생한다. speed 2.0이면 두 배 빠르다. 마지막 장면 한 칸 뒤에는 도착해 주차한 것으로 보고 기어를 P로 둔다. */
class ReplayVehicleSignals(private val scenario: Scenario, scope: CoroutineScope, private val speed: Double = 1.0) :
    VehicleSignals {
    private val frame = MutableStateFlow(scenario.myCar.first())
    private val parked = MutableStateFlow(false)

    @Volatile private var onBypass = false

    /** 지금 재생 중인 장면의 시나리오 시각(초). ReplayHazardClient가 같은 시각을 쓴다. */
    val t: StateFlow<Int> = frame.map { it.t }.stateIn(scope, SharingStarted.Eagerly, frame.value.t)

    override val wiper: Flow<Pair<String, String>> =
        frame.map { it.wiper to if (it.wiper == "OFF") "OFF" else "ON" }.distinctUntilChanged()
    override val position: Flow<Position> = frame.map { Position(it.lat, it.lng, it.heading, it.speedMps) }
    override val gear: Flow<String> = combine(frame, parked) { f, p -> if (p) "P" else f.gear }.distinctUntilChanged()
    override val tbts: Flow<List<Tbt>> = frame.map { f -> listOfNotNull(f.tbt?.toModel()) }
    override val windowOpen: Flow<Boolean> = frame.map { it.windowOpen }.distinctUntilChanged()
    override val guiding: Flow<Boolean> = flowOf(true)

    init {
        scope.launch {
            while (true) {
                val next = next() ?: break
                delay(((next.t - frame.value.t) * 1000 / speed).toLong())
                frame.value = next() ?: break
            }
            delay((scenario.stepS * 1000 / speed).toLong())
            parked.value = true
        }
    }

    /** bypass.from_t 이후로는 우회 경로 장면을 낸다. */
    fun switchToBypass() {
        onBypass = true
    }

    private fun next(): CarFrame? {
        val now = frame.value.t
        val track = if (onBypass && now >= scenario.bypass.fromT) scenario.bypass.myCar else scenario.myCar
        return track.firstOrNull { it.t > now }
    }
}
