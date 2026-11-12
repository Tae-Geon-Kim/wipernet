package ai.wipernet.car.act

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val CALL_TIMEOUT_MS = 2_000L
private const val CONFIRM_MS = 2_000L
private const val CONFIRM_STEP_MS = 250L

private val WINDSHIELD = WindowArea(WindowValue.FRONT_WINDSHIELD)
private val SIDE_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    .map { WindowArea(it) }

enum class PrepOutcome { APPLIED, ALREADY, NOT_APPLIED }

interface VehiclePrep {
    suspend fun closeWindows(): PrepOutcome
    suspend fun defog(): PrepOutcome
    suspend fun headlightsOn(): PrepOutcome
}

/**
 * Vehicle SDK로 직접 제어한다. 지금 값을 읽어 이미 목표면 ALREADY, 아니면 바꾼 뒤 2초 안에 다시 읽어
 * 목표와 같으면 APPLIED. 성공 콜백만으로는 판단하지 않는다(Virtual Console이 닫혀 있으면 콜백만 오고
 * 값은 그대로다, D2). 읽기가 안 되는 항목은 NOT_APPLIED라 음성으로 권한다.
 */
class PleosVehiclePrep(context: Context) : VehiclePrep {
    private val vehicle = Vehicle(context).apply { initialize() }

    override suspend fun closeWindows(): PrepOutcome {
        val window = vehicle.getWindow()
        suspend fun position(area: WindowArea) = call<Int?> { ok, err -> window.getCurrentWindowPosition(area, ok, err) }
        val before = SIDE_WINDOWS.mapNotNull { area -> position(area)?.let { area to it } }
        if (before.isEmpty()) return PrepOutcome.NOT_APPLIED
        val open = before.filter { it.second > 0 }.map { it.first }
        if (open.isEmpty()) return PrepOutcome.ALREADY
        open.forEach { area -> window.setWindowPosition(area, 0, { _, _ -> }, { }) }
        // 다시 읽지 못한 창은 닫혔다고 치지 않는다.
        return confirm { open.all { position(it) == 0 } }
    }

    override suspend fun defog(): PrepOutcome {
        val hvac = vehicle.getHVAC()
        suspend fun on() = call<Boolean?> { ok, err -> hvac.getHvacDefrosterState(WINDSHIELD, ok, err) }
        when (on()) {
            null -> return PrepOutcome.NOT_APPLIED
            true -> return PrepOutcome.ALREADY
            false -> Unit
        }
        hvac.setHvacDefroster(WINDSHIELD, true, { _, _ -> }, { })
        return confirm { on() == true }
    }

    override suspend fun headlightsOn(): PrepOutcome {
        val light = vehicle.getLight()
        suspend fun switch() = call<LightSwitch?> { ok, err -> light.getLowBeamLightSwitch(ok, err) }
        when (switch()) {
            null -> return PrepOutcome.NOT_APPLIED
            LightSwitch.ON -> return PrepOutcome.ALREADY
            else -> Unit
        }
        light.setLowBeamLightSwitch(LightSwitch.ON, { }, { })
        return confirm { switch() == LightSwitch.ON }
    }

    fun release() = vehicle.release()

    private suspend fun confirm(reached: suspend () -> Boolean): PrepOutcome =
        withTimeoutOrNull(CONFIRM_MS) {
            while (!reached()) delay(CONFIRM_STEP_MS)
            PrepOutcome.APPLIED
        } ?: PrepOutcome.NOT_APPLIED

    /** 콜백 API 한 번 부르기. 실패하거나 답이 없으면 null. */
    private suspend fun <T> call(block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit): T? {
        val answer = CompletableDeferred<T?>()
        block({ answer.complete(it) }, { answer.complete(null) })
        return withTimeoutOrNull(CALL_TIMEOUT_MS) { answer.await() }
    }
}
