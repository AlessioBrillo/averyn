package dev.averyn.campaign

import dev.averyn.backend.imports.FitParser
import dev.averyn.backend.imports.ImportParseResult
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.system.exitProcess

private const val USAGE = """usage: campaign evaluate <diagnostics.json> --row S15 [options]
  --row <ID>              the scenario of docs/testing/device-matrix.md (S1, S2, S5, S6, S15, S29 are checked by number)
  --ref <strap.fit>       the strap's own FIT file (S15, S29)
  --gpx <activity.gpx>    the exported GPX, validated against the GPX 1.1 schema (S1)
  --xsd <gpx-1.1.xsd>     default: tests/gps-fixtures/schema/gpx-1.1.xsd
  --kill-at <ISO instant> the instant of the kill, e.g. 2026-11-09T10:15:30Z (S5)
  --ref-distance-m <m>    the reference distance of the route (S1)
  --date <YYYY-MM-DD>     default: today
Prints the row for the Results table, then each criterion. Exit 0 = PASS, 1 = FAIL, 2 = MANUAL (a criterion needs a person
or an input that was not given), 64 = bad usage."""

fun main(args: Array<String>) {
    if (args.size < 2 || args[0] != "evaluate") usage("")
    val options = args.drop(2).chunked(2).associate { (k, v) -> k to v }
    if (args.drop(2).size % 2 != 0 ||
        options.keys.any { !it.startsWith("--") }
    ) {
        usage("options come in pairs: --name value")
    }
    val row = options["--row"] ?: usage("--row is required")
    val (report, device) = parseExport(File(args[1]).readText())

    val reference =
        options["--ref"]?.let { path ->
            val parsed = File(path).inputStream().use { FitParser().parse(it) }
            when (parsed) {
                is ImportParseResult.Parsed ->
                    hrReference(
                        parsed.track.heartRates,
                        report.startedAtMs,
                        report.endedAtMs ?: Long.MAX_VALUE,
                    )
                is ImportParseResult.Invalid -> usage("--ref is not a readable FIT file (${parsed.code})")
            }
        }
    val gpxValid =
        options["--gpx"]?.let { gpx ->
            val xsd = File(options["--xsd"] ?: "tests/gps-fixtures/schema/gpx-1.1.xsd")
            if (!xsd.isFile) usage("the GPX schema is not at ${xsd.path}: run from the repository root or give --xsd")
            validGpx(File(gpx), xsd)
        }
    val input =
        Inputs(
            report = report,
            device = device,
            hrReference = reference,
            gpxValid = gpxValid,
            killAtMs = options["--kill-at"]?.let { Instant.parse(it).toEpochMilli() },
            referenceDistanceM = options["--ref-distance-m"]?.toDouble(),
        )
    val result = evaluate(row, input)
    println(render(result, input, options["--date"] ?: LocalDate.now().toString()))
    exitProcess(
        when (result.verdict) {
            Verdict.PASS -> 0
            Verdict.FAIL -> 1
            Verdict.MANUAL -> 2
        },
    )
}

private fun usage(why: String): Nothing {
    if (why.isNotEmpty()) System.err.println("campaign: $why")
    System.err.println(USAGE)
    exitProcess(64)
}

/** True when [gpx] is valid against [xsd]. A file that does not parse is not valid, not an error of the tool. */
internal fun validGpx(
    gpx: File,
    xsd: File,
): Boolean =
    try {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        // The schema is local and complete; nothing is fetched from the network while checking an export.
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
        factory.newSchema(xsd).newValidator().validate(StreamSource(gpx))
        true
    } catch (_: org.xml.sax.SAXException) {
        false
    }
