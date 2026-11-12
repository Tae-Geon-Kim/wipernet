package ai.wipernet.car.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ev(n: Int) = WiperEvent("vid", "2022-08-08T20:00:%02d+09:00".format(n), 37.5, 127.0, "OFF", "OFF")
    private fun List<WiperEvent>.secs() = map { it.ts.substring(17, 19).toInt() }

    @Test fun keepsOrderAndRetriesAfterFailure() = runTest {
        val q = UploadQueue(MemoryQueueStore())
        (1..3).forEach { q.enqueue(ev(it)) }
        q.flush { false }
        val sent = mutableListOf<Int>()
        q.flush { sent += it.secs(); true }
        assertEquals(listOf(1, 2, 3), sent)
        assertEquals(0, q.size())
    }

    @Test fun dropsOldestBeyondCap() = runTest {
        val q = UploadQueue(MemoryQueueStore(), cap = 3)
        (1..5).forEach { q.enqueue(ev(it)) }
        val sent = mutableListOf<Int>()
        q.flush { sent += it.secs(); true }
        assertEquals(listOf(3, 4, 5), sent)
    }

    @Test fun sendsInBatchesOf200() = runTest {
        val q = UploadQueue(MemoryQueueStore())
        repeat(450) { q.enqueue(ev(0)) }
        val sizes = mutableListOf<Int>()
        q.flush { sizes += it.size; true }
        assertEquals(listOf(200, 200, 50), sizes)
    }

    @Test fun eventsAddedDuringSendAreNotLost() = runTest {
        val q = UploadQueue(MemoryQueueStore(), cap = 2)
        (1..2).forEach { q.enqueue(ev(it)) }
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<List<Int>>()
        val job = launch { q.flush { sent += it.secs(); sending.complete(Unit); release.await(); true } }
        sending.await()
        q.enqueue(ev(3))
        release.complete(Unit)
        job.join()
        assertEquals(listOf(listOf(1, 2), listOf(3)), sent)
        assertEquals(0, q.size())
    }

    @Test fun clearDropsEverything() = runTest {
        val q = UploadQueue(MemoryQueueStore())
        (1..3).forEach { q.enqueue(ev(it)) }
        q.clear()
        var called = false
        q.flush { called = true; true }
        assertEquals(false, called)
    }

    @Test fun fileStoreSurvivesRestart() = runTest {
        val file = tmp.root.resolve("queue.jsonl")
        UploadQueue(FileQueueStore(file)).apply { (1..3).forEach { enqueue(ev(it)) } }
        val sent = mutableListOf<Int>()
        UploadQueue(FileQueueStore(file)).flush { sent += it.secs(); true }
        assertEquals(listOf(1, 2, 3), sent)
        assertEquals(0, UploadQueue(FileQueueStore(file)).size())
    }

    @Test fun fileStoreSkipsCorruptLine() = runTest {
        val file = tmp.root.resolve("queue.jsonl")
        UploadQueue(FileQueueStore(file)).apply { (1..2).forEach { enqueue(ev(it)) } }
        file.appendText("{\"vid\":\"v\",\"ts\":")
        val sent = mutableListOf<Int>()
        UploadQueue(FileQueueStore(file)).flush { sent += it.secs(); true }
        assertEquals(listOf(1, 2), sent)
    }

    @Test fun overlappingFlushesSendEachEventOnce() = runTest {
        val q = UploadQueue(MemoryQueueStore())
        (1..3).forEach { q.enqueue(ev(it)) }
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val send: suspend (List<WiperEvent>) -> Boolean = { sent += it.secs(); release.await(); true }
        val a = launch { q.flush(send) }
        val b = launch { q.flush(send) }
        release.complete(Unit)
        a.join()
        b.join()
        assertEquals(listOf(1, 2, 3), sent)
    }
}
