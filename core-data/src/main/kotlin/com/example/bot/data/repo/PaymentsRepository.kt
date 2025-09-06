package com.example.bot.data.repo

import com.example.bot.booking.InvoiceInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for storing invoices and payment records.
 */
interface PaymentsRepository {
    suspend fun save(invoice: InvoiceInfo, idempotencyKey: String)
    suspend fun findByIdempotencyKey(idempotencyKey: String): InvoiceInfo?
}

/**
 * In-memory implementation used in tests.
 */
class InMemoryPaymentsRepository : PaymentsRepository {
    private val byKey = ConcurrentHashMap<String, InvoiceInfo>()

    override suspend fun save(invoice: InvoiceInfo, idempotencyKey: String) {
        byKey.putIfAbsent(idempotencyKey, invoice)
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): InvoiceInfo? = byKey[idempotencyKey]
}

