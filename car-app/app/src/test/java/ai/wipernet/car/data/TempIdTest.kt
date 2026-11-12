package ai.wipernet.car.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TempIdTest {
    @Test fun keepsIdFor12MinutesAndRotatesBy18() {
        repeat(50) { seed ->
            var now = 0L
            val id = TempId({ now }, Random(seed))
            val first = id.current()
            now = 719_000
            assertEquals(first, id.current())
            now = 1_080_000
            assertNotEquals(first, id.current())
        }
    }

    @Test fun rotationTimeIsRandom() {
        val rotatedAt = (0 until 20).map { seed ->
            var now = 0L
            val id = TempId({ now }, Random(seed))
            val first = id.current()
            while (id.current() == first) now += 1_000
            now
        }
        assertTrue(rotatedAt.all { it in 720_000..1_080_000 })
        assertTrue(rotatedAt.toSet().size > 10)
    }
}
