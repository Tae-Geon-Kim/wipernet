package ai.wipernet.car.act

import ai.wipernet.car.R
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

private const val NOTIFICATION_PROMPT = 3
private const val EXTRA_ID = "id"
private const val EXTRA_YES = "yes"

/** 우회 질문을 시스템 알림의 예/아니오 버튼으로 받는다(설계 6.8-4). 답이 오거나 취소되면 알림을 지운다. */
class NotificationPrompt(private val context: Context, private val channel: String) : ConsentPrompt {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override suspend fun askYesNo(question: String): Boolean? {
        val id = nextId.incrementAndGet()
        manager.notify(
            NOTIFICATION_PROMPT,
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(question)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_yes), answer(id, true)).build())
                .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_no), answer(id, false)).build())
                .setAutoCancel(true)
                .build(),
        )
        try {
            return answers.first { it.first == id }.second
        } finally {
            manager.cancel(NOTIFICATION_PROMPT)
        }
    }

    override suspend fun listen(seconds: Int): String? = null

    private fun answer(id: Int, yes: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        id * 2 + if (yes) 1 else 0,
        Intent(context, Receiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_YES, yes),
        PendingIntent.FLAG_IMMUTABLE,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val answer = intent.getIntExtra(EXTRA_ID, -1) to intent.getBooleanExtra(EXTRA_YES, false)
            Log.i("WiperNet", "notification answer $answer")
            answers.tryEmit(answer)
        }
    }

    private companion object {
        val nextId = AtomicInteger()
        val answers = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 8)
    }
}
