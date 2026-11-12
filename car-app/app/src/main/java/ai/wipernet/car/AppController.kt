package ai.wipernet.car

import ai.wipernet.car.act.ActionRunner
import ai.wipernet.car.act.EitherPrompt
import ai.wipernet.car.act.NaviRerouter
import ai.wipernet.car.act.NotificationPrompt
import ai.wipernet.car.act.PleosAnnouncer
import ai.wipernet.car.act.PleosVehiclePrep
import ai.wipernet.car.act.ReplayRerouter
import ai.wipernet.car.act.Rerouter
import ai.wipernet.car.act.VoicePrompt
import ai.wipernet.car.core.Action
import ai.wipernet.car.core.HazardDecider
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Position
import ai.wipernet.car.core.RainCell
import ai.wipernet.car.core.Tbt
import ai.wipernet.car.core.UnderpassRisk
import ai.wipernet.car.core.UpcomingUnderpass
import ai.wipernet.car.core.UpcomingUnderpassDetector
import ai.wipernet.car.core.cellOf
import ai.wipernet.car.data.AutoWipeCounter
import ai.wipernet.car.data.HazardClient
import ai.wipernet.car.data.LastSent
import ai.wipernet.car.data.LiveHazardClient
import ai.wipernet.car.data.PleosVehicleSignals
import ai.wipernet.car.data.ReplayHazardClient
import ai.wipernet.car.data.ReplayVehicleSignals
import ai.wipernet.car.data.Scenario
import ai.wipernet.car.data.ServerApi
import ai.wipernet.car.data.TempId
import ai.wipernet.car.data.UploadPolicy
import ai.wipernet.car.data.VehicleSignals
import ai.wipernet.car.data.WiperEvent
import ai.wipernet.car.data.nearRouteEnds
import ai.wipernet.car.service.Notifications
import ai.wipernet.car.settings.Mode
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TICK_MS = 1_000L
private const val FLUSH_MS = 10_000L
private const val SETTINGS_SETTLE_MS = 1_000L

data class UiState(
    val mode: Mode = Mode.DEMO,
    val offline: Boolean = false,
    val gear: String? = null,
    val position: Position? = null,
    val upcoming: UpcomingUnderpass? = null,
    val rainCells: List<RainCell> = emptyList(),
    val underpasses: List<UnderpassRisk> = emptyList(),
)

/**
 * 앱의 한 바퀴(A12): 1초마다 앞쪽 지하차도 찾기 → 판단 → 동작 실행. 판단과 실행은 한 코루틴에서 차례로 돌아
 * HazardDecider를 한 곳에서만 만진다. 모드(데모/라이브)나 서버 주소가 바뀌거나 데모를 다시 시작하면 처음부터 다시 만든다.
 * 라이브 모드는 업로드 규칙(A16)이 고른 와이퍼 이벤트만 큐에 넣고 10초마다 보낸다. 데모 모드는 보내지 않는다.
 */
@OptIn(FlowPreview::class)
class AppController(private val app: WiperNetApp) {
    private val ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = ui.asStateFlow()
    private val demoRuns = MutableStateFlow(0)
    private var job: Job? = null
    val notifications by lazy { Notifications(app) }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val store = app.settingsStore.await()
            // 서버 주소는 라이브에서만 따지고, 입력 중에는 글자마다 다시 만들지 않도록 1초 기다린다.
            store.settings.map { it.mode to if (it.mode == Mode.LIVE) it.serverUrl else "" }.distinctUntilChanged()
                .debounce(SETTINGS_SETTLE_MS)
                .combine(demoRuns) { modeAndUrl, _ -> modeAndUrl }
                .collectLatest { (mode, url) -> runMode(mode, url) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun restartDemo() {
        demoRuns.value++
    }

    private suspend fun runMode(mode: Mode, serverUrl: String) = coroutineScope {
        ui.value = UiState(mode = mode)
        val announcer = PleosAnnouncer(app)
        val voice = VoicePrompt(app)
        val prep = PleosVehiclePrep(app)
        var navi: NaviRerouter? = null
        var pleos: PleosVehicleSignals? = null
        try {
            val signals: VehicleSignals
            val hazards: HazardClient
            val rerouter: Rerouter
            val clock: () -> Long
            var api: ServerApi? = null
            val latest: Latest
            if (mode == Mode.DEMO) {
                val scenario = Scenario.parse(app.assets.open("demo/scenario.json").bufferedReader().readText())
                val replay = ReplayVehicleSignals(scenario, this)
                signals = replay
                latest = Latest(signals, this)
                hazards = ReplayHazardClient(scenario, replay.t, this)
                rerouter = ReplayRerouter(replay)
                clock = { replay.t.value * 1000L }
            } else {
                pleos = PleosVehicleSignals(app, this).also { it.start() }
                signals = pleos
                latest = Latest(signals, this)
                api = ServerApi(serverUrl)
                val live = LiveHazardClient(api) { latest.position.value?.let { LatLng(it.lat, it.lng) } }
                launch { live.run() }
                hazards = live
                navi = NaviRerouter(app)
                rerouter = navi
                clock = SystemClock::elapsedRealtime
            }
            val decider = HazardDecider(clock)
            val runner = ActionRunner(
                announcer = announcer,
                prep = prep,
                rerouter = rerouter,
                prompt = EitherPrompt(voice, NotificationPrompt(app, notifications.channelForPrompts())),
                responses = decider,
            )
            api?.let { launch { uploadLoop(it, latest, hazards) } }
            while (true) {
                tick(latest, hazards, decider, runner, mode)
                delay(TICK_MS)
            }
        } finally {
            announcer.release()
            voice.release()
            prep.release()
            navi?.release()
            pleos?.stop()
        }
    }

    private suspend fun tick(latest: Latest, hazards: HazardClient, decider: HazardDecider, runner: ActionRunner, mode: Mode) {
        val pos = latest.position.value ?: return
        val h = hazards.hazards.value
        val gear = latest.gear.value ?: "D"
        val settings = app.settingsStore.await().settings.value
        val upcoming = UpcomingUnderpassDetector.detect(pos, latest.tbts.value, latest.guiding.value, h.underpasses)
        ui.value = UiState(mode, h.offline, gear, pos, upcoming, h.rainCells, h.underpasses)
        val actions = decider.decide(pos, gear, upcoming, h, settings)
        if (actions.isEmpty()) return
        Log.i("WiperNet", "actions $actions")
        actions.forEach { if (it is Action.WarnUnderpass || it is Action.InformUnderpass || it is Action.ShowOffline) notifications.alert(it) }
        runner.run(actions)
    }

    /** 업로드 규칙(A16). 동의·서버 시계·오프라인·출발지/목적지 300 m를 먼저 본다. 오프라인이면 넣지 않는다(사용자 결정 2026-11-12). */
    private suspend fun uploadLoop(api: ServerApi, latest: Latest, hazards: HazardClient) = coroutineScope {
        val tempId = TempId(System::currentTimeMillis)
        val counter = AutoWipeCounter(SystemClock::elapsedRealtime)
        // AUTO가 아닐 때는 꺼짐으로 넣어, 다시 AUTO가 됐을 때 옛 "켜진 시각"이 남지 않게 한다.
        launch { latest.wiperFlow.collect { (switch, state) -> counter.onState(if (switch == "AUTO") state else "OFF") } }
        var last: LastSent? = null
        var guideStart: LatLng? = null
        var lastFlush = 0L
        var lastSkip: String? = ""
        while (true) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val pos = latest.position.value
            val guiding = latest.guiding.value
            if (!guiding) guideStart = null else if (guideStart == null && pos != null) guideStart = LatLng(pos.lat, pos.lng)
            if (now - lastFlush >= FLUSH_MS) {
                lastFlush = now
                app.uploadQueue.flush { api.postEvents(it) }
            }
            val wiper = latest.wiper.value
            val h = hazards.hazards.value
            val clock = h.serverClock
            val skip = when {
                wiper == null -> "와이퍼 값 없음"
                pos == null -> "위치 없음"
                clock == null -> "서버 시계 없음"
                h.offline -> "오프라인"
                !app.settingsStore.await().settings.value.consented -> "동의 안 함"
                nearRouteEnds(LatLng(pos.lat, pos.lng), guideStart, latest.tbts.value) -> "출발지·목적지 300 m 안"
                else -> null
            }
            if (skip != lastSkip) Log.i("WiperNet", "upload: ${skip ?: "보낼 수 있음"}")
            lastSkip = skip
            if (skip != null || wiper == null || pos == null || clock == null) continue
            val (switch, rawState) = wiper
            val autoWpm = if (switch == "AUTO") counter.wpm() else null
            val state = if (autoWpm != null) (if (autoWpm > 0) "ON" else "OFF") else rawState
            val cell = cellOf(pos.lat, pos.lng)
            if (!UploadPolicy.shouldSend(now, guiding, switch, state, autoWpm, cell, last)) continue
            app.uploadQueue.enqueue(WiperEvent(tempId.current(), clock, pos.lat, pos.lng, switch, state, autoWpm))
            last = LastSent(now, switch, state, autoWpm, cell)
        }
    }

    /** 신호의 마지막 값. Pleos 신호는 값을 읽기 전에는 내지 않으므로 null로 시작한다. */
    private class Latest(signals: VehicleSignals, scope: CoroutineScope) {
        val wiperFlow: Flow<Pair<String, String>> = signals.wiper
        val position = signals.position.latest(scope)
        val gear = signals.gear.latest(scope)
        val wiper = signals.wiper.latest(scope)
        val tbts: StateFlow<List<Tbt>> = signals.tbts.stateIn(scope, SharingStarted.Eagerly, emptyList())
        val guiding: StateFlow<Boolean> = signals.guiding.stateIn(scope, SharingStarted.Eagerly, false)

        private fun <T> Flow<T>.latest(scope: CoroutineScope): StateFlow<T?> = stateIn(scope, SharingStarted.Eagerly, null)
    }
}
