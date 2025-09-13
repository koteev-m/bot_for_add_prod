package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.HikariFactory
import com.example.bot.data.db.MigrationRunner
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object MigrationState {
    @Volatile
    var migrationsApplied: Boolean = false
}

@Suppress("TooGenericExceptionCaught")
fun Application.installMigrationsAndDatabase(): DataSource {
    val log = LoggerFactory.getLogger("Migrations")
    val dbCfg = DbConfig.fromEnv()
    val flywayCfg = FlywayConfig.fromEnv()

    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    try {
        val runner = MigrationRunner(ds, flywayCfg)
        runner.run()
        MigrationState.migrationsApplied = true
        log.info("Migrations completed successfully")
    } catch (e: Exception) {
        log.error("Migrations failed, stopping application", e)
        throw e
    }

    Database.connect(ds)

    environment.monitor.subscribe(ApplicationStopped) {
        (ds as? AutoCloseable)?.let {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
    }

    return ds
}
