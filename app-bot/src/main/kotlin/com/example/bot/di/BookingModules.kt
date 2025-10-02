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
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttributionCoordinator
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoAttributionStore
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module

val bookingModule = module {
    // DB
    single {
        val ds = DataSourceHolder.dataSource ?: error("DataSource is not initialized")
        Database.connect(ds)
    }

    // Core repos
    single { BookingRepository(get()) }
    single { BookingHoldRepository(get()) }
    single { OutboxRepository(get()) }
    single { NotificationsOutboxRepository(get()) }
    single { AuditLogRepository(get()) }

    // Guests
    single<GuestListRepository> { GuestListRepositoryImpl(get()) }

    // Promo repos (interfaces -> impl)
    single<PromoLinkRepository> { PromoLinkRepositoryImpl(get()) }
    single<PromoAttributionRepository> { PromoAttributionRepositoryImpl(get()) }
    single<BookingTemplateRepository> { BookingTemplateRepositoryImpl(get()) }

    // Security repos (interfaces -> impl)
    single<UserRepository> { ExposedUserRepository(get()) }
    single<UserRoleRepository> { ExposedUserRoleRepository(get()) }

    // Promo store/service
    single<PromoAttributionStore> { InMemoryPromoAttributionStore() }

    // ВНИМАНИЕ: порядок параметров соответствует реальной сигнатуре
    single {
        PromoAttributionService(
            get<PromoLinkRepository>(),
            get<PromoAttributionRepository>(),
            get<PromoAttributionStore>(),
            get<UserRepository>(),
            get<UserRoleRepository>(),
        )
    }
    single<PromoAttributionCoordinator> { get<PromoAttributionService>() }

    // ВНИМАНИЕ: порядок параметров соответствует реальной сигнатуре
    single {
        BookingTemplateService(
            get<BookingTemplateRepository>(),
            get<BookingService>(),
            get<UserRepository>(),
            get<UserRoleRepository>(),
            get<NotificationsOutboxRepository>(),
        )
    }

    // Outbound port stub
    single<SendPort> { DummySendPort }

    // High-level services/workers
    single { BookingService(get(), get(), get(), get(), get()) }
    single { OutboxWorker(get(), get()) }
}

private object DummySendPort : SendPort {
    override suspend fun send(topic: String, payload: JsonObject): SendOutcome = SendOutcome.Ok
}
