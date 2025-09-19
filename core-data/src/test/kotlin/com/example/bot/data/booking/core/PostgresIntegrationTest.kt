package com.example.bot.data.booking.core

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresIntegrationTest {
    protected lateinit var database: Database

    @BeforeAll
    fun startContainer() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations(
                "classpath:db/migration/common",
                "classpath:db/migration/postgresql",
            )
            .baselineOnMigrate(true)
            .load()
            .migrate()
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )
    }

    @BeforeEach
    fun cleanDatabase() {
        check(::database.isInitialized) { "database not initialised" }
        transaction(database) {
            exec(
                "TRUNCATE TABLE booking_outbox, audit_log, booking_holds, bookings, events, tables, clubs RESTART IDENTITY CASCADE",
            )
        }
    }

    @AfterAll
    fun stopContainer() {
        postgres.stop()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
