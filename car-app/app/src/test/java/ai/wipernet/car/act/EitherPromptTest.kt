package ai.wipernet.car.act

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EitherPromptTest {
    private class Fake(val afterMs: Long?, val answer: Boolean?) : ConsentPrompt {
        var cancelled = false
        override suspend fun askYesNo(question: String): Boolean? {
            try {
                if (afterMs == null) awaitCancellation()
                delay(afterMs)
                return answer
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        override suspend fun listen(seconds: Int): String? = "들음"
    }

    @Test fun eitherPromptTakesFirstAnswer() = runTest {
        val voice = Fake(null, null)
        val button = Fake(3_000, false)
        assertEquals(false, EitherPrompt(voice, button).askYesNo("q"))
        assertEquals(3_000, currentTime)
        assertTrue(voice.cancelled)
    }

    @Test fun nullFromOneWaitsForTheOther() = runTest {
        assertEquals(true, EitherPrompt(Fake(1_000, null), Fake(5_000, true)).askYesNo("q"))
    }

    @Test fun eitherPromptTimesOutAfterTenSeconds() = runTest {
        assertNull(EitherPrompt(Fake(null, null), Fake(null, null)).askYesNo("q"))
        assertEquals(10_000, currentTime)
    }

    @Test fun listenUsesFirstPrompt() = runTest {
        assertEquals("들음", EitherPrompt(Fake(null, null), Fake(null, null)).listen(8))
    }
}
