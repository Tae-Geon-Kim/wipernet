package ai.wipernet.car.act

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.data.ReplayVehicleSignals
import ai.wipernet.car.data.Scenario
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val REROUTE_WAIT_MS = 20_000L
private const val CONNECT_WAIT_MS = 5_000L
private const val INFO_WAIT_MS = 1_500L

interface Rerouter {
    suspend fun viaBypass(bypass: LatLng): Reroute
}

sealed interface Reroute {
    data object Failed : Reroute

    /** 우회 지점을 거쳐 가도록 바꿨다. 내비에서 받지 못한 값은 null이다. */
    data class Done(val remain: RemainTime? = null) : Reroute
}

/** 목적지까지 남은 시간. extraS는 우회 전보다 늘어난 시간이고, 우회 전 값을 못 받았으면 null. */
data class RemainTime(val totalS: Int, val extraS: Int?)

/** 데모: 녹화한 우회 경로로 갈아탄다(NaviHelper 안 씀). 두 경로 모두 같은 도착 지점에서 끝나서 마지막 장면 시각으로 남은 시간을 잰다. */
class ReplayRerouter(private val signals: ReplayVehicleSignals, private val scenario: Scenario) : Rerouter {
    override suspend fun viaBypass(bypass: LatLng): Reroute {
        signals.switchToBypass()
        val arriveT = scenario.bypass.myCar.last().t
        return Reroute.Done(RemainTime(arriveT - signals.t.value, arriveT - scenario.myCar.last().t))
    }
}

/**
 * 라이브: NaviHelper로 우회 지점을 첫 경유지로 넣고 재탐색한다.
 * 경유지 변경이나 재탐색 완료 콜백이 20초 안에 오면 성공으로 본다. 내비는 먼저 기존 경로를 취소하고
 * 약 6~7.5초 뒤에 경유지 변경을 알린다(에뮬레이터, 2026-11-15).
 * 남은 시간은 getDestinationInfo 응답(약 1초 뒤)으로 우회 전후에 한 번씩 묻는다.
 * 첫 안내는 내비가 재탐색 직후 스스로 말하므로 여기서 다루지 않는다(에뮬레이터, 2026-11-15).
 */
class NaviRerouter(context: Context) : Rerouter {
    private val navi = NaviHelper(context)
    private val connected = CompletableDeferred<Boolean>()

    @Volatile private var changed: CompletableDeferred<Unit>? = null
    @Volatile private var destination: CompletableDeferred<DestinationInfo>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onWaypointChanged(i: WaypointChangedInfo) { changed?.complete(Unit) }
        override fun onReRouteEnded() { changed?.complete(Unit) }
        override fun onRoutingCompleted() { changed?.complete(Unit) }
        override fun onDestinationInfo(i: DestinationInfo) { destination?.complete(i) }
    }

    init {
        navi.initialize({
            navi.addListener(listener)
            connected.complete(true)
        }, { e ->
            Log.w("WiperNet", "NaviHelper 연결 실패", e)
            connected.complete(false)
        })
    }

    override suspend fun viaBypass(bypass: LatLng): Reroute {
        if (withTimeoutOrNull(CONNECT_WAIT_MS) { connected.await() } != true) return Reroute.Failed
        val before = remainS()
        val result = CompletableDeferred<Unit>()
        changed = result
        navi.addWaypoint(RequestWaypointInfo(bypass.lat, bypass.lng, "우회 지점", WaypointIndex.FIRST, "", "", "0", ""))
        navi.requestReRoute()
        val ok = withTimeoutOrNull(REROUTE_WAIT_MS) { result.await() } != null
        changed = null
        if (!ok) return Reroute.Failed
        return Reroute.Done(remainS()?.let { RemainTime(it, before?.let { b -> it - b }) })
    }

    /** 목적지까지 남은 초. 경로가 없으면 내비가 0을 준다. */
    private suspend fun remainS(): Int? {
        val reply = CompletableDeferred<DestinationInfo>()
        destination = reply
        navi.getDestinationInfo()
        val info = withTimeoutOrNull(INFO_WAIT_MS) { reply.await() }.also { destination = null }
        return info?.remainTime?.takeIf { it > 0 }
    }

    fun release() {
        navi.removeListener(listener)
        navi.release()
    }
}
