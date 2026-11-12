package ai.wipernet.car.act

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

private const val MAX_SPEECH_MS = 20_000L
private const val INIT_WAIT_MS = 5_000L

interface Announcer {
    /** 말이 끝나면 돌아온다. */
    suspend fun say(text: String)
}

/** Pleos TTS(ON_DEVICE). 리스너는 initialize 성공 뒤에 붙여야 한다(먼저 붙이면 앱이 죽는다, D2). */
class PleosAnnouncer(context: Context) : Announcer {
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()
    private val speaking = Mutex()

    @Volatile private var done: CompletableDeferred<Unit>? = null

    init {
        tts.initialize(object : TtsInit {
            override fun onSuccess() {
                tts.addEventListener(object : EventListener {
                    override fun onReady() = Unit
                    override fun onStart() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onDone() { done?.complete(Unit) }
                    override fun onStop() { done?.complete(Unit) }
                    override fun onError(errorType: TtsError) { done?.complete(Unit) }
                })
                ready.complete(true)
            }

            override fun onFailure(errorType: TtsError) {
                ready.complete(false)
            }
        })
    }

    override suspend fun say(text: String) = speaking.withLock {
        if (withTimeoutOrNull(INIT_WAIT_MS) { ready.await() } != true) return@withLock
        val finished = CompletableDeferred<Unit>()
        done = finished
        try {
            tts.speak(text)
            withTimeoutOrNull(MAX_SPEECH_MS) { finished.await() }
        } finally {
            // 취소되면 말을 멈춘다. 남은 소리를 다음 듣기가 받아 적거나, 늦은 onDone이 다음 말을 끝내지 않게.
            if (!finished.isCompleted) tts.stop()
            done = null
        }
    }

    fun release() = tts.release()
}
