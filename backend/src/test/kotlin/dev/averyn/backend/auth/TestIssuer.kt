package dev.averyn.backend.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A stand-in identity provider for tests: its own RSA key, a matching [jwks], and a token minter. */
class TestIssuer(
    val config: OidcConfig = OidcConfig(issuer = "https://idp.test", audience = "project-1", clientId = "app-1"),
) {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unsigned(bigInt: java.math.BigInteger) =
        b64(
            bigInt
                .toByteArray()
                .dropWhile {
                    it == 0.toByte()
                }.toByteArray(),
        )

    val jwks: JwkProvider =
        JwkProvider { keyId ->
            val key = keys.public as RSAPublicKey
            Jwk(
                keyId,
                "RSA",
                "RS256",
                "sig",
                emptyList<String>(),
                null,
                null,
                null,
                mapOf("n" to unsigned(key.modulus), "e" to unsigned(key.publicExponent)),
            )
        }

    fun token(
        subject: String? = "user-1",
        issuer: String = config.issuer,
        audience: String = config.audience,
        expiresIn: Duration = Duration.ofHours(1),
        signedWith: java.security.KeyPair = keys,
    ): String =
        JWT
            .create()
            .withKeyId("test-key")
            .withIssuer(issuer)
            .withAudience(audience)
            .withExpiresAt(Instant.now().plus(expiresIn))
            .apply { if (subject != null) withSubject(subject) }
            .sign(Algorithm.RSA256(null, signedWith.private as RSAPrivateKey))

    /** A token signed by a key this issuer does not publish. */
    fun forgedToken(): String =
        token(
            signedWith =
                KeyPairGenerator
                    .getInstance("RSA")
                    .apply {
                        initialize(2048)
                    }.generateKeyPair(),
        )
}
