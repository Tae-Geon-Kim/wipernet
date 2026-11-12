package ai.wipernet.car.act

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.data.ReplayVehicleSignals
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val REROUTE_WAIT_MS = 10_000L
private const val CONNECT_WAIT_MS = 5_000L

interface Rerouter {
    /** 우회 지점을 거쳐 가도록 바꿨으면 true. */
    suspend fun viaBypass(bypass: LatLng): Boolean
}

/** 데모: 녹화한 우회 경로로 갈아탄다(NaviHelper 안 씀). */
class ReplayRerouter(private val signals: ReplayVehicleSignals) : Rerouter {
    override suspend fun viaBypass(bypass: LatLng): Boolean {
        signals.switchToBypass()
        return true
    }
}

/**
 * 라이브: NaviHelper로 우회 지점을 첫 경유지로 넣고 재탐색한다.
 * 경유지 변경이나 재탐색 완료 콜백이 10초 안에 오면 성공으로 본다.
 * 에뮬레이터 내비가 멈춰 있어 아직 확인하지 못했다(D2, 2026-11-12).
 */
class NaviRerouter(context: Context) : Rerouter {
    private val navi = NaviHelper(context)
    private val connected = CompletableDeferred<Boolean>()

    @Volatile private var changed: CompletableDeferred<Unit>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onWaypointChanged(i: WaypointChangedInfo) { changed?.complete(Unit) }
        override fun onReRouteEnded() { changed?.complete(Unit) }
        override fun onRoutingCompleted() { changed?.complete(Unit) }
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

    override suspend fun viaBypass(bypass: LatLng): Boolean {
        if (withTimeoutOrNull(CONNECT_WAIT_MS) { connected.await() } != true) return false
        val result = CompletableDeferred<Unit>()
        changed = result
        navi.addWaypoint(RequestWaypointInfo(bypass.lat, bypass.lng, "우회 지점", WaypointIndex.FIRST, "", "", "0", ""))
        navi.requestReRoute()
        val ok = withTimeoutOrNull(REROUTE_WAIT_MS) { result.await() } != null
        changed = null
        return ok
    }

    fun release() {
        navi.removeListener(listener)
        navi.release()
    }
}
