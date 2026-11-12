package ai.wipernet.car.service

import ai.wipernet.car.MainActivity
import ai.wipernet.car.R
import ai.wipernet.car.core.Action
import ai.wipernet.car.core.Level
import ai.wipernet.car.core.UpcomingUnderpass
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Locale

private const val CHANNEL_SERVICE = "service"
private const val CHANNEL_ALERTS = "alerts"
const val NOTIFICATION_RUNNING = 1
private const val NOTIFICATION_ALERT = 2

/** 상시 알림(서비스)과 경고 알림. 경고는 알림만 띄우고 앱 화면을 앞으로 띄우지 않는다(설계 5.6-3). */
class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun running(): Notification = Notification.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(context.getString(R.string.notif_running))
        .setContentIntent(openApp())
        .setOngoing(true)
        .build()

    /** 경고·주의 안내·오프라인만 알림으로도 띄운다. */
    fun alert(action: Action) {
        val text = when (action) {
            is Action.WarnUnderpass -> underpassLine(action.u)
            is Action.InformUnderpass -> underpassLine(action.u)
            Action.ShowOffline -> context.getString(R.string.notif_offline)
            else -> return
        }
        manager.notify(
            NOTIFICATION_ALERT,
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    fun channelForPrompts(): String = CHANNEL_ALERTS

    fun underpassLine(u: UpcomingUnderpass): String =
        "${u.risk.name} ${levelName(u.risk.level)} · ${String.format(Locale.US, "%.1f", u.distanceM / 1000.0)}km 앞"

    fun levelName(level: Level): String = when (level) {
        Level.DANGER -> context.getString(R.string.level_danger)
        Level.WARNING -> context.getString(R.string.level_warning)
        Level.CAUTION -> context.getString(R.string.level_caution)
        Level.NONE -> ""
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
