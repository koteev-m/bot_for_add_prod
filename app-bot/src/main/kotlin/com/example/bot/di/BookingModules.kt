package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module

val bookingModule = module {
    single {
        val dataSource = DataSourceHolder.dataSource ?: error("DataSource is not initialized")
        Database.connect(dataSource)
    }
    single { BookingRepository(get()) }
    single { BookingHoldRepository(get()) }
    single { OutboxRepository(get()) }
    single { AuditLogRepository(get()) }
    single<SendPort> { DummySendPort }
    single { BookingService(get(), get(), get(), get()) }
    single { OutboxWorker(get(), get()) }
}

private object DummySendPort : SendPort {
    override suspend fun send(topic: String, payload: JsonObject): SendOutcome = SendOutcome.Ok
}
