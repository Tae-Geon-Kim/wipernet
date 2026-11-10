package ai.wipernet.car.core

import ai.wipernet.car.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardDeciderTest {
    private var nowMs = 0L
    private val decider = HazardDecider { nowMs }
    private val pos = Position(37.50, 127.03, headingDeg = 0.0, speedMps = 20.0)
    private val bypass = LatLng(37.51, 127.03)

    private fun at(s: Int) {
        nowMs = s * 1000L
    }

    private fun up(id: String, level: Level, withBypass: Boolean = true, distanceM: Int = 1000, onRoute: Boolean = true) =
        UpcomingUnderpass(
            UnderpassRisk(id, id, 37.509, 127.03, level, if (withBypass) bypass else null),
            distanceM,
            onRoute,
        )

    private fun hazards(offline: Boolean = false, cells: List<RainCell> = emptyList()) =
        Hazards(cells, emptyList(), offline)

    private fun decide(
        upcoming: UpcomingUnderpass? = null,
        hz: Hazards = hazards(),
        gear: String = "D",
        s: Settings = Settings(),
    ) = decider.decide(pos, gear, upcoming, hz, s)

    private fun rain(hereMmph: Double?, aheadMmph: Double?): Hazards {
        val cells = buildList {
            if (hereMmph != null) add(RainCell(37.50, 127.03, hereMmph))
            if (aheadMmph != null) add(RainCell(37.518, 127.03, aheadMmph))
        }
        return hazards(cells = cells)
    }

    private fun warnAndOffer(u: UpcomingUnderpass) = listOf(Action.WarnUnderpass(u), Action.OfferReroute(u))

    @Test
    fun warnsAndOffersRerouteForWarningUnderpass() {
        val u = up("a", Level.WARNING)
        assertEquals(warnAndOffer(u), decide(u))
    }

    @Test
    fun warnsWithoutOfferWhenNoBypass() {
        val u = up("a", Level.WARNING, withBypass = false)
        assertEquals(listOf(Action.WarnUnderpass(u)), decide(u))
    }

    @Test
    fun ignoresUnderpassBeyondTwoKm() {
        assertEquals(emptyList<Action>(), decide(up("a", Level.DANGER, distanceM = 2001)))
    }

    @Test
    fun cooldownFiveMinutes() {
        val u = up("a", Level.WARNING)
        decide(u)
        at(299)
        assertEquals(emptyList<Action>(), decide(u))
        at(301)
        assertEquals(warnAndOffer(u), decide(u))
    }

    @Test
    fun declinedNotAskedAgainUntilLevelRises() {
        val w = up("a", Level.WARNING)
        decide(w)
        decider.onRerouteDeclined("a", Level.WARNING)
        at(400)
        assertEquals(emptyList<Action>(), decide(w))
        at(800)
        val d = up("a", Level.DANGER)
        assertEquals(warnAndOffer(d), decide(d))
    }

    @Test
    fun levelRiseSkipsCooldown() {
        decide(up("a", Level.CAUTION))
        at(60)
        val w = up("a", Level.WARNING)
        assertEquals(warnAndOffer(w), decide(w))
    }

    @Test
    fun levelDropIsNotAnnounced() {
        decide(up("a", Level.DANGER))
        at(10)
        assertEquals(emptyList<Action>(), decide(up("a", Level.CAUTION)))
    }

    @Test
    fun parkedDoesNothing() {
        assertEquals(emptyList<Action>(), decide(up("a", Level.DANGER), hazards(offline = true), gear = "P"))
    }

    @Test
    fun cautionOncePerApproach() {
        val u = up("a", Level.CAUTION)
        assertEquals(listOf(Action.InformUnderpass(u)), decide(u))
        for (s in 10..400 step 10) {
            at(s)
            assertEquals("t=$s", emptyList<Action>(), decide(u))
        }
    }

    @Test
    fun cautionAgainOnNewApproach() {
        val u = up("a", Level.CAUTION)
        decide(u)
        at(10)
        decide(u)
        at(71)
        assertEquals(listOf(Action.InformUnderpass(u)), decide(u))
    }

    @Test
    fun briefGapIsSameApproach() {
        val u = up("a", Level.CAUTION)
        decide(u)
        at(10)
        decide(null)
        at(40)
        assertEquals(emptyList<Action>(), decide(u))
    }

    @Test
    fun mutedCautionStaysSilentButWarningSpeaks() {
        decider.onCautionMuted()
        assertEquals(emptyList<Action>(), decide(up("a", Level.CAUTION)))
        val w = up("b", Level.WARNING)
        assertEquals(warnAndOffer(w), decide(w))
    }

    @Test
    fun parkingClearsMute() {
        decider.onCautionMuted()
        val u = up("a", Level.CAUTION)
        assertEquals(emptyList<Action>(), decide(u))
        decide(gear = "P")
        assertEquals(listOf(Action.InformUnderpass(u)), decide(u))
    }

    @Test
    fun showOfflineOnlyOnTransition() {
        assertEquals(listOf(Action.ShowOffline), decide(hz = hazards(offline = true)))
        assertEquals(emptyList<Action>(), decide(hz = hazards(offline = true)))
        decide(hz = hazards(offline = false))
        assertEquals(listOf(Action.ShowOffline), decide(hz = hazards(offline = true)))
    }

    @Test
    fun outputOrderIsOfflineUnderpassThenRain() {
        val u = up("a", Level.WARNING)
        val out = decide(u, Hazards(rain(null, 15.0).rainCells, emptyList(), offline = true))
        assertEquals(listOf(Action.ShowOffline) + warnAndOffer(u), out.dropLast(1))
        assertTrue(out.last() is Action.PrepareForRain)
    }

    @Test
    fun preparesForRainAhead() {
        assertEquals(
            listOf(Action.PrepareForRain(heavier = false, closeWindows = true, defog = true, headlights = true)),
            decide(hz = rain(null, 15.0)),
        )
        at(10)
        assertEquals(
            listOf(Action.PrepareForRain(heavier = true, closeWindows = true, defog = true, headlights = true)),
            decide(hz = rain(15.0, 35.0)),
        )
    }

    @Test
    fun noPrepWhenAheadStaysBelowNextTier() {
        assertEquals(emptyList<Action>(), decide(hz = rain(15.0, 25.0)))
    }

    @Test
    fun respectsPrepSettings() {
        val out = decide(hz = rain(null, 15.0), s = Settings(prepDefog = false))
        assertEquals(Action.PrepareForRain(heavier = false, closeWindows = true, defog = false, headlights = true), out.single())
    }

    @Test
    fun prepEscalationSkipsCooldown() {
        decide(hz = rain(null, 15.0))
        at(120)
        val out = decide(hz = rain(null, 35.0))
        assertTrue((out.single() as Action.PrepareForRain).heavier)
    }

    @Test
    fun prepNoDowngradeWithinCooldown() {
        decide(hz = rain(null, 35.0))
        at(120)
        assertEquals(emptyList<Action>(), decide(hz = rain(null, 15.0)))
        at(601)
        assertFalse((decide(hz = rain(null, 15.0)).single() as Action.PrepareForRain).heavier)
    }

    @Test
    fun prepRepeatsAfterTenMinutes() {
        decide(hz = rain(null, 15.0))
        at(599)
        assertEquals(emptyList<Action>(), decide(hz = rain(null, 15.0)))
        at(600)
        assertEquals(1, decide(hz = rain(null, 15.0)).size)
    }

    @Test
    fun headingOnlyNeedsTwoCalls() {
        val a = up("a", Level.WARNING, onRoute = false)
        assertEquals(emptyList<Action>(), decide(a))
        at(1)
        assertEquals(warnAndOffer(a), decide(a))
    }

    @Test
    fun headingOnlyDifferentIdResetsConfirmation() {
        val a = up("a", Level.WARNING, onRoute = false)
        val b = up("b", Level.WARNING, onRoute = false)
        decide(a)
        at(1)
        assertEquals(emptyList<Action>(), decide(b))
        at(2)
        assertEquals(emptyList<Action>(), decide(a))
        at(3)
        assertEquals(warnAndOffer(a), decide(a))
    }

    @Test
    fun onRouteAnnouncesImmediately() {
        val u = up("a", Level.WARNING, onRoute = true)
        assertEquals(warnAndOffer(u), decide(u))
    }

    @Test
    fun acceptedRerouteSilentUntilNewApproach() {
        decide(up("a", Level.WARNING))
        decider.onRerouteAccepted("a")
        at(10)
        assertEquals(emptyList<Action>(), decide(up("a", Level.DANGER)))
        at(71)
        decide(null)
        at(72)
        val d = up("a", Level.DANGER)
        assertEquals(warnAndOffer(d), decide(d))
    }

    @Test
    fun parkingClearsDecline() {
        val w = up("a", Level.WARNING)
        decide(w)
        decider.onRerouteDeclined("a", Level.WARNING)
        at(400)
        assertEquals(emptyList<Action>(), decide(w))
        decide(gear = "P")
        at(401)
        assertEquals(warnAndOffer(w), decide(w))
    }

    @Test
    fun dangerThenWarningRemindsAfterCooldown() {
        decide(up("a", Level.DANGER))
        at(100)
        assertEquals(emptyList<Action>(), decide(up("a", Level.WARNING)))
        at(300)
        val w = up("a", Level.WARNING)
        assertEquals(warnAndOffer(w), decide(w))
    }

    private fun rainAt(lat: Double, lng: Double = 127.03) = hazards(cells = listOf(RainCell(lat, lng, 15.0)))

    @Test
    fun noPrepForCellBehind() {
        assertEquals(emptyList<Action>(), decide(hz = rainAt(37.482)))
    }

    @Test
    fun noPrepForCellOffAxis() {
        assertEquals(emptyList<Action>(), decide(hz = rainAt(37.50, 127.0527)))
    }

    @Test
    fun noPrepForCellNearerThanSixtySeconds() {
        assertEquals(emptyList<Action>(), decide(hz = rainAt(37.5063)))
    }

    @Test
    fun noPrepForCellFartherThanThreeMinutes() {
        assertEquals(emptyList<Action>(), decide(hz = rainAt(37.545)))
    }
}
