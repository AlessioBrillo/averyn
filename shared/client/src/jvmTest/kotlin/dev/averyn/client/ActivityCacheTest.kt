package dev.averyn.client

import dev.averyn.api.ActivitySummary
import dev.averyn.api.UnitSystem
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cache of the server on the device (TDD-0011): it holds copies, it never holds a token. */
class ActivityCacheTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var cache: ActivityCache

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-cache-test")
        cache = ActivityCache(Path(dir.toString()), "https://server.example")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun summary(
        id: String,
        startedAt: String = "2026-01-31T09:15:00Z",
        clientId: String = id,
    ) = ActivitySummary(
        id = id,
        clientActivityId = clientId,
        sport = "RUN",
        startedAt = startedAt,
        finalState = "COMPLETED",
        distanceM = 1000.0,
        elapsedMs = 600_000,
        movingMs = 500_000,
        pausedMs = 60_000,
        stationaryMs = 40_000,
        averageSpeedMps = 2.0,
        paceSecPerKm = 500.0,
        qualityGrade = "GOOD",
        droppedRecords = 0,
        metricsAlgorithmVersion = "distance-v1,time-v1,speed-v1",
        qualityAlgorithmVersion = "quality-v2",
        visibility = "PRIVATE",
        avgHrBpm = 140,
        maxHrBpm = 160,
        hrSource = "ble-hr",
        hrAlgorithmVersion = "hr-v2",
    )

    @Test
    fun `the summaries of the list are written and read back`() {
        assertNull(cache.readSummaries())
        cache.writeSummaries(listOf(summary("a1"), summary("a2")), "next", savedAtMs = 1_000)
        val cached = cache.readSummaries()
        assertNotNull(cached)
        assertEquals(2, cached.items.size)
        assertEquals("next", cached.nextCursor)
        assertEquals(1_000L, cached.savedAtMs)
    }

    @Test
    fun `the summaries of the list are kept newest first and bounded`() {
        val items = (1..20).map { summary("a$it", startedAt = "2026-01-01T00:00:00Z") }
        val bounded = ActivityCache(Path(dir.toString()), "https://server.example", maxSummaries = 5)
        bounded.writeSummaries(items, null, savedAtMs = 1)
        assertEquals(5, bounded.readSummaries()?.items?.size)
    }

    @Test
    fun `a detail is written and read back`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 42, summary = summary("a1")))
        val detail = cache.readDetail("a1")
        assertNotNull(detail)
        assertEquals(42L, detail.savedAtMs)
        assertEquals("a1", detail.summary.id)
    }

    @Test
    fun `a detail of another server is not read`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 42, summary = summary("a1")))
        val other = ActivityCache(Path(dir.toString()), "https://another.example")
        assertNull(other.readDetail("a1"))
        // The cache of the other server took the place of the first one: the copy of a server is never shown
        // to the user of another one.
        assertNull(cache.readDetail("a1"))
    }

    @Test
    fun `an id that is not an id never becomes a file`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("../../evil")))
        assertNull(cache.readDetail("../../evil"))
        assertTrue(!SystemFileSystem.exists(Path(Path(dir.toString(), "details"), "..%2F..%2Fevil.json")))
    }

    @Test
    fun `the oldest details make room for the new ones`() {
        val small = ActivityCache(Path(dir.toString()), "https://server.example", maxDetails = 2)
        small.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        small.writeDetail(CachedActivityDetail(savedAtMs = 2, summary = summary("a2")))
        small.writeDetail(CachedActivityDetail(savedAtMs = 3, summary = summary("a3")))
        assertNull(small.readDetail("a1"))
        assertNotNull(small.readDetail("a3"))
    }

    @Test
    fun `a detail that was opened stays`() {
        val small = ActivityCache(Path(dir.toString()), "https://server.example", maxDetails = 2, nowMs = { 100 })
        small.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        small.writeDetail(CachedActivityDetail(savedAtMs = 2, summary = summary("a2")))
        // a1 is opened again: it is the one that has to stay.
        small.readDetail("a1")
        small.writeDetail(CachedActivityDetail(savedAtMs = 3, summary = summary("a3")))
        assertNotNull(small.readDetail("a1"))
        assertNull(small.readDetail("a2"))
    }

    @Test
    fun `a deleted activity leaves the cache`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        cache.writeSummaries(listOf(summary("a1")), null, savedAtMs = 1)
        cache.deleteDetail("a1")
        cache.removeSummary("a1")
        assertNull(cache.readDetail("a1"))
        assertTrue(cache.readSummaries()?.items?.isEmpty() == true)
    }

    @Test
    fun `a broken file is ignored instead of crashing`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        val path = Path(Path(dir.toString(), "details"), "a1.json")
        SystemFileSystem.sink(path, append = false).buffered().use { it.writeString("{ this is not json") }
        assertNull(cache.readDetail("a1"))
    }

    @Test
    fun `the cache can be thrown away`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        cache.clear()
        assertNull(cache.readDetail("a1"))
        assertEquals(0, cache.stats().detailCount)
    }

    @Test
    fun `the index is rebuilt when it is gone`() {
        cache.writeDetail(CachedActivityDetail(savedAtMs = 1, summary = summary("a1")))
        val index = Path(Path(dir.toString(), "details"), "index.json")
        if (SystemFileSystem.exists(index)) SystemFileSystem.delete(index)
        assertEquals(1, cache.stats().detailCount)
    }

    @Test
    fun `the unit system is remembered and is the only thing of the profile the cache keeps`() {
        assertNull(cache.readUnits())

        cache.writeUnits(UnitSystem.IMPERIAL)

        assertEquals(UnitSystem.IMPERIAL, cache.readUnits())
        val files =
            dir
                .toFile()
                .walkTopDown()
                .filter { it.isFile }
                .map { it.readText() }
                .toList()
        assertTrue(files.none { it.contains("maxHr") }, "the maximum heart rate must not be on the device")
    }

    @Test
    fun `the unit system of another server is not read`() {
        cache.writeUnits(UnitSystem.IMPERIAL)

        val other = ActivityCache(Path(dir.toString()), "https://other.example")

        assertNull(other.readUnits())
    }

    @Test
    fun `clearing the cache forgets the unit system`() {
        cache.writeUnits(UnitSystem.IMPERIAL)

        cache.clear()

        assertNull(cache.readUnits())
    }
}
