package com.example.notify

import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import com.example.bot.data.repo.OutboxRepository
import com.example.bot.notifications.NotifyConfig
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.Permit
import com.example.bot.notifications.RatePolicy
import com.example.bot.notifications.TimeSource
import com.example.bot.telegram.NotifySender
import com.example.bot.telegram.SendResult
import com.example.bot.workers.OutboxWorker
import com.example.testing.support.DbSupport
import com.example.testing.support.PgContainer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OutboxWorkerSmokeTest : PgContainer() {
    @BeforeEach
    fun prepare() {
        DbSupport.resetAndMigrate(dataSource)
    }

    @Test
    fun `should send all messages`() =
        runTest {
            val repo = OutboxRepository(database)
            val sender = mockk<NotifySender>()
            coEvery { sender.sendMessage(any(), any(), any(), any()) } returns SendResult.Ok(messageId = null)
            coEvery { sender.sendPhoto(any(), any(), any(), any(), any()) } returns SendResult.Ok(messageId = null)
            coEvery { sender.sendMediaGroup(any(), any(), any(), any()) } returns SendResult.Ok(messageId = null)
            val policy =
                object : RatePolicy {
                    override val timeSource: TimeSource =
                        object : TimeSource {
                            override fun nowMs() = System.currentTimeMillis()
                        }

                    override fun acquireGlobal(n: Int, now: Long) = Permit(true)

                    override fun acquireChat(chatId: Long, n: Int, now: Long) = Permit(true)

                    override fun on429(chatId: Long?, retryAfter: Long, now: Long) {}
                }
            val registry = SimpleMeterRegistry()
            val config = NotifyConfig(workerParallelism = 1)

            for (i in 1..10) {
                repo.enqueue(NotifyMessage(i.toLong(), null, NotifyMethod.TEXT, "hi", null, null, null, null, null))
            }

            val worker = OutboxWorker(this, OutboxWorker.Deps(repo, sender, policy, config, registry))
            worker.start()
            advanceUntilIdle()

            val sent =
                newSuspendedTransaction(db = database) {
                    NotificationsOutboxTable.select { NotificationsOutboxTable.status eq OutboxStatus.SENT.name }.count()
                }
            assertEquals(10, sent)
            assertEquals(10.0, registry.counter("notify.sent", "method", "TEXT", "threaded", "false").count())
        }
}
