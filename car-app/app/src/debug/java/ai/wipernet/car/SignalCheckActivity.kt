package ai.wipernet.car

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
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용 확인 화면(계획 Task 13 Step 5). Pleos 신호를 그대로 보여 주고,
 * 동의 + 라이브 모드면 와이퍼가 바뀔 때마다 업로드 큐를 거쳐 서버로 보낸다.
 * 업로드 규칙과 앱 컨트롤러는 Task 15에서 따로 만든다.
 * adb shell am start --user current -n ai.wipernet.car/.SignalCheckActivity \
 *   --es server http://10.0.2.2:8001 --ez live true --ez consent true   (주면 설정에 저장한다)
 */
class SignalCheckActivity : Activity() {
    private val scope = MainScope()
    private lateinit var signals: PleosVehicleSignals

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { textSize = 22f; setPadding(48, 48, 48, 48) }
        setContentView(ScrollView(this).apply { addView(out) })
        val app = application as WiperNetApp
        signals = PleosVehicleSignals(applicationContext, scope).also { it.start() }
        val log = MutableStateFlow(emptyList<String>())
        fun note(line: String) {
            Log.i("WiperNetCheck", line)
            log.update { (listOf(line) + it).take(12) }
        }

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
        scope.cancel()
        super.onDestroy()
    }
}
