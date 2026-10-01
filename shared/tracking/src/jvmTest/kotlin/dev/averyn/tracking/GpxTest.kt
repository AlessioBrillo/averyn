package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readString
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpxTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-gpx-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    /** DOM includes whitespace between tags as text nodes; count only element children (e.g. `<trkpt>`s). */
    private fun elementChildren(node: Node): List<Node> =
        (0 until node.childNodes.length)
            .map { node.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }

    @Test
    fun writesWellFormedGpxWithOneSegmentPerRecordingInterval() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendEvent("a1", StateEvent(0, 0, ActivityState.RECORDING, Reason.GPS_READY))
        store.appendSample("a1", LocationSample(1_000, 0, 45.0, 9.0, 5.0, altitudeM = 100.0))
        store.appendSample("a1", LocationSample(2_000, 1_000, 45.001, 9.0, 5.0))
        store.appendEvent("a1", StateEvent(1_000, 2_000, ActivityState.PAUSED, Reason.USER))
        store.appendSample("a1", LocationSample(3_000, 1_500, 45.5, 9.5, 5.0)) // during PAUSED: excluded
        store.appendEvent("a1", StateEvent(2_000, 3_000, ActivityState.RECORDING, Reason.USER))
        store.appendSample("a1", LocationSample(4_000, 2_000, 45.002, 9.0, 5.0))

        val buffer = Buffer()
        writeGpx(store, "a1", buffer)
        val xml = buffer.readString()

        val doc = parse(xml)
        assertEquals("gpx", doc.documentElement.tagName)
        val segments = doc.getElementsByTagName("trkseg")
        assertEquals(2, segments.length) // one before the pause, one after — the paused sample is excluded
        assertEquals(2, elementChildren(segments.item(0)).size)
        assertEquals(1, elementChildren(segments.item(1)).size)

        val firstPoint = elementChildren(segments.item(0)).first() as Element
        assertEquals("45.0", firstPoint.getAttribute("lat"))
        assertTrue(xml.contains("<ele>100.0</ele>"))
        assertTrue(xml.contains("1970-01-01T00:00:01Z")) // timeMs 1_000
    }

    @Test
    fun acceptedOnlyExcludesJumpFlaggedLegs() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendEvent("a1", StateEvent(0, 0, ActivityState.RECORDING, Reason.GPS_READY))
        store.appendSample("a1", LocationSample(0, 0, 0.0, 0.0, 5.0))
        store.appendSample("a1", LocationSample(1_000, 1_000, 10.0, 0.0, 5.0)) // ~1111 km in 1s: a JUMP

        val raw = Buffer().also { writeGpx(store, "a1", it, GpxContent.RAW) }.readString()
        val accepted = Buffer().also { writeGpx(store, "a1", it, GpxContent.ACCEPTED_ONLY) }.readString()

        assertEquals(2, parse(raw).getElementsByTagName("trkpt").length)
        assertEquals(1, parse(accepted).getElementsByTagName("trkpt").length)
    }
}
