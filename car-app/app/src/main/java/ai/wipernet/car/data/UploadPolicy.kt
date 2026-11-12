package ai.wipernet.car.data

import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Tbt
import ai.wipernet.car.core.distanceM

private const val ON_RESEND_MS = 60_000L
private const val OFF_GUIDING_RESEND_MS = 300_000L
private const val PRIVATE_RADIUS_M = 300.0
private const val WIPE_WINDOW_MS = 60_000L
private const val CONTINUOUS_ON_MS = 15_000L
private const val CONTINUOUS_WPM = 40.0

data class LastSent(val atMs: Long, val switch: String, val state: String, val autoWpm: Double?, val cell: Pair<Long, Long>)

/** 와이퍼 이벤트를 언제 보내나. 시뮬레이터 `upload_policy.should_send`와 같은 규칙이고 C5 사례로 같이 테스트한다. */
object UploadPolicy {
    fun shouldSend(
        nowMs: Long,
        guiding: Boolean,
        switch: String,
        state: String,
        autoWpm: Double?,
        cell: Pair<Long, Long>,
        last: LastSent?,
    ): Boolean {
        if (state == "ON") {
            if (last == null || last.state != "ON" || last.switch != switch) return true
            if (((last.autoWpm ?: 0.0) > 0) != ((autoWpm ?: 0.0) > 0)) return true
            return nowMs - last.atMs >= ON_RESEND_MS
        }
        if (last != null && last.state == "ON") return true
        if (!guiding) return false
        return last == null || last.cell != cell || nowMs - last.atMs >= OFF_GUIDING_RESEND_MS
    }
}

/**
 * 출발지·목적지 300 m 안이면 보내지 않는다(설계 6.11). 출발지는 길안내가 시작된 위치,
 * 목적지까지는 TBT 구간 거리의 합(첫 항목이 지금 위치에서의 거리다).
 */
fun nearRouteEnds(me: LatLng, guideStart: LatLng?, tbts: List<Tbt>): Boolean {
    if (guideStart != null && distanceM(me.lat, me.lng, guideStart.lat, guideStart.lng) <= PRIVATE_RADIUS_M) return true
    return tbts.isNotEmpty() && tbts.sumOf { it.distanceM } <= PRIVATE_RADIUS_M
}

/**
 * AUTO 와이퍼가 최근 60초에 몇 번 닦았나(OFF→ON 횟수). 15초 넘게 계속 ON이면 40(연속).
 * AUTO에서 상태가 와이퍼 한 번마다 바뀌는지는 에뮬레이터에서 확인하지 못했다(기능지도 8장).
 */
class AutoWipeCounter(private val clock: () -> Long) {
    private val wipes = ArrayDeque<Long>()
    private var onSince: Long? = null

    @Synchronized
    fun onState(state: String) {
        val now = clock()
        if (state == "ON" && onSince == null) {
            onSince = now
            wipes.addLast(now)
        } else if (state != "ON") {
            onSince = null
        }
    }

    @Synchronized
    fun wpm(): Double {
        val now = clock()
        while (wipes.isNotEmpty() && now - wipes.first() > WIPE_WINDOW_MS) wipes.removeFirst()
        val since = onSince
        if (since != null && now - since >= CONTINUOUS_ON_MS) return CONTINUOUS_WPM
        return wipes.size.toDouble()
    }
}
