package ai.wipernet.car.service

import ai.wipernet.car.WiperNetApp
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 포그라운드 서비스(A15). 앱 화면을 닫아도 AppController가 계속 돈다. 동의한 뒤 MainActivity가 시작하고,
 * 철회하면 WiperNetApp이 멈춘다. 마이크(음성 응답)를 쓰므로 microphone 타입으로 시작하고, 부팅 직후처럼
 * 백그라운드에서 그 타입이 막히면 specialUse로 시작한다.
 */
class WiperNetService : Service() {
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val app = application as WiperNetApp
        val notification = app.controller.notifications.running()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_RUNNING, notification)
            } else try {
                startForeground(NOTIFICATION_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: SecurityException) {
                Log.w("WiperNet", "microphone 타입으로 시작 못 함, specialUse로 시작", e)
                startForeground(NOTIFICATION_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } catch (e: IllegalStateException) {
            // 시스템이 백그라운드에서 다시 띄울 때는 포그라운드 시작이 막힐 수 있다. 다음 실행·부팅 때 다시 시작한다.
            Log.w("WiperNet", "포그라운드로 시작할 수 없어 멈춤", e)
            stopSelf()
            return
        }
        app.controller.start(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        (application as WiperNetApp).controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) = context.startForegroundService(Intent(context, WiperNetService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WiperNetService::class.java))
    }
}

/** 부팅 때 동의한 상태면 서비스를 시작한다. Pleos에서 차가 켜질 때 이 방송이 오는지는 에뮬레이터에서 확인한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as WiperNetApp
        val pending = goAsync()
        app.scope.launch {
            try {
                if (app.settingsStore.await().settings.value.consented) {
                    Log.i("WiperNet", "boot completed, starting service")
                    WiperNetService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
