package ai.wipernet.car.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream

/** POST /v1/wiper-events 이벤트 하나(docs/api.md). */
@Serializable
data class WiperEvent(
    val vid: String,
    val ts: String,
    val lat: Double,
    val lng: Double,
    val switch: String,
    val state: String,
    @SerialName("auto_wpm") val autoWpm: Double? = null,
)

interface QueueStore {
    fun load(): List<WiperEvent>
    fun save(events: List<WiperEvent>)
}

class MemoryQueueStore : QueueStore {
    private var saved = emptyList<WiperEvent>()
    override fun load() = saved
    override fun save(events: List<WiperEvent>) {
        saved = events.toList()
    }
}

/**
 * 한 줄에 이벤트 하나(JSON). 임시 파일에 다 쓰고 디스크에 내린 뒤 바꿔 넣는다.
 * 차 전원이 갑자기 꺼져 깨진 줄이 남으면 그 줄만 버린다.
 */
class FileQueueStore(private val file: File) : QueueStore {
    override fun load(): List<WiperEvent> =
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line -> runCatching { wireJson.decodeFromString<WiperEvent>(line) }.getOrNull() }

    override fun save(events: List<WiperEvent>) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(events.joinToString("") { wireJson.encodeToString(it) + "\n" }.toByteArray())
            out.fd.sync()
        }
        tmp.renameTo(file)
    }
}

/**
 * 보낼 이벤트를 순서대로 모은다. cap을 넘으면 가장 오래된 것부터 버린다.
 * 파일 읽기·쓰기는 IO 스레드에서 한다.
 */
class UploadQueue(private val store: QueueStore, private val cap: Int = 5_000, private val batch: Int = 200) {
    private val events by lazy { ArrayDeque(store.load().takeLast(cap)) }
    private val flushing = Mutex()

    suspend fun size(): Int = locked { events.size }

    suspend fun enqueue(e: WiperEvent) = locked {
        events.addLast(e)
        while (events.size > cap) events.removeFirst()
        store.save(events)
    }

    /** 앞에서부터 batch개씩 보낸다. send가 false면 멈추고 남은 것은 다음에 보낸다. 동시에 한 번만 돈다. */
    suspend fun flush(send: suspend (List<WiperEvent>) -> Boolean) = flushing.withLock {
        while (true) {
            val chunk = locked { events.take(batch) }
            if (chunk.isEmpty() || !send(chunk)) return@withLock
            locked {
                // 보내는 동안 cap으로 버려졌거나 clear된 것은 이미 없다. 보낸 객체만 지운다.
                events.removeAll { e -> chunk.any { it === e } }
                store.save(events)
            }
        }
    }

    suspend fun clear() = locked {
        events.clear()
        store.save(events)
    }

    private suspend fun <T> locked(f: () -> T): T = withContext(Dispatchers.IO) { synchronized(this@UploadQueue) { f() } }
}
