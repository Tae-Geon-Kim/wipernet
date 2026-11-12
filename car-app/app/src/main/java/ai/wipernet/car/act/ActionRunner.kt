package ai.wipernet.car.act

import ai.wipernet.car.core.Action
import ai.wipernet.car.core.DriverResponses
import ai.wipernet.car.core.UpcomingUnderpass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

private const val LISTEN_S = 8
private const val ASK_REROUTE = "우회 경로로 안내할까요?"

/** A3의 동작을 계획 Task 14의 고정 문구로 실행한다. 한 번에 하나씩, 말이 끝난 뒤 다음으로 간다. */
class ActionRunner(
    private val announcer: Announcer,
    private val prep: VehiclePrep,
    private val rerouter: Rerouter,
    private val prompt: ConsentPrompt,
    private val responses: DriverResponses,
) {
    suspend fun run(actions: List<Action>) {
        for (a in actions) {
            when (a) {
                is Action.WarnUnderpass -> announcer.say(warning(a.u))
                is Action.OfferReroute -> offerReroute(a.u)
                is Action.InformUnderpass -> inform(a.u)
                is Action.PrepareForRain -> prepare(a)
                Action.ShowOffline -> announcer.say("네트워크 연결이 끊겨 빗길 정보를 받을 수 없어요.")
            }
        }
    }

    private suspend fun offerReroute(u: UpcomingUnderpass) {
        val bypass = u.risk.bypass ?: return
        announcer.say(ASK_REROUTE)
        when (prompt.askYesNo(ASK_REROUTE)) {
            true -> if (rerouter.viaBypass(bypass)) {
                responses.onRerouteAccepted(u.risk.id)
                announcer.say("우회 경로로 안내할게요.")
            } else {
                announcer.say("우회 경로를 찾지 못했어요.")
            }
            false -> responses.onRerouteDeclined(u.risk.id, u.risk.level)
            null -> Unit
        }
    }

    private suspend fun inform(u: UpcomingUnderpass) {
        val officialOnly = u.risk.reasons.isNotEmpty() && u.risk.reasons.all { it == "kma_warning" || it == "alert_rain" }
        val what = if (officialOnly) "호우특보가 발효 중이에요." else "비가 많이 오고 있어요."
        announcer.say("${where(u)} 주변에 $what")
        if (isMuteRequest(prompt.listen(LISTEN_S))) {
            responses.onCautionMuted()
            announcer.say("주의 안내는 끌게요. 위험할 때는 알려 드릴게요.")
        }
    }

    private suspend fun prepare(a: Action.PrepareForRain) {
        val steps = listOf(a.closeWindows to PrepStep.WINDOWS, a.defog to PrepStep.DEFOG, a.headlights to PrepStep.HEADLIGHTS)
            .filter { it.first }.map { it.second }
        // 반영 확인이 항목마다 최대 2초라 함께 돌린다. 말하는 순서는 위 순서 그대로다.
        val outcomes = coroutineScope { steps.map { step -> async { step to step.run(prep) } }.awaitAll() }
        val done = outcomes.filter { it.second == PrepOutcome.APPLIED }.map { it.first }
        val ask = outcomes.filter { it.second == PrepOutcome.NOT_APPLIED }.map { it.first }
        if (done.isEmpty() && ask.isEmpty()) return
        val sentences = listOfNotNull(
            if (a.heavier) "곧 더 강한 비 구간이에요." else "곧 비 구간이에요.",
            sentence(done) { it.willDo },
            sentence(ask) { it.pleaseDo },
        )
        announcer.say(sentences.joinToString(" "))
    }

    private fun sentence(steps: List<PrepStep>, last: (PrepStep) -> String): String? =
        if (steps.isEmpty()) null else (steps.dropLast(1).map { it.and } + last(steps.last())).joinToString(", ") + "."
}

private enum class PrepStep(val and: String, val willDo: String, val pleaseDo: String, val run: suspend (VehiclePrep) -> PrepOutcome) {
    WINDOWS("창문을 닫고", "창문을 닫을게요", "창문을 닫아 주세요", { it.closeWindows() }),
    DEFOG("김서림 방지를 켜고", "김서림 방지를 켤게요", "김서림 방지를 켜 주세요", { it.defog() }),
    HEADLIGHTS("전조등을 켜고", "전조등을 켤게요", "전조등을 켜 주세요", { it.headlightsOn() }),
}

private fun where(u: UpcomingUnderpass) = "${String.format(Locale.US, "%.1f", u.distanceM / 1000.0)}km 앞 ${u.risk.name}"

/** 첫 문장은 근거로 고른다: 재난문자 통제 > 주변 차량 > 레이더(설계 6.10.4). 호우특보는 한 문장만 덧붙인다. */
private fun warning(u: UpcomingUnderpass): String {
    val reasons = u.risk.reasons
    val first = when {
        "alert_closure" in reasons -> "${where(u)}, 통제 중이라는 재난문자가 있어요."
        "vehicles" in reasons -> "${where(u)}, 주변 차량들이 폭우를 감지했어요."
        else -> "${where(u)} 주변에 강한 비가 관측되고 있어요."
    }
    val suffix = if ("alert_closure" !in reasons && "kma_warning" in reasons) " 호우특보도 발효 중이에요." else ""
    return first + suffix
}
