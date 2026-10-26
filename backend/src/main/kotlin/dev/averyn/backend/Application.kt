package dev.averyn.backend

import dev.averyn.backend.activities.AnalysisService
import dev.averyn.backend.activities.DEFAULT_REQUESTS_PER_MINUTE
import dev.averyn.backend.activities.DEFAULT_USER_QUOTA_BYTES
import dev.averyn.backend.activities.UserData
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.OidcConfig
import dev.averyn.backend.auth.auth
import dev.averyn.backend.consent.ConsentScope
import dev.averyn.backend.consent.consent
import dev.averyn.backend.imports.imports
import dev.averyn.backend.profile.profile
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/** Entry point, referenced from application.yaml. Domain packages (auth, activities, …) are added as they are built. */
fun Application.module() {
    val cfg = environment.config
    val db =
        connectAndMigrate(
            url = cfg.property("averyn.database.url").getString(),
            user = cfg.property("averyn.database.user").getString(),
            password = cfg.property("averyn.database.password").getString(),
        )
    val oidc =
        OidcConfig(
            issuer = cfg.property("averyn.oidc.issuer").getString(),
            audience = cfg.property("averyn.oidc.audience").getString(),
            clientId = cfg.property("averyn.oidc.clientId").getString(),
            webClientId = cfg.property("averyn.oidc.webClientId").getString(),
            internalUrl = cfg.propertyOrNull("averyn.oidc.internalUrl")?.getString()?.ifBlank { null },
        )
    val mapStyleUrl =
        cfg.propertyOrNull("averyn.web.mapStyleUrl")?.getString()?.ifBlank { null } ?: DEFAULT_MAP_STYLE_URL
    val raw =
        RawObjectStore(
            endpoint = cfg.property("averyn.s3.endpoint").getString(),
            bucket = cfg.property("averyn.s3.bucket").getString(),
            accessKey = cfg.property("averyn.s3.accessKey").getString(),
            secretKey = cfg.property("averyn.s3.secretKey").getString(),
        ).also { it.ensureBucket() }
    content()
    observability(isReady = db::isReachable)
    auth(oidc)
    // The oldest app version this instance still supports (TDD-0011): optional, an unset value means no minimum.
    val minClientVersion = cfg.propertyOrNull("averyn.client.minClientVersion")?.getString()?.ifBlank { null }
    val privacyPolicyUrl = cfg.propertyOrNull("averyn.legal.privacyPolicyUrl")?.getString()
    val termsUrl = cfg.propertyOrNull("averyn.legal.termsUrl")?.getString()
    clientConfig(oidc, mapStyleUrl, minClientVersion, privacyPolicyUrl, termsUrl)
    val quotaBytes =
        cfg.propertyOrNull("averyn.limits.quotaBytes")?.getString()?.toLong() ?: DEFAULT_USER_QUOTA_BYTES
    // One analysis service for the whole process: it holds the slots that limit how many activities are
    // analysed at the same time.
    val analysisService = AnalysisService(db, raw)
    val userData = UserData(db, raw, analysisService = analysisService)
    val consentStore =
        consent(db) { ownerId, scope ->
            // Revoking the recording consent really deletes the data it covers (ADR-0021).
            if (scope == ConsentScope.RR_RECORDING) userData.deleteRrData(ownerId)
        }
    activities(
        db,
        raw,
        quotaBytes = quotaBytes,
        requestsPerMinute =
            cfg.propertyOrNull("averyn.limits.requestsPerMinute")?.getString()?.toInt()
                ?: DEFAULT_REQUESTS_PER_MINUTE,
        consentStore = consentStore,
        userData = userData,
        analysisService = analysisService,
    )
    imports(db, raw, quotaBytes = quotaBytes)
    profile(db)
}

/**
 * Demo-grade world style, fine to try the web app out. Whoever serves the tiles sees the area a user views, so a
 * real instance sets AVERYN_MAP_STYLE_URL (docs/deployment/self-hosting.md).
 */
const val DEFAULT_MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"

fun Application.content() {
    install(ContentNegotiation) { json() }
}
