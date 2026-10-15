package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MVP-0 exit criterion "GPX export validates" (TDD-0006 R5): complete bytes, plain decimals, the GPX 1.1 XSD. */
class GpxExportTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-gpx-export-test")
        store = ActivityStore(Path(dir.toString()))
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendEvent("a1", StateEvent(0, 0, ActivityState.RECORDING, Reason.GPS_READY))
        // Near the equator and at sea level: Double.toString() writes these as 1.0E-4 / 5.0E-4.
        store.appendSample("a1", LocationSample(1_000, 1_000, 0.0001, -0.0002, 5.0, altitudeM = 0.0005))
        store.appendSample("a1", LocationSample(2_000, 2_000, 0.00011, -0.00021, 5.0, altitudeM = 1.5))
        store.appendSample("a1", LocationSample(3_000, 3_000, 45.0, 9.0, 5.0, altitudeM = 100.0))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun gpxSchemaFile(): File =
        File(
            System.getProperty("averyn.fixturesDir") ?: error("averyn.fixturesDir is not set"),
            "schema/gpx-1.1.xsd",
        )

    private fun assertValidGpx(bytes: ByteArray) {
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(gpxSchemaFile())
        schema.newValidator().validate(StreamSource(bytes.inputStream())) // throws SAXException when invalid
    }

    @Test
    fun aBufferedSinkOverAStreamTheCallerNeverClosedStillGetsEveryByte() {
        // What the Android export does: the ContentResolver stream is closed by `use {}`, the Sink is not.
        val stream = ByteArrayOutputStream()

        writeGpx(store, "a1", stream.asSink().buffered())

        val text = stream.toString(Charsets.UTF_8)
        assertTrue(text.endsWith("</gpx>\n"), "the footer must have reached the stream, got: ...${text.takeLast(40)}")
    }

    @Test
    fun theExportValidatesAgainstTheGpx11Schema() {
        val stream = ByteArrayOutputStream()
        writeGpx(store, "a1", stream.asSink().buffered())

        assertValidGpx(stream.toByteArray())
    }

    @Test
    fun theFileExportValidatesToo() {
        val file = File(dir.toFile(), "out.gpx")

        store.exportGpxToFile("a1", file.absolutePath)

        assertValidGpx(file.readBytes())
    }

    @Test
    fun smallCoordinatesAreWrittenAsPlainDecimals() {
        val stream = ByteArrayOutputStream()
        writeGpx(store, "a1", stream.asSink().buffered())

        val text = stream.toString(Charsets.UTF_8)
        assertTrue("E-" !in text && "E+" !in text, "no scientific notation in the file")
        assertTrue("lat=\"0.0001\" lon=\"-0.0002\"" in text)
        assertTrue("<ele>0.0005</ele>" in text)
    }

    @Test
    fun plainDecimalCoversBothDirectionsOfScientificNotation() {
        assertEquals("0.0001", plainDecimal(1.0E-4))
        assertEquals("-0.00001234", plainDecimal(-1.234E-5))
        assertEquals("10000000", plainDecimal(1.0E7))
        assertEquals("45.123", plainDecimal(45.123))
        assertEquals("0.0", plainDecimal(0.0))
    }

    @Test
    fun aNonFiniteAltitudeIsLeftOutInsteadOfBreakingTheFile() {
        store.appendSample("a1", LocationSample(4_000, 4_000, 45.1, 9.1, 5.0, altitudeM = Double.NaN))
        val stream = ByteArrayOutputStream()

        writeGpx(store, "a1", stream.asSink().buffered())

        assertValidGpx(stream.toByteArray())
    }
}
