package com.example.bot.notifications

import com.example.bot.data.notifications.OutboxStatus
import com.example.bot.data.repo.OutboxRepository
import com.example.bot.telegram.NotifySender
import com.example.bot.telegram.SendResult
import com.example.bot.workers.OutboxWorker
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import java.time.OffsetDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import testing.RequiresDocker

@RequiresDocker
@Tag("it")
class OutboxRateLimitIT :
    StringSpec({
        "worker respects rate limits" {
            val config = NotifyConfig(globalRps = 5, chatRps = 2.0, workerParallelism = 4)
            val policy = DefaultRatePolicy(config.globalRps, config.chatRps)

            data class State(
                val msg: NotifyMessage,
                var attempts: Int = 0,
                var status: OutboxStatus = OutboxStatus.NEW,
                var next: OffsetDateTime = OffsetDateTime.now(),
            )
            val records = ConcurrentHashMap<Long, State>()
            val total = 40
            val chatA = 1L
            val chatB = 2L
            for (i in 0 until total) {
                val chat = if (i % 2 == 0) chatA else chatB
                records[i.toLong()] =
                    State(
                        msg = NotifyMessage(chat, null, NotifyMethod.TEXT, "hi", null, null, null, null, null),
                    )
            }

            val repo = mockk<OutboxRepository>()
            coEvery { repo.pickBatch(any(), any()) } coAnswers {
                val now: OffsetDateTime = arg(0)
                val limit: Int = arg(1)
                records.entries
                    .filter { it.value.status == OutboxStatus.NEW && !it.value.next.isAfter(now) }
                    .sortedBy { it.key }
                    .take(limit)
                    .map { (id, st) -> OutboxRepository.Record(id, st.msg, null, st.attempts) }
            }
            coEvery { repo.markSent(any(), any()) } coAnswers {
                val id: Long = arg(0)
                records[id]?.apply {
                    status = OutboxStatus.SENT
                    attempts++
                }
                1
            }
            coEvery { repo.markFailed(any(), any(), any()) } coAnswers {
                val id: Long = arg(0)
                val next: OffsetDateTime = arg(2)
                records[id]?.apply {
                    status = OutboxStatus.NEW
                    this.next = next
                    attempts++
                }
                1
            }
            coEvery { repo.postpone(any(), any()) } coAnswers {
                val id: Long = arg(0)
                val next: OffsetDateTime = arg(1)
                records[id]?.apply {
                    status = OutboxStatus.NEW
                    this.next = next
                }
                1
            }
            coEvery { repo.markPermanentFailure(any(), any()) } coAnswers { 1 }
            coEvery { repo.isSent(any()) } returns false

            val timestamps = ConcurrentHashMap<Long, MutableList<Long>>()
            val failOnce = AtomicBoolean(false)
            val sender = mockk<NotifySender>()
            coEvery { sender.sendMessage(any(), any(), any(), any()) } coAnswers {
                val chat = firstArg<Long>()
                val now = System.currentTimeMillis()
                timestamps.computeIfAbsent(chat) { Collections.synchronizedList(mutableListOf()) }.add(now)
                if (chat == chatA && failOnce.compareAndSet(false, true)) {
                    SendResult.RetryAfter(1000)
                } else {
                    SendResult.Ok(messageId = null)
                }
            }

            val scope = CoroutineScope(Dispatchers.Default)
            val worker = OutboxWorker(scope, OutboxWorker.Deps(repo, sender, policy, config))
            worker.start()
            scope.launch {
                delay(4000)
                scope.cancel()
            }
            delay(4500)

            val allTimes = timestamps.values.flatten().sorted()
            checkRate(allTimes, 5)
            checkRate(timestamps[chatA].orEmpty().sorted(), 2)
            checkRate(timestamps[chatB].orEmpty().sorted(), 2)

            val firstFail = timestamps[chatA]?.firstOrNull() ?: 0L
            val after = timestamps[chatA]?.dropWhile { it - firstFail < 1000 } ?: emptyList()
            after.forEach { (it - firstFail).shouldBeGreaterThanOrEqual(1000) }
        }
    })

private fun checkRate(times: List<Long>, limit: Int) {
    for (i in times.indices) {
        val windowEnd = times[i] + 1000
        var count = 0
        var j = i
        while (j < times.size && times[j] < windowEnd) {
            count++
            j++
        }
        count.shouldBeLessThanOrEqual(limit)
    }
}
