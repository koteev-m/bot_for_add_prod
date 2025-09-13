package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.HikariFactory
import com.example.bot.data.db.MigrationRunner
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object MigrationState {
    @Volatile
    var migrationsApplied: Boolean = false
}

fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")
    val dbCfg = DbConfig.fromEnv()
    val flywayCfg = FlywayConfig.fromEnv()

    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    try {
        runBlocking {
            runInterruptible(Dispatchers.IO) {
                val runner = MigrationRunner(ds, flywayCfg)
                runner.run()
            }
        }
        MigrationState.migrationsApplied = true
        log.info("Migrations completed successfully")
    } catch (e: FlywayException) {
        log.error("Migrations failed (FlywayException), stopping application", e)
        throw e
    } catch (e: RuntimeException) {
        // включая JDBC/DS misconfig и иные runtime-ошибки старта
        log.error("Migrations failed (RuntimeException), stopping application", e)
        throw e
    }

    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    environment.monitor.subscribe(ApplicationStopped) {
        try {
            (ds as? AutoCloseable)?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            DataSourceHolder.dataSource = null
        }
    }
}
