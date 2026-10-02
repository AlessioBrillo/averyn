package dev.averyn.backend.storage

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI
import java.nio.file.Path

/**
 * Immutable raw activity files in any S3-compatible store (ADR-0006, ADR-0016); only the S3 API is used.
 * Path-style addressing and "checksums only when required" keep it working with self-hosted stores.
 */
class RawObjectStore(
    endpoint: String,
    private val bucket: String,
    accessKey: String,
    secretKey: String,
) {
    private val log = LoggerFactory.getLogger(RawObjectStore::class.java)

    private val s3: S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1) // required by the SDK, ignored by self-hosted stores
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .httpClient(UrlConnectionHttpClient.create())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build()

    /**
     * Creates the bucket if it is missing. A self-hosted store takes a few seconds to come up (and may answer with
     * errors until its configuration has loaded), so any failure is retried every 2 s, [attempts] times, before
     * giving up. Blocks the caller meanwhile: meant for start-up.
     */
    fun ensureBucket(attempts: Int = 30) {
        repeat(attempts - 1) { attempt ->
            try {
                return create()
            } catch (e: SdkException) {
                log.warn("object store not ready (attempt {}/{}): {}", attempt + 1, attempts, e.javaClass.simpleName)
                Thread.sleep(2_000)
            }
        }
        create()
    }

    private fun create() {
        try {
            s3.headBucket { it.bucket(bucket) }
        } catch (e: NoSuchBucketException) {
            s3.createBucket { it.bucket(bucket) }
        }
    }

    /** The whole object. For recomputing derived data from the raw file (ADR-0016) and for tests. */
    fun get(key: String): ByteArray = s3.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()

    fun put(
        key: String,
        file: Path,
    ) {
        s3.putObject({ it.bucket(bucket).key(key).contentType("application/x-ndjson") }, RequestBody.fromFile(file))
    }
}
