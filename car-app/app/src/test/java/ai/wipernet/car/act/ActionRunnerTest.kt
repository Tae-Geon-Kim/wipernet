package ai.wipernet.car.act

import ai.wipernet.car.core.Action.InformUnderpass
import ai.wipernet.car.core.Action.OfferReroute
import ai.wipernet.car.core.Action.PrepareForRain
import ai.wipernet.car.core.Action.ShowOffline
import ai.wipernet.car.core.Action.WarnUnderpass
import ai.wipernet.car.core.DriverResponses
import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Level
import ai.wipernet.car.core.UnderpassRisk
import ai.wipernet.car.core.UpcomingUnderpass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRunnerTest {
    private val bypass = LatLng(37.4759, 127.0587)

    private fun up(reasons: List<String> = listOf("vehicles"), distanceM: Int = 1200, level: Level = Level.WARNING) =
        UpcomingUnderpass(UnderpassRisk("gaepo", "역삼지하차도", 37.47, 127.05, level, bypass, reasons), distanceM, onRoute = true)

    private class Fakes(
        val answer: Boolean? = null,
        val heard: String? = null,
        val rerouteOk: Boolean = true,
        val outcomes: Map<String, PrepOutcome> = emptyMap(),
    ) : DriverResponses {
        val spoken = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val prepCalls = mutableListOf<String>()
        var reroutedVia: LatLng? = null
        var declined: Pair<String, Level>? = null
        var accepted: String? = null
        var muted = false

        override fun onRerouteDeclined(id: String, level: Level) { declined = id to level }
        override fun onRerouteAccepted(id: String) { accepted = id }
        override fun onCautionMuted() { muted = true }

        private fun prepOf(name: String): PrepOutcome { prepCalls += name; return outcomes[name] ?: PrepOutcome.APPLIED }

        val runner = ActionRunner(
            announcer = object : Announcer { override suspend fun say(text: String) { spoken += text } },
            prep = object : VehiclePrep {
                override suspend fun closeWindows() = prepOf("windows")
                override suspend fun defog() = prepOf("defog")
                override suspend fun headlightsOn() = prepOf("headlights")
            },
            rerouter = object : Rerouter { override suspend fun viaBypass(bypass: LatLng): Boolean { reroutedVia = bypass; return rerouteOk } },
            prompt = object : ConsentPrompt {
                override suspend fun askYesNo(question: String): Boolean? { asked += question; return answer }
                override suspend fun listen(seconds: Int): String? = heard
            },
            responses = this,
        )
    }

    @Test fun warnThenOfferAcceptedReroutes() = runTest {
        val f = Fakes(answer = true)
        f.runner.run(listOf(WarnUnderpass(up()), OfferReroute(up())))
        assertEquals(listOf("1.2km 앞 역삼지하차도, 주변 차량들이 폭우를 감지했어요.", "우회 경로로 안내할까요?", "우회 경로로 안내할게요."), f.spoken)
        assertEquals(listOf("우회 경로로 안내할까요?"), f.asked)
        assertEquals(bypass, f.reroutedVia)
        assertEquals("gaepo", f.accepted)
    }

    @Test fun declineIsReportedToDecider() = runTest {
        val f = Fakes(answer = false)
        f.runner.run(listOf(OfferReroute(up(level = Level.DANGER))))
        assertEquals("gaepo" to Level.DANGER, f.declined)
        assertNull(f.reroutedVia)
        assertEquals(listOf("우회 경로로 안내할까요?"), f.spoken)
    }

    @Test fun noAnswerChangesNothing() = runTest {
        val f = Fakes(answer = null)
        f.runner.run(listOf(OfferReroute(up())))
        assertNull(f.declined)
        assertNull(f.accepted)
        assertNull(f.reroutedVia)
    }

    @Test fun failedRerouteIsNotTreatedAsAccepted() = runTest {
        val f = Fakes(answer = true, rerouteOk = false)
        f.runner.run(listOf(OfferReroute(up())))
        assertNull(f.accepted)
        assertEquals(listOf("우회 경로로 안내할까요?", "우회 경로를 찾지 못했어요."), f.spoken)
    }

    @Test fun warnUsesClosureAlertFirst() = runTest {
        val f = Fakes()
        f.runner.run(listOf(WarnUnderpass(up(listOf("vehicles", "kma_warning", "alert_closure")))))
        assertEquals(listOf("1.2km 앞 역삼지하차도, 통제 중이라는 재난문자가 있어요."), f.spoken)
    }

    @Test fun warnAddsKmaSuffix() = runTest {
        val f = Fakes()
        f.runner.run(listOf(WarnUnderpass(up(listOf("vehicles", "kma_warning")))))
        assertEquals(listOf("1.2km 앞 역삼지하차도, 주변 차량들이 폭우를 감지했어요. 호우특보도 발효 중이에요."), f.spoken)
    }

    @Test fun warnFromRadarRoundsDistance() = runTest {
        val f = Fakes()
        f.runner.run(listOf(WarnUnderpass(up(listOf("radar"), distanceM = 1960))))
        assertEquals(listOf("2.0km 앞 역삼지하차도 주변에 강한 비가 관측되고 있어요."), f.spoken)
    }

    @Test fun informThenMuteOnStop() = runTest {
        val f = Fakes(heard = "그만 말해")
        f.runner.run(listOf(InformUnderpass(up(listOf("vehicles"), level = Level.CAUTION))))
        assertEquals(
            listOf("1.2km 앞 역삼지하차도 주변에 비가 많이 오고 있어요.", "주의 안내는 끌게요. 위험할 때는 알려 드릴게요."),
            f.spoken,
        )
        assertTrue(f.muted)
    }

    @Test fun informFromWarningOnlyAndOtherSpeechKeepsCaution() = runTest {
        val f = Fakes(heard = "오늘 날씨 어때")
        f.runner.run(listOf(InformUnderpass(up(listOf("kma_warning", "alert_rain"), level = Level.CAUTION))))
        assertEquals(listOf("1.2km 앞 역삼지하차도 주변에 호우특보가 발효 중이에요."), f.spoken)
        assertFalse(f.muted)
    }

    @Test fun prepareAnnouncesAppliedSteps() = runTest {
        val f = Fakes()
        f.runner.run(listOf(PrepareForRain(heavier = false, closeWindows = true, defog = true, headlights = true)))
        assertEquals(listOf("곧 비 구간이에요. 창문을 닫고, 김서림 방지를 켜고, 전조등을 켤게요."), f.spoken)
    }

    @Test fun prepareSuggestsNotAppliedSteps() = runTest {
        val f = Fakes(outcomes = mapOf("defog" to PrepOutcome.NOT_APPLIED))
        f.runner.run(listOf(PrepareForRain(heavier = true, closeWindows = true, defog = true, headlights = true)))
        assertEquals(listOf("곧 더 강한 비 구간이에요. 창문을 닫고, 전조등을 켤게요. 김서림 방지를 켜 주세요."), f.spoken)
    }

    @Test fun prepareSkipsDisabledAndAlready() = runTest {
        val f = Fakes(outcomes = mapOf("headlights" to PrepOutcome.ALREADY))
        f.runner.run(listOf(PrepareForRain(heavier = false, closeWindows = true, defog = false, headlights = true)))
        assertEquals(listOf("곧 비 구간이에요. 창문을 닫을게요."), f.spoken)
        assertEquals(listOf("windows", "headlights"), f.prepCalls)

        val g = Fakes(outcomes = PrepOutcome.ALREADY.let { a -> mapOf("windows" to a, "defog" to a, "headlights" to a) })
        g.runner.run(listOf(PrepareForRain(heavier = false, closeWindows = true, defog = true, headlights = true)))
        assertEquals(emptyList<String>(), g.spoken)
    }

    @Test fun prepareOnlyNotApplied() = runTest {
        val f = Fakes(outcomes = mapOf("windows" to PrepOutcome.NOT_APPLIED))
        f.runner.run(listOf(PrepareForRain(heavier = false, closeWindows = true, defog = false, headlights = false)))
        assertEquals(listOf("곧 비 구간이에요. 창문을 닫아 주세요."), f.spoken)
    }

    @Test fun offline() = runTest {
        val f = Fakes()
        f.runner.run(listOf(ShowOffline))
        assertEquals(listOf("네트워크 연결이 끊겨 빗길 정보를 받을 수 없어요."), f.spoken)
    }

    @Test fun parsesAnswers() {
        assertEquals(true, parseYesNo("응"))
        assertEquals(true, parseYesNo("네 해 줘"))
        assertEquals(true, parseYesNo("그래"))
        assertEquals(false, parseYesNo("아니요"))
        assertEquals(false, parseYesNo("싫어"))
        assertEquals(false, parseYesNo("아뇨"))
        assertEquals(false, parseYesNo("그래도 그냥 갈게"))
        assertEquals(false, parseYesNo("아니에요"))
        assertNull(parseYesNo("음"))
        assertNull(parseYesNo(null))
        assertTrue(isMuteRequest("그만해"))
        assertTrue(isMuteRequest("알았어"))
        assertTrue(isMuteRequest("알겠어"))
        assertTrue(isMuteRequest("적당히 해"))
        assertFalse(isMuteRequest("네"))
        assertFalse(isMuteRequest(null))
    }
}
