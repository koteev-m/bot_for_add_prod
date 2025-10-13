package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.finalize.DefaultPaymentsFinalizeService
import com.example.bot.payments.finalize.PaymentsFinalizeService
import org.koin.dsl.module

val paymentsModule = module {
    single<PaymentsRepository> { PaymentsRepositoryImpl(get()) }
    single<PaymentsFinalizeService> {
        DefaultPaymentsFinalizeService(
            bookingService = get<BookingService>(),
            paymentsRepository = get(),
        )
    }
    single<PaymentsService> {
        DefaultPaymentsService(
            finalizeService = get(),
        )
    }
}
