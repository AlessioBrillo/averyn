package dev.averyn.backend

import dev.averyn.backend.storage.RawObjectStore
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import javax.sql.DataSource

/**
 * The stack the backend tests run against: one PostgreSQL/PostGIS and one SeaweedFS for the whole suite.
 *
 * Every test class used to start a pair of containers of its own: with five classes that is ten containers,
 * and the machine runs out of room for them (a container that does not start is a red build that has nothing
 * to do with the code). One stack, started once, is enough: the tests clean up after themselves, truncating
 * the tables and writing under the prefix of their own user.
 */
object TestStack {
    private const val S3_CONFIG =
        """{"identities":[{"name":"test","credentials":[{"accessKey":"test","secretKey":"test"}],"actions":["Admin","Read","Write","List","Tagging"]}]}"""

    val postgres: PostgreSQLContainer =
        PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine").asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("averyn")
            .also { it.start() }

    val seaweed: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
            .withCommand("server", "-dir=/data", "-s3", "-s3.config=/etc/seaweedfs/s3.json")
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPort())
            .also { it.start() }

    val endpoint = "http://${seaweed.host}:${seaweed.getMappedPort(8333)}"

    val bucket = "averyn-test"

    val db: DataSource = connectAndMigrate(postgres.jdbcUrl, postgres.username, postgres.password)

    val raw = RawObjectStore(endpoint, bucket, "test", "test")

    init {
        raw.ensureBucket() // retries while the object store comes up
        warmUp()
    }

    /**
     * A round trip through the object store, while the stack is fresh.
     *
     * The first test of the suite must not pay the cold start of the store: a request that waits for a store
     * that is not ready yet takes a minute, and a test that times out is a red build that has nothing to do
     * with the code.
     */
    private fun warmUp() {
        val probe = Files.createTempFile("averyn-test-stack", ".txt")
        try {
            Files.writeString(probe, "probe")
            raw.put("test-stack/probe", probe)
            check(raw.get("test-stack/probe").isNotEmpty()) { "the object store did not answer" }
            raw.delete("test-stack/probe")
        } finally {
            Files.deleteIfExists(probe)
        }
    }
}
