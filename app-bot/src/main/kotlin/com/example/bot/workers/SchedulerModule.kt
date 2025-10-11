package com.example.bot.workers

import com.example.bot.telemetry.Telemetry
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import java.time.Duration
import org.koin.core.error.ClosedScopeException

/** Optional scheduler configuration sourced from environment variables. */
data class SchedulerConfig(
    val tickInterval: Duration =
        System.getenv("SCHEDULER_TICK_MS")?.toLongOrNull()?.let(Duration::ofMillis) ?: DEFAULT_TICK_INTERVAL,
    val batchSize: Int = System.getenv("SCHEDULER_BATCH")?.toIntOrNull() ?: DEFAULT_BATCH_SIZE,
)

/** Lightweight metrics adapter around Telemetry/Micrometer. */
class WorkerMetrics(
    private val onStarted: (() -> Unit)? = null,
    private val onStopped: (() -> Unit)? = null,
    private val onEnqueued: ((Int) -> Unit)? = null,
    private val onError: (() -> Unit)? = null,
) {
    fun markStarted() = onStarted?.invoke() ?: Unit
    fun markStopped() = onStopped?.invoke() ?: Unit
    fun markEnqueued(n: Int) = onEnqueued?.invoke(n) ?: Unit
    fun markError() = onError?.invoke() ?: Unit

    companion object {
        fun fromTelemetry(): WorkerMetrics {
            return runCatching {
                val registry = Telemetry.registry
                val started = registry.counter("campaign_scheduler_started_total")
                val stopped = registry.counter("campaign_scheduler_stopped_total")
                val enqueued = registry.counter("campaign_scheduler_enqueued_total")
                val errors = registry.counter("campaign_scheduler_errors_total")
                WorkerMetrics(
                    onStarted = { started.increment() },
                    onStopped = { stopped.increment() },
                    onEnqueued = { n -> if (n > 0) enqueued.increment(n.toDouble()) },
                    onError = { errors.increment() },
                )
            }.getOrElse { WorkerMetrics() }
        }
    }
}

val schedulerModule =
    module {
        single(named("campaignSchedulerScope")) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        single { SchedulerConfig() }
        single { WorkerMetrics.fromTelemetry() }
        single {
            CampaignScheduler(
                scope = get(named("campaignSchedulerScope")),
                api = get(),
                config = get(),
                metrics = get(),
            )
        }
    }

fun Application.launchCampaignSchedulerOnStart() {
    var scheduler: CampaignScheduler? = null

    monitor.subscribe(ApplicationStarted) {
        runCatching {
            val worker = getKoin().get<CampaignScheduler>()
            scheduler = worker
            worker.start()
        }.onFailure {
            if (it !is ClosedScopeException) {
                throw it
            }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        val worker = scheduler
        if (worker != null) {
            runBlocking { worker.stop() }
        }
        scheduler = null
    }
}
