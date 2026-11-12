package ai.wipernet.car.act

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 두 응답(음성, 알림 버튼) 중 먼저 온 예/아니오를 쓴다. 한쪽이 null로 끝나면 다른 쪽을 기다리고,
 * 답이 오면 나머지는 취소한다(VoicePrompt는 취소되면 마이크를 닫는다). 듣기만 할 때는 첫 번째를 쓴다.
 * 제한 10초: 음성은 8초 듣고 최종 글자를 1.5초 더 기다리므로, 8초로 자르면 끝에 한 대답을 버린다.
 */
class EitherPrompt(private val first: ConsentPrompt, private val second: ConsentPrompt, private val timeoutMs: Long = 10_000) :
    ConsentPrompt {
    override suspend fun askYesNo(question: String): Boolean? = withTimeoutOrNull(timeoutMs) {
        merge(flow { emit(first.askYesNo(question)) }, flow { emit(second.askYesNo(question)) })
            .filterNotNull()
            .firstOrNull()
    }

    override suspend fun listen(seconds: Int): String? = first.listen(seconds)
}
