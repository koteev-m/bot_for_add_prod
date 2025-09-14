package com.example.bot.tools

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.HikariFactory
import com.example.bot.data.db.MigrationRunner

fun main() {
    val db = DbConfig.fromEnv()
    val fw = FlywayConfig.fromEnv()
    val ds = HikariFactory.dataSource(db)
    try {
        val res = MigrationRunner(ds, fw).run()
        println("Flyway OK: $res")
    } finally {
        try {
            (ds as? AutoCloseable)?.close()
        } catch (_: Throwable) {
        }
    }
}
