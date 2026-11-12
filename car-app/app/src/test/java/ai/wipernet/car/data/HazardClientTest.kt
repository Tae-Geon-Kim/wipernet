package ai.wipernet.car.data

import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Level
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardClientTest {
    private val server = MockWebServer()
    private var failing = false
    private var postCode = 202
    private var clockJson = """{"now":"2022-08-08T20:00:00+09:00"}"""

    private val rainMap = """{"at":"2022-08-08T20:00:00+09:00","cell":{"lat":0.0045,"lng":0.0057},"cells":[
        {"row":1,"col":1,"lat":37.5001,"lng":127.0001,"mmph":25.0,"acc1h_mm":18.5,"vehicles":4,"saturated_min":0,"bins":[]},
        {"row":2,"col":2,"lat":37.6000,"lng":127.0000,"mmph":40.0,"acc1h_mm":30.0,"vehicles":3,"saturated_min":0,"bins":[]}]}"""
    private val risk = """{"at":"2022-08-08T20:00:00+09:00","underpasses":[
        {"id":"gaepo","name":"개포지하차도","lat":37.475985,"lng":127.058793,"level":"DANGER","mmph_10m":60.0,"acc1h_mm":52.0,
         "bypass_waypoint":{"lat":37.475909,"lng":127.058771},"reasons":["vehicles","saturated"],"alerts":[]},
        {"id":"x","name":"x","lat":37.0,"lng":127.0,"level":"NONE","mmph_10m":0.0,"acc1h_mm":0.0,"bypass_waypoint":null,"reasons":[],"alerts":[]}]}"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failing) return MockResponse().setResponseCode(500)
                return when (request.path) {
                    "/v1/clock" -> MockResponse().setBody(clockJson)
                    "/v1/rain-map" -> MockResponse().setBody(rainMap)
                    "/v1/underpass-risk" -> MockResponse().setBody(risk)
                    "/v1/wiper-events" -> MockResponse().setResponseCode(postCode).setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val api = ServerApi(server.url("/").toString().trimEnd('/'))
    private fun client(at: LatLng? = LatLng(37.5, 127.0)) = LiveHazardClient(api) { at }

    @Test fun liveClientExposesServerClockAndRisks() = runTest {
        val c = client()
        c.poll()
        val h = c.hazards.value
        assertEquals("2022-08-08T20:00:00+09:00", h.serverClock)
        assertFalse(h.offline)
        val gaepo = h.underpasses.first()
        assertEquals(Level.DANGER, gaepo.level)
        assertEquals(LatLng(37.475909, 127.058771), gaepo.bypass)
        assertEquals(listOf("vehicles", "saturated"), gaepo.reasons)
        assertNull(h.underpasses.last().bypass)
    }

    @Test fun keepsOnlyCellsWithin3km() = runTest {
        val c = client()
        c.poll()
        assertEquals(listOf(25.0), c.hazards.value.rainCells.map { it.mmph })
    }

    @Test fun nullServerClockStaysNull() = runTest {
        clockJson = """{"now":null}"""
        val c = client()
        c.poll()
        assertNull(c.hazards.value.serverClock)
    }

    @Test fun liveClientGoesOfflineAfterTwoFailures() = runTest {
        val c = client()
        c.poll()
        failing = true
        c.poll()
        assertFalse(c.hazards.value.offline)
        c.poll()
        assertTrue(c.hazards.value.offline)
        assertEquals(2, c.hazards.value.underpasses.size)
        failing = false
        c.poll()
        assertFalse(c.hazards.value.offline)
    }

    @Test fun unreachableServerCountsAsFailure() = runTest {
        val c = LiveHazardClient(ServerApi("http://127.0.0.1:1")) { null }
        c.poll()
        c.poll()
        assertTrue(c.hazards.value.offline)
    }

    @Test fun postEventsSendsWireFieldsWithoutAutoWpmWhenAbsent() = runTest {
        val ok = api.postEvents(listOf(WiperEvent("v1", "2022-08-08T20:00:00+09:00", 37.5, 127.0, "CONTINUOUS_LEVEL_3", "ON")))
        assertTrue(ok)
        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            """{"events":[{"vid":"v1","ts":"2022-08-08T20:00:00+09:00","lat":37.5,"lng":127.0,"switch":"CONTINUOUS_LEVEL_3","state":"ON"}]}""",
            body,
        )
    }

    @Test fun postEventsReportsFailure() = runTest {
        failing = true
        assertFalse(api.postEvents(listOf(WiperEvent("v1", "t", 0.0, 0.0, "OFF", "OFF"))))
    }

    @Test fun rejectedBatchIsDroppedNotRetried() = runTest {
        postCode = 422
        assertTrue(api.postEvents(listOf(WiperEvent("v1", "t", 0.0, 0.0, "OFF", "OFF"))))
    }

    @Test fun badServerUrlCountsAsFailureInsteadOfCrashing() = runTest {
        for (url in listOf("", "10.0.2.2:8000", "htt")) {
            val c = LiveHazardClient(ServerApi(url)) { null }
            c.poll()
            c.poll()
            assertTrue(url, c.hazards.value.offline)
            assertFalse(url, ServerApi(url).postEvents(listOf(WiperEvent("v1", "t", 0.0, 0.0, "OFF", "OFF"))))
        }
    }

    @Test fun trailingSlashInServerUrlWorks() = runTest {
        val c = LiveHazardClient(ServerApi(server.url("/").toString())) { null }
        c.poll()
        assertEquals("2022-08-08T20:00:00+09:00", c.hazards.value.serverClock)
    }
}
