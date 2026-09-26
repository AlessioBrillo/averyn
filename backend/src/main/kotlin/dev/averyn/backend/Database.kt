package dev.averyn.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Connects, runs pending Flyway migrations, and returns the pool. Fails fast on any error. */
fun connectAndMigrate(
    url: String,
    user: String,
    password: String,
): DataSource {
    val ds =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 10
            },
        )
    Flyway
        .configure()
        .dataSource(ds)
        .load()
        .migrate()
    return ds
}

fun DataSource.isReachable(): Boolean = runCatching { connection.use { it.isValid(2) } }.getOrDefault(false)
