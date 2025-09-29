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
        // Выполняем миграции в IO-контексте
        runBlocking {
            runInterruptible(Dispatchers.IO) {
                val runner = MigrationRunner(ds, flywayCfg)
                runner.run()
            }
        }
        MigrationState.migrationsApplied = true
        log.info("Migrations completed successfully")
    } catch (e: Exception) {
        val flywayEx =
            e::class.qualifiedName == "org.flywaydb.core.api.FlywayException" ||
                e.cause?.let { it::class.qualifiedName == "org.flywaydb.core.api.FlywayException" } == true
        if (flywayEx) {
            log.error("Migrations failed (FlywayException), stopping application", e)
        } else {
            log.error("Migrations failed, stopping application", e)
        }
        throw e
    }

    // Подключаем Exposed к уже инициализированному DataSource
    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    // Корректное закрытие пула при остановке приложения (Ktor 3: monitor → events)
    monitor.subscribe(ApplicationStopped) {
        try {
            (ds as? AutoCloseable)?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            DataSourceHolder.dataSource = null
        }
    }
}
