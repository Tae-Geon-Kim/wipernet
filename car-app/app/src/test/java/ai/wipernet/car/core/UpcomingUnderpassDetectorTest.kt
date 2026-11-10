package ai.wipernet.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UpcomingUnderpassDetectorTest {
    private val pos = Position(37.50, 127.03, headingDeg = 0.0, speedMps = 15.0)
    private val enter = "TURN_ENTER_UNDER_C"

    private fun underpassNorth(id: String, km: Double) =
        UnderpassRisk(id, id, 37.50 + km * 0.009, 127.03, Level.WARNING, null)

    private fun guided(tbts: List<Tbt>, vararg u: UnderpassRisk) =
        UpcomingUnderpassDetector.detect(pos, tbts, guiding = true, underpasses = u.toList())

    private fun heading(vararg u: UnderpassRisk) =
        UpcomingUnderpassDetector.detect(pos, emptyList(), guiding = false, underpasses = u.toList())

    @Test
    fun sumsTbtSegments() {
        val u = underpassNorth("c", 2.05)
        val found = guided(listOf(Tbt("TURN_LEFT", 300), Tbt("TURN_ENTER_UNDER_L", 1500)), u)
        assertEquals(UpcomingUnderpass(u, 1800, onRoute = true), found)
    }

    @Test
    fun picksUnderpassWhoseDistanceIsClosestToRouteDistance() {
        val a = underpassNorth("a", 1.9)
        val b = underpassNorth("b", 2.2)
        assertEquals("a", guided(listOf(Tbt(enter, 1800)), b, a)!!.risk.id)
    }

    @Test
    fun guidingIgnoresEntryBeyondTwoKm() {
        val tbts = listOf(Tbt("TURN_LEFT", 500), Tbt(enter, 1600))
        assertNull(guided(tbts, underpassNorth("x", 2.1)))
    }

    @Test
    fun guidingWithoutUnderpassEntryIsNull() {
        assertNull(guided(listOf(Tbt("TURN_LEFT", 500)), underpassNorth("ahead", 1.0)))
    }

    @Test
    fun guidingIgnoresUnderpassFarBeyondEntry() {
        assertNull(guided(listOf(Tbt(enter, 800)), underpassNorth("x", 1.5)))
    }

    @Test
    fun headingIgnoresUnderpassBehind() {
        assertNull(heading(underpassNorth("b", -0.5)))
    }

    @Test
    fun headingFindsNearestAheadWithRoundedDistance() {
        val found = heading(underpassNorth("far", 1.5), underpassNorth("near", 1.0))!!
        assertEquals(UpcomingUnderpass(underpassNorth("near", 1.0), 1001, onRoute = false), found)
    }

    @Test
    fun headingIgnoresBeyondTwoKm() {
        assertNull(heading(underpassNorth("far", 2.5)))
    }

    @Test
    fun headingIgnoresOffAxis() {
        assertNull(heading(UnderpassRisk("e", "e", 37.50, 127.03 + 0.0114, Level.WARNING, null)))
    }

    @Test
    fun headingMatchIsNotOnRoute() {
        assertFalse(heading(underpassNorth("n", 1.0))!!.onRoute)
    }
}
