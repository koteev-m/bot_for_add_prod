package com.example.bot.music

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer

/** Ensures music migrations apply successfully. */
class MigrationTest {
    @Test
    fun `migrations apply`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            org.testcontainers.DockerClientFactory
                .instance()
                .isDockerAvailable,
        )
        PostgreSQLContainer<Nothing>("postgres:15-alpine").use { pg ->
            pg.start()
            val flyway = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load()
            flyway.migrate()
            pg.createConnection("")!!.use { conn ->
                val rs = conn.createStatement().executeQuery("select to_regclass('music_items')")
                rs.next()
                assertTrue(rs.getString(1) != null)
            }
        }
    }
}
