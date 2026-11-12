package ai.wipernet.car.act

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit

private const val ANSWER_S = 8
private const val LATE_RESULT_MS = 1_500L
private const val INIT_WAIT_MS = 5_000L

interface ConsentPrompt {
    /** 예/아니오. 무응답이면 null. 질문은 부르는 쪽이 이미 말했다. */
    suspend fun askYesNo(question: String): Boolean?

    /** 질문 없이 듣기만 한다. 들은 글자, 없으면 null. */
    suspend fun listen(seconds: Int): String?
}

// 아니오를 먼저 본다: "아니에요", "그래도 그냥 갈게"는 아니오다.
private val NO = listOf("아니", "아뇨", "싫어", "됐어", "괜찮아", "그냥")
private val YES = listOf("응", "네", "예", "그래")
private val MUTE = listOf("그만", "알았어", "알겠어", "적당히")

fun parseYesNo(text: String?): Boolean? = when {
    text == null -> null
    NO.any { it in text } -> false
    YES.any { it in text } -> true
    else -> null
}

fun isMuteRequest(text: String?): Boolean = text != null && MUTE.any { it in text }

/**
 * Pleos STT(ON_DEVICE)로 듣는다. 말이 없으면 인식이 스스로 끝나지 않아 시간이 되면 stop()을 부르고,
 * 최종 글자(completed = true)는 onEndedRecognition 직후에 오므로 조금 더 기다린다(D2, 2026-11-10 확인).
 */
class VoicePrompt(context: Context) : ConsentPrompt {
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()

    @Volatile private var pending: CompletableDeferred<String>? = null

    init {
        stt.initialize(object : SttInit {
            override fun onSuccess() {
                stt.addListener(object : ResultListener {
                    override fun onUpdated(stt: String, completed: Boolean) {
                        if (completed) pending?.complete(stt)
                    }

                    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                    override fun onStartedRecognition() = Unit
                    override fun onEndedRecognition() = Unit
                    override fun onReady() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onError(errorType: SttError) {
                        pending?.complete("")
                    }
                })
                stt.setLocale(Locale.KOREA)
                ready.complete(true)
            }

            override fun onFailure(errorType: SttError) {
                ready.complete(false)
            }
        })
    }

    override suspend fun askYesNo(question: String): Boolean? = parseYesNo(listen(ANSWER_S))

    override suspend fun listen(seconds: Int): String? {
        if (withTimeoutOrNull(INIT_WAIT_MS) { ready.await() } != true) return null
        val result = CompletableDeferred<String>()
        pending = result
        try {
            stt.request()
            val text = withTimeoutOrNull(seconds * 1000L) { result.await() }
                ?: run {
                    stt.stop()
                    withTimeoutOrNull(LATE_RESULT_MS) { result.await() }
                }
            return text?.takeIf { it.isNotBlank() }
        } finally {
            // 알림 버튼 답이 먼저 와 취소되면 마이크를 닫는다. 그대로 두면 다음 말이 다음 듣기의 답이 된다.
            if (!result.isCompleted) stt.stop()
            pending = null
        }
    }

    fun release() = stt.release()
}
