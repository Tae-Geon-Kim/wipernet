package ai.wipernet.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class MapTilesTest {
    private val lat = 37.4760
    private val lng = 127.0588

    @Test
    fun `중심 점을 품은 타일이 있고 그 경계가 점을 포함한다`() {
        val tiles = tilesAround(lat, lng, 3_000.0, 14)
        val t = tiles.single { lat <= it.north && lat >= it.south && lng >= it.west && lng <= it.east }
        assertEquals(14, t.z)
        assertEquals("https://tile.openstreetmap.org/14/${t.x}/${t.y}.png", t.url)
    }

    @Test
    fun `반경 3 km는 빈틈없는 사각형 몇 장으로 덮인다`() {
        val tiles = tilesAround(lat, lng, 3_000.0, 14)
        assertEquals(tiles.map { it.x }.distinct().size * tiles.map { it.y }.distinct().size, tiles.distinct().size)
        assertTrue(tiles.all { it.z == 14 })
        assertTrue(tiles.size <= 30)

        val dLat = 3_000.0 / 110_540.0
        val dLng = 3_000.0 / (111_320.0 * cos(Math.toRadians(lat)))
        assertTrue(tiles.maxOf { it.north } >= lat + dLat)
        assertTrue(tiles.minOf { it.south } <= lat - dLat)
        assertTrue(tiles.minOf { it.west } <= lng - dLng)
        assertTrue(tiles.maxOf { it.east } >= lng + dLng)
    }
}
