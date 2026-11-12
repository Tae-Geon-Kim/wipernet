package ai.wipernet.car

import ai.wipernet.car.act.ActionRunner
import ai.wipernet.car.act.Announcer
import ai.wipernet.car.act.ConsentPrompt
import ai.wipernet.car.act.NaviRerouter
import ai.wipernet.car.act.PleosAnnouncer
import ai.wipernet.car.act.PleosVehiclePrep
import ai.wipernet.car.act.Rerouter
import ai.wipernet.car.act.VehiclePrep
import ai.wipernet.car.act.VoicePrompt
import ai.wipernet.car.core.Action
import ai.wipernet.car.core.HazardDecider
import ai.wipernet.car.core.Level
import ai.wipernet.car.core.UnderpassRisk
import ai.wipernet.car.core.UpcomingUnderpass
import ai.wipernet.car.data.LiveHazardClient
import ai.wipernet.car.data.PleosVehicleSignals
import ai.wipernet.car.data.ServerApi
import ai.wipernet.car.data.TempId
import ai.wipernet.car.data.WiperEvent
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.settings.Mode
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용 확인 화면(계획 Task 13 Step 5, Task 14 Step 5). Pleos 신호를 그대로 보여 주고,
 * 동의 + 라이브 모드면 와이퍼가 바뀔 때마다 업로드 큐를 거쳐 서버로 보낸다.
 * 위쪽 버튼은 A11 동작을 실제 Pleos 구현(TTS, STT, Vehicle 제어, NaviHelper)으로 한 번씩 실행한다.
 * 업로드 규칙과 앱 컨트롤러는 Task 15에서 따로 만든다.
 * adb shell am start --user current -n ai.wipernet.car/.SignalCheckActivity \
 *   --es server http://10.0.2.2:8001 --ez live true --ez consent true   (주면 설정에 저장한다)
 *   --es action warn|offer|inform|prepare|offline|reroute                (3초 뒤 그 동작을 실행한다)
 */
class SignalCheckActivity : Activity() {
    private val scope = MainScope()
    private lateinit var signals: PleosVehicleSignals
    private lateinit var announcer: PleosAnnouncer
    private lateinit var prompt: VoicePrompt
    private lateinit var prep: PleosVehiclePrep
    private lateinit var rerouter: NaviRerouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { textSize = 22f; setPadding(48, 24, 48, 48) }
        val buttons = LinearLayout(this).apply { setPadding(32, 32, 32, 0) }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(out)
            })
        })
        val app = application as WiperNetApp
        signals = PleosVehicleSignals(applicationContext, scope).also { it.start() }
        val log = MutableStateFlow(emptyList<String>())
        fun note(line: String) {
            Log.i("WiperNetCheck", line)
            log.update { (listOf(line) + it).take(12) }
        }

        announcer = PleosAnnouncer(applicationContext)
        prompt = VoicePrompt(applicationContext)
        prep = PleosVehiclePrep(applicationContext)
        rerouter = NaviRerouter(applicationContext)
        val runner = ActionRunner(
            announcer = object : Announcer {
                override suspend fun say(text: String) { note("말함: $text"); announcer.say(text) }
            },
            prep = object : VehiclePrep {
                override suspend fun closeWindows() = prep.closeWindows().also { note("창문 닫기 → $it") }
                override suspend fun defog() = prep.defog().also { note("김서림 방지 → $it") }
                override suspend fun headlightsOn() = prep.headlightsOn().also { note("전조등 → $it") }
            },
            rerouter = object : Rerouter {
                override suspend fun viaBypass(bypass: LatLng) = rerouter.viaBypass(bypass).also { note("우회 경유지 추가 → $it") }
            },
            prompt = object : ConsentPrompt {
                override suspend fun askYesNo(question: String) = prompt.askYesNo(question).also { note("예/아니오 → $it") }
                override suspend fun listen(seconds: Int) = prompt.listen(seconds).also { note("들은 말 → $it") }
            },
            responses = HazardDecider(System::currentTimeMillis),
        )
        val gaepo = UpcomingUnderpass(
            UnderpassRisk("gaepo", "개포지하차도", 37.475985, 127.058793, Level.WARNING, LatLng(37.475909, 127.058771), listOf("vehicles", "kma_warning")),
            distanceM = 1200,
            onRoute = true,
        )
        val actions = linkedMapOf(
            "warn" to ("경고" to listOf(Action.WarnUnderpass(gaepo))),
            "offer" to ("우회 질문" to listOf(Action.OfferReroute(gaepo))),
            "inform" to ("주의 안내" to listOf(Action.InformUnderpass(gaepo.copy(risk = gaepo.risk.copy(level = Level.CAUTION))))),
            "prepare" to ("차량 준비" to listOf(Action.PrepareForRain(heavier = false, closeWindows = true, defog = true, headlights = true))),
            "offline" to ("오프라인" to listOf(Action.ShowOffline)),
        )
        var running: Job? = null
        fun runAction(key: String) {
            if (running?.isActive == true) return
            if (key == "reroute") {
                note("--- 우회 경유지 바로 추가 (질문 없이)")
                running = scope.launch { note("우회 경유지 추가 → ${rerouter.viaBypass(gaepo.risk.bypass!!)}") }
                return
            }
            val (name, list) = actions[key] ?: return
            note("--- $name 실행")
            running = scope.launch { runner.run(list); note("--- $name 끝") }
        }
        actions.forEach { (key, v) ->
            buttons.addView(Button(this).apply { text = v.first; textSize = 20f; setOnClickListener { runAction(key) } })
        }
        intent.getStringExtra("action")?.let { key -> scope.launch { delay(3_000); runAction(key) } }

        scope.launch {
            val store = app.settingsStore.await()
            store.update { s ->
                s.copy(
                    serverUrl = intent.getStringExtra("server") ?: s.serverUrl,
                    mode = if (!intent.hasExtra("live")) s.mode else if (intent.getBooleanExtra("live", false)) Mode.LIVE else Mode.DEMO,
                    consented = intent.getBooleanExtra("consent", s.consented),
                )
            }
            val settings = store.settings.value
            val api = ServerApi(settings.serverUrl)
            var me: LatLng? = null
            val hazards = LiveHazardClient(api) { me }
            launch { hazards.run() }
            launch { signals.position.collect { me = LatLng(it.lat, it.lng) } }
            val vid = TempId(System::currentTimeMillis)
            note("서버 ${settings.serverUrl}, 모드 ${settings.mode}, 동의 ${settings.consented}")

            launch {
                signals.wiper.collect { (switch, state) ->
                    val clock = hazards.hazards.value.serverClock
                    val at = me
                    when {
                        !settings.consented || settings.mode != Mode.LIVE -> note("와이퍼 $switch/$state (동의·라이브 아님, 안 보냄)")
                        clock == null || at == null -> note("와이퍼 $switch/$state (서버 시계 $clock, 위치 $at 없어 안 보냄)")
                        else -> {
                            app.uploadQueue.enqueue(WiperEvent(vid.current(), clock, at.lat, at.lng, switch, state))
                            app.uploadQueue.flush { api.postEvents(it) }
                            note("와이퍼 $switch/$state → 큐 남은 ${app.uploadQueue.size()}건")
                        }
                    }
                }
            }

            fun label(name: String, f: Flow<Any?>) = name to f.onStart { emit("없음") }
            val rows = listOf(
                label("와이퍼", signals.wiper), label("기어", signals.gear), label("위치", signals.position),
                label("창문 열림", signals.windowOpen), label("길안내 중", signals.guiding), label("TBT", signals.tbts),
            )
            combine(rows.map { it.second }) { values -> rows.map { it.first }.zip(values.toList()) }
                .combine(hazards.hazards) { values, h -> values to h }
                .combine(log) { vh, lines -> Triple(vh.first, vh.second, lines) }
                .collect { (values, h, lines) ->
                    out.text = buildString {
                        values.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine("서버 시계: ${h.serverClock}, 오프라인: ${h.offline}, 비 칸 ${h.rainCells.size}개, 지하차도 ${h.underpasses.size}곳")
                        appendLine()
                        lines.forEach { appendLine(it) }
                    }
                }
        }
    }

    override fun onDestroy() {
        signals.stop()
        announcer.release()
        prompt.release()
        prep.release()
        rerouter.release()
        scope.cancel()
        super.onDestroy()
    }
}
