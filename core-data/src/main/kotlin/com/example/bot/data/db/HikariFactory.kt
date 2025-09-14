package com.example.bot.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object HikariFactory {

    private fun envInt(name: String, default: Int): Int =
        System.getenv(name)?.toIntOrNull() ?: default

    private fun envLong(name: String, default: Long): Long =
        System.getenv(name)?.toLongOrNull() ?: default

    fun dataSource(db: DbConfig): DataSource {
        val hc = HikariConfig().apply {
            jdbcUrl = db.url
            username = db.user
            password = db.password

            maximumPoolSize = envInt("HIKARI_MAX_POOL_SIZE", 20)
            minimumIdle = envInt("HIKARI_MIN_IDLE", 2)
            connectionTimeout = envLong("HIKARI_CONN_TIMEOUT_MS", 5_000)
            validationTimeout = envLong("HIKARI_VALIDATION_TIMEOUT_MS", 2_000)
            leakDetectionThreshold = envLong("HIKARI_LEAK_DETECTION_MS", 10_000)

            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(hc)
    }
}

