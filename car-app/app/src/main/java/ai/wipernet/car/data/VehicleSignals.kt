package ai.wipernet.car.data

import ai.wipernet.car.core.Position
import ai.wipernet.car.core.Tbt
import kotlinx.coroutines.flow.Flow

/** 차량 신호. 값을 읽을 수 없는 신호는 아무것도 내지 않는다. */
interface VehicleSignals {
    /** (스위치, 상태). 값 목록은 기능지도 C4. */
    val wiper: Flow<Pair<String, String>>
    val position: Flow<Position>

    /** "P", "R", "N", "D" */
    val gear: Flow<String>
    val tbts: Flow<List<Tbt>>
    val windowOpen: Flow<Boolean>
    val guiding: Flow<Boolean>
}
