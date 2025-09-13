package com.example.bot.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object HikariFactory {
    fun dataSource(db: DbConfig): DataSource {
        val hc =
            HikariConfig().apply {
                jdbcUrl = db.url
                username = db.user
                password = db.password

                maximumPoolSize = DEFAULT_MAX_POOL_SIZE
                minimumIdle = DEFAULT_MIN_IDLE
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }
        return HikariDataSource(hc)
    }
}

private const val DEFAULT_MAX_POOL_SIZE = 20
private const val DEFAULT_MIN_IDLE = 2
