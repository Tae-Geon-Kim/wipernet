package ai.wipernet.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpcomingUnderpassDetectorTest {
    private val pos = Position(37.50, 127.03, headingDeg = 0.0, speedMps = 15.0)

    private fun underpassNorth(id: String, km: Double) =
        UnderpassRisk(id, id, 37.50 + km * 0.009, 127.03, Level.WARNING, null)

    @Test
    fun detectorUsesTbtDistance() {
        val u = underpassNorth("c", 1.75)
        val found = UpcomingUnderpassDetector.detect(pos, Tbt("TURN_ENTER_UNDER_C", 1800), listOf(u))
        assertEquals(UpcomingUnderpass(u, 1800), found)
    }

    @Test
    fun detectorFallbackIgnoresUnderpassBehind() {
        assertNull(UpcomingUnderpassDetector.detect(pos, null, listOf(underpassNorth("b", -0.5))))
    }

    @Test
    fun detectorFallbackFindsNearestAheadWithRoundedDistance() {
        val near = underpassNorth("near", 1.0)
        val far = underpassNorth("far", 1.5)
        val found = UpcomingUnderpassDetector.detect(pos, null, listOf(far, near))!!
        assertEquals("near", found.risk.id)
        assertEquals(1000.0, found.distanceM.toDouble(), 5.0)
    }

    @Test
    fun detectorFallbackIgnoresBeyondTwoKm() {
        assertNull(UpcomingUnderpassDetector.detect(pos, null, listOf(underpassNorth("far", 2.5))))
    }

    @Test
    fun detectorFallbackIgnoresOffAxis() {
        val east = UnderpassRisk("e", "e", 37.50, 127.03 + 0.0114, Level.WARNING, null)
        assertNull(UpcomingUnderpassDetector.detect(pos, null, listOf(east)))
    }

    @Test
    fun detectorTbtIgnoresUnderpassFarFromProjectedPoint() {
        assertNull(UpcomingUnderpassDetector.detect(pos, Tbt("TURN_ENTER_UNDER_C", 1800), listOf(underpassNorth("x", 1.0))))
    }

    @Test
    fun detectorNonUnderpassTbtFallsBack() {
        val u = underpassNorth("u", 1.0)
        val found = UpcomingUnderpassDetector.detect(pos, Tbt("TURN_LEFT", 100), listOf(u))!!
        assertEquals(1000.0, found.distanceM.toDouble(), 5.0)
    }
}
