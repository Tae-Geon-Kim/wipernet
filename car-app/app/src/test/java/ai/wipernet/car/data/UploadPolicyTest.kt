package ai.wipernet.car.data

import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Tbt
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPolicyTest {
    private val c1 = 10L to 20L
    private val c2 = 10L to 21L

    @Serializable
    private data class LastJson(
        @SerialName("at_s") val atS: Double,
        val switch: String,
        val state: String,
        @SerialName("auto_wpm") val autoWpm: Double? = null,
        val cell: List<Long>,
    )

    @Serializable
    private data class Case(
        val name: String,
        @SerialName("now_s") val nowS: Double,
        val guiding: Boolean,
        val switch: String,
        val state: String,
        @SerialName("auto_wpm") val autoWpm: Double? = null,
        val cell: List<Long>,
        val last: LastJson? = null,
        val send: Boolean,
    )

    @Test fun matchesSharedCases() {
        val cases = wireJson.decodeFromString<List<Case>>(javaClass.getResource("/upload_policy_cases.json")!!.readText())
        assertEquals(19, cases.size)
        for (c in cases) {
            val last = c.last?.let { LastSent((it.atS * 1000).toLong(), it.switch, it.state, it.autoWpm, it.cell[0] to it.cell[1]) }
            val got = UploadPolicy.shouldSend((c.nowS * 1000).toLong(), c.guiding, c.switch, c.state, c.autoWpm, c.cell[0] to c.cell[1], last)
            assertEquals(c.name, c.send, got)
        }
    }

    @Test fun wiperOnSendsOnChangeAndEveryMinute() {
        val last = LastSent(0, "CONTINUOUS_LEVEL_3", "ON", null, c1)
        assertFalse(UploadPolicy.shouldSend(59_000, false, "CONTINUOUS_LEVEL_3", "ON", null, c1, last))
        assertTrue(UploadPolicy.shouldSend(60_000, false, "CONTINUOUS_LEVEL_3", "ON", null, c1, last))
        assertTrue(UploadPolicy.shouldSend(10_000, false, "CONTINUOUS_LEVEL_4", "ON", null, c1, last))
    }

    @Test fun offWhileGuidingSendsOnCellChangeAndEveryFiveMinutes() {
        val off = LastSent(0, "OFF", "OFF", null, c1)
        assertFalse(UploadPolicy.shouldSend(299_000, true, "OFF", "OFF", null, c1, off))
        assertTrue(UploadPolicy.shouldSend(300_000, true, "OFF", "OFF", null, c1, off))
        assertTrue(UploadPolicy.shouldSend(10_000, true, "OFF", "OFF", null, c2, off))
    }

    @Test fun autoWipeCounterCountsLastMinute() {
        var now = 0L
        val counter = AutoWipeCounter { now }
        repeat(10) {
            counter.onState("ON"); now += 1_000
            counter.onState("OFF"); now += 2_000
        }
        assertEquals(10.0, counter.wpm(), 0.0)
        now += 61_000
        assertEquals(0.0, counter.wpm(), 0.0)
        counter.onState("ON")
        now += 20_000
        assertEquals(40.0, counter.wpm(), 0.0)
    }

    @Test fun nearRouteEndsIsPrivate() {
        val start = LatLng(37.5, 127.0)
        val far = listOf(Tbt("TURN_LEFT", 800), Tbt("TURN_ARRIVE", 900))
        val near = listOf(Tbt("TURN_ARRIVE", 250))
        assertTrue(nearRouteEnds(LatLng(37.5010, 127.0), start, far))
        assertFalse(nearRouteEnds(LatLng(37.5040, 127.0), start, far))
        assertTrue(nearRouteEnds(LatLng(37.5040, 127.0), start, near))
        assertFalse(nearRouteEnds(LatLng(37.5040, 127.0), null, emptyList()))
    }
}
