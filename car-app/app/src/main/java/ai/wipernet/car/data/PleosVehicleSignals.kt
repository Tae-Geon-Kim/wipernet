package ai.wipernet.car.data

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.wipernet.car.core.Position
import ai.wipernet.car.core.Tbt
import ai.wipernet.car.core.bearingDeg
import ai.wipernet.car.core.distanceM
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

private const val TAG = "WiperNet"
private const val VEHICLE_POLL_MS = 1_000L
private const val NAVI_POLL_MS = 2_000L
private const val MIN_MOVE_M = 5.0

private val WINDSHIELD = WindowArea(WindowValue.FRONT_WINDSHIELD)
private val SIDE_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    .map { WindowArea(it) }

/**
 * Pleos SDK에서 차량 신호를 읽는다.
 * Vehicle SDK는 연결 완료 콜백이 없고 와이퍼 스위치 리스너도 없어, 현재 값을 1초마다 읽는다.
 * 위치는 FusedLocation, 지원하지 않으면 NaviHelper. 속도와 방향은 연속한 두 위치로 계산한다
 * (에뮬레이터 FusedLocation은 heading이 0으로 오고, 둘 다 속도를 주지 않는다).
 */
class PleosVehicleSignals(context: Context, private val scope: CoroutineScope) : VehicleSignals {
    private val vehicle = Vehicle(context).apply { initialize() }
    private val navi = NaviHelper(context)
    private val fused = FusedLocation(context)
    private val useFused = fused.isSupported()

    private val wiperState = MutableStateFlow<Pair<String, String>?>(null)
    private val positionState = MutableStateFlow<Position?>(null)
    private val gearState = MutableStateFlow<String?>(null)
    private val tbtState = MutableStateFlow<List<Tbt>?>(null)
    private val windowPositions = MutableStateFlow<Map<WindowArea, Int>>(emptyMap())
    private val windowOpenState = MutableStateFlow<Boolean?>(null)
    private val guidingState = MutableStateFlow<Boolean?>(null)

    override val wiper: Flow<Pair<String, String>> = wiperState.filterNotNull()
    override val position: Flow<Position> = positionState.filterNotNull()
    override val gear: Flow<String> = gearState.filterNotNull()
    override val tbts: Flow<List<Tbt>> = tbtState.filterNotNull()
    override val windowOpen: Flow<Boolean> = windowOpenState.filterNotNull()
    override val guiding: Flow<Boolean> = guidingState.filterNotNull()

    private val jobs = mutableListOf<Job>()
    private val wiperListening = AtomicBoolean(false)
    private var lastFixAtMs: Long? = null
    private var headingFrom: Pair<Double, Double>? = null
    private val loggedFailures = ConcurrentHashMap.newKeySet<String>()
    private val wiperReads = AtomicLong()
    private val wiperApplied = AtomicLong()

    private val wiperListener = object : WiperStateListener {
        // 상태가 스위치보다 먼저 바뀌어 오므로, 반쪽만 고치지 않고 둘 다 다시 읽는다.
        override fun onWiperStateUpdated(state: WiperState?) = readWiper()

        override fun onFailed(e: Exception) = logOnce("wiper listener", e)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(l: List<TBTInfo>) {
            tbtState.value = l.map { Tbt(it.type.name, Math.round(it.distance).toInt()) }
        }

        override fun onRouteStateInfo(i: RouteStateInfo) {
            guidingState.value = i.naviMode == NaviMode.ROUTE_GUIDING || i.naviMode == NaviMode.SIMULATION_GUIDING
        }

        override fun onCurrentLocationInfo(i: CurrentLocationInfo) {
            if (!useFused) onFix(i.latitude, i.longitude, System.currentTimeMillis())
        }
    }

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(d: LocationData?) {
            if (d != null) onFix(d.latitude, d.longitude, d.timestampMillis)
        }

        override fun onError(e: Exception) = logOnce("fused location", e)
    }

    fun start() {
        jobs += scope.launch {
            while (true) {
                readWiper()
                readGear()
                readWindows()
                delay(VEHICLE_POLL_MS)
            }
        }
        navi.initialize({
            navi.addListener(naviListener)
            jobs += scope.launch {
                while (true) {
                    navi.getTBTInfo()
                    navi.getRouteStateInfo()
                    if (!useFused) navi.getCurrentLocationInfo()
                    delay(NAVI_POLL_MS)
                }
            }
        }, { e -> Log.w(TAG, "NaviHelper 연결 실패", e) })
        if (useFused) {
            fused.initialize()
            fused.registerFusedLocationCallback(fusedListener)
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (wiperListening.get()) vehicle.getWiper().unregisterWiperState(WINDSHIELD, wiperListener)
        navi.removeListener(naviListener)
        navi.release()
        if (useFused) {
            fused.unregisterFusedLocationCallback(fusedListener)
            fused.release()
        }
        vehicle.release()
    }

    /** 읽기가 겹치면 늦게 시작한 읽기가 먼저 끝날 수 있다. 먼저 시작한 읽기의 결과로 덮어쓰지 않는다. */
    private fun readWiper() {
        val w = vehicle.getWiper()
        val read = wiperReads.incrementAndGet()
        w.getCurrentWiperSwitch(WINDSHIELD, { sw ->
            w.getCurrentWiperState(WINDSHIELD, { st ->
                if (sw != null && st != null && wiperApplied.getAndAccumulate(read, ::maxOf) < read) {
                    wiperState.value = sw.name to st.name
                }
                if (wiperListening.compareAndSet(false, true)) w.registerWiperState(WINDSHIELD, wiperListener)
            }, { logOnce("wiper state", it) })
        }, { logOnce("wiper switch", it) })
    }

    private fun readGear() {
        vehicle.getGear().getCurrentGearSelectionState({ g -> g?.let(::gearOf)?.let { gearState.value = it } }, { logOnce("gear", it) })
    }

    private fun readWindows() {
        val window = vehicle.getWindow()
        for (area in SIDE_WINDOWS) {
            window.getCurrentWindowPosition(area, { p ->
                if (p == null) return@getCurrentWindowPosition
                val all = windowPositions.updateAndGet { it + (area to p) }
                windowOpenState.value = all.values.any { it > 0 }
            }, { logOnce("window $area", it) })
        }
    }

    /** 속도는 직전 위치에서, 방향은 5 m 넘게 움직였을 때 그 출발점에서 계산한다(멈춰 있으면 방향 유지). */
    @Synchronized
    private fun onFix(lat: Double, lng: Double, atMs: Long) {
        val prev = positionState.value
        val prevAt = lastFixAtMs
        if (prev != null && prevAt != null && atMs <= prevAt) return
        val speed = if (prev == null || prevAt == null) 0.0 else distanceM(prev.lat, prev.lng, lat, lng) / ((atMs - prevAt) / 1000.0)
        var heading = prev?.headingDeg ?: 0.0
        val from = headingFrom
        if (from == null) {
            headingFrom = lat to lng
        } else if (distanceM(from.first, from.second, lat, lng) >= MIN_MOVE_M) {
            heading = bearingDeg(from.first, from.second, lat, lng)
            headingFrom = lat to lng
        }
        positionState.value = Position(lat, lng, heading, speed)
        lastFixAtMs = atMs
    }

    private fun logOnce(what: String, e: Exception) {
        if (loggedFailures.add(what)) Log.w(TAG, "$what 읽기 실패", e)
    }

    private fun gearOf(g: VehicleGear): String? = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> null
        else -> "D"
    }
}
