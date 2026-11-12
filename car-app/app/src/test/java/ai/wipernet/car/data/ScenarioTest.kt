package ai.wipernet.car.data

import ai.wipernet.car.core.LatLng
import ai.wipernet.car.core.Level
import ai.wipernet.car.core.Tbt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScenarioTest {
    private val scenario = Scenario.parse(javaClass.getResource("/scenario_small.json")!!.readText())

    @Test fun scenarioParses() {
        assertEquals(5, scenario.myCar.size)
        assertEquals(Tbt("TURN_ENTER_UNDER_C", 1998), scenario.myCar.first { it.tbt != null }.tbt!!.toModel())
        assertEquals(210, scenario.bypass.fromT)
        val gaepo = scenario.hazards.last().underpasses.first().toModel()
        assertEquals(Level.WARNING, gaepo.level)
        assertEquals(LatLng(37.475909, 127.058771), gaepo.bypass)
        assertEquals(listOf("vehicles", "kma_warning"), gaepo.reasons)
    }

    @Test fun realDemoScenarioParses() {
        val real = Scenario.parse(java.io.File("src/main/assets/demo/scenario.json").readText())
        assertEquals(real.myCar.size, real.myCar.map { it.t }.distinct().size)
    }

    @Test fun replayEmitsFramesOnScenarioTime() = runTest {
        val signals = ReplayVehicleSignals(scenario, backgroundScope, speed = 1.0)
        runCurrent()
        assertEquals(0, signals.t.value)
        assertEquals(Pair("CONTINUOUS_LEVEL_3", "ON"), signals.wiper.first())
        advanceTimeBy(5_001)
        assertEquals(5, signals.t.value)
        advanceTimeBy(200_000)
        assertEquals(205, signals.t.value)
        assertEquals(emptyList<Tbt>(), signals.tbts.first())
        advanceTimeBy(5_000)
        assertEquals(210, signals.t.value)
        assertEquals(listOf(Tbt("TURN_ENTER_UNDER_C", 1998)), signals.tbts.first())
    }

    @Test fun replaySpeedShortensWaits() = runTest {
        val signals = ReplayVehicleSignals(scenario, backgroundScope, speed = 5.0)
        advanceTimeBy(1_001)
        assertEquals(5, signals.t.value)
    }

    @Test fun switchToBypassFollowsBypassTrackAfterFromT() = runTest {
        val shifted = scenario.bypass.myCar.map { it.copy(lat = it.lat + 0.01) }
        val sc = scenario.copy(bypass = scenario.bypass.copy(myCar = shifted))
        val signals = ReplayVehicleSignals(sc, backgroundScope, speed = 1.0)
        signals.switchToBypass()
        advanceTimeBy(210_001)
        assertEquals(scenario.myCar.first { it.t == 210 }.lat, signals.position.first().lat, 0.0)
        advanceTimeBy(5_000)
        assertEquals(215, signals.t.value)
        assertEquals(shifted.first { it.t == 215 }.lat, signals.position.first().lat, 0.0)
        advanceTimeBy(5_000)
        assertEquals(220, signals.t.value)
    }

    @Test fun replayHazardsUseLatestFrameAtOrBeforeT() = runTest {
        val signals = ReplayVehicleSignals(scenario, backgroundScope, speed = 1.0)
        val client = ReplayHazardClient(scenario, signals.t, backgroundScope)
        runCurrent()
        assertEquals(Level.CAUTION, client.hazards.value.underpasses.first().level)
        advanceTimeBy(205_001)
        assertEquals(Level.CAUTION, client.hazards.value.underpasses.first().level)
        advanceTimeBy(5_000)
        assertEquals(Level.WARNING, client.hazards.value.underpasses.first().level)
        assertFalse(client.hazards.value.offline)
        assertNull(client.hazards.value.serverClock)
    }
}
