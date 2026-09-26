package dev.averyn.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.UUID

/**
 * ADR-0009: request ids in logs, Prometheus metrics, liveness and readiness probes.
 * `/health` = process is up. `/ready` = dependencies ([isReady]) are reachable.
 * `/metrics` must only be exposed on the internal network (see infrastructure/compose).
 */
fun Application.observability(isReady: () -> Boolean) {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-Id")
    }
    install(CallLogging) { callIdMdc("request_id") }
    install(MicrometerMetrics) { this.registry = registry }

    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        get("/ready") {
            if (isReady()) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
            }
        }
        get("/metrics") { call.respondText(registry.scrape()) }
    }
}
