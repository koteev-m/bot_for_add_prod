package com.example.bot.workers

import com.example.bot.notifications.SchedulerApi
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.concurrent.atomic.AtomicInteger

class CampaignSchedulerWiringTest :
    StringSpec({
        "scheduler starts on application start" {
            val fakeApi = CountingSchedulerApi()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            testApplication {
                application {
                    install(Koin) {
                        allowOverride(true)
                        modules(
                            schedulerModule,
                            module {
                                single { scope }
                                single<SchedulerApi> { fakeApi }
                            },
                        )
                    }

                    launchCampaignSchedulerOnStart()

                    runBlocking {
                        environment.monitor.raise(ApplicationStarted, this@application)

                        try {
                            withTimeout(5_000) {
                                while (fakeApi.listActiveCalls.get() == 0) {
                                    delay(10)
                                }
                            }
                        } finally {
                            scope.cancel()
                        }
                    }
                }
            }

            fakeApi.listActiveCalls.get().shouldBeGreaterThan(0)
        }
    })

private class CountingSchedulerApi : SchedulerApi {
    val listActiveCalls = AtomicInteger(0)
    val enqueueBatchCalls = AtomicInteger(0)

    override suspend fun listActive(): List<SchedulerApi.Campaign> {
        listActiveCalls.incrementAndGet()
        return emptyList()
    }

    override suspend fun markSending(id: Long) {
        // no-op
    }

    override suspend fun enqueueBatch(
        campaignId: Long,
        limit: Int,
    ): Int {
        enqueueBatchCalls.incrementAndGet()
        return 0
    }

    override suspend fun progress(campaignId: Long): SchedulerApi.Progress =
        SchedulerApi.Progress(enqueued = 0, total = 0)

    override suspend fun markDone(id: Long) {
        // no-op
    }
}
