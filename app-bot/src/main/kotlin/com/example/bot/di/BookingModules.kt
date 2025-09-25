package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.promo.BookingTemplateRepositoryImpl
import com.example.bot.data.promo.PromoAttributionRepositoryImpl
import com.example.bot.data.promo.PromoLinkRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttributionCoordinator
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoAttributionStore
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module

val bookingModule =
    module {
        single {
            val dataSource = DataSourceHolder.dataSource ?: error("DataSource is not initialized")
            Database.connect(dataSource)
        }
        single { BookingRepository(get()) }
        single { BookingHoldRepository(get()) }
        single { OutboxRepository(get()) }
        single { NotificationsOutboxRepository(get()) }
        single { AuditLogRepository(get()) }
        single<GuestListRepository> { GuestListRepositoryImpl(get()) }
        single { PromoLinkRepositoryImpl(get()) }
        single { PromoAttributionRepositoryImpl(get()) }
        single<BookingTemplateRepository> { BookingTemplateRepositoryImpl(get()) }
        single { ExposedUserRepository(get()) }
        single { ExposedUserRoleRepository(get()) }
        single<PromoAttributionStore> { InMemoryPromoAttributionStore() }
        single { PromoAttributionService(get(), get(), get(), get(), get()) }
        single<PromoAttributionCoordinator> { get<PromoAttributionService>() }
        single { BookingTemplateService(get(), get(), get(), get(), get()) }
        single<SendPort> { DummySendPort }
        single { BookingService(get(), get(), get(), get(), get()) }
        single { OutboxWorker(get(), get()) }
    }

private object DummySendPort : SendPort {
    override suspend fun send(
        topic: String,
        payload: JsonObject,
    ): SendOutcome = SendOutcome.Ok
}
