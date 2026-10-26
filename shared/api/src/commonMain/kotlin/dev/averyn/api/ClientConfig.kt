package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * The public configuration of a server: `GET /v1/client-config` (TDD-0011).
 *
 * It is public (no token needed): an app is given only the server URL and learns from here which identity
 * provider to use, which basemap the server serves and how the two versions fit together.
 */
@Serializable
data class ClientConfig(
    val issuer: String,
    val clientId: String,
    val webClientId: String,
    val mapStyleUrl: String,
    /**
     * The version of the API contract the server speaks. A client that does not understand it is warned, never
     * blocked: the read path of TDD-0011 is additive, so an old client still works against a newer server.
     */
    val apiVersion: Int = Api.API_VERSION,
    /**
     * The oldest client version the server still supports (semantic version), or null when the server does not
     * impose a minimum. It is a policy of the operator of the instance, not something the server can infer.
     */
    val minClientVersion: String? = null,
    /**
     * Where the operator of the instance publishes its privacy notice, or null when it does not. The text is the
     * operator's (the controller of the data of that instance): Averyn only links to it, from the web and the apps.
     */
    val privacyPolicyUrl: String? = null,
    /** Where the operator publishes the terms of the service, or null. */
    val termsUrl: String? = null,
)
