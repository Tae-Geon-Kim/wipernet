package ai.wipernet.car.data

import java.util.UUID
import kotlin.random.Random

private const val MIN_MS = 720_000L
private const val MAX_MS = 1_080_000L

/** 임시 차량 ID. 12~18분 사이 무작위 간격마다 새 UUID로 바뀐다(설계 6.11, 시뮬레이터 S5와 같다). */
class TempId(private val clock: () -> Long, private val rng: Random = Random.Default) {
    private var id = newId()
    private var rotateAt = clock() + interval()

    @Synchronized
    fun current(): String {
        val now = clock()
        if (now >= rotateAt) {
            id = newId()
            rotateAt = now + interval()
        }
        return id
    }

    private fun interval() = rng.nextLong(MIN_MS, MAX_MS + 1)
    private fun newId() = UUID.randomUUID().toString()
}
