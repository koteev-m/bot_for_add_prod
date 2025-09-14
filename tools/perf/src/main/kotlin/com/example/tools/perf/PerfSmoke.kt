package com.example.tools.perf

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

data class Args(
    val baseUrl: String,
    val endpoints: List<String>,
    val workers: Int,
    val durationSec: Int,
    val targetRps: Int,
    val assertP95Ms: Long,
    val maxErrorRate: Double,
    val warmupSec: Int
)

private fun parseArgs(raw: Array<String>): Args {
    fun get(name: String, def: String? = null): String? =
        raw.asSequence()
            .mapNotNull {
                val p = it.trim()
                if (!p.startsWith("--")) null else p.removePrefix("--")
            }
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i < 0) kv to "" else kv.substring(0, i) to kv.substring(i + 1)
            }
            .toMap()[name] ?: def

    val url = get("url") ?: error("--url is required (e.g. --url=http://localhost:8080)")
    val eps = (get("endpoints", "/health,/ready") ?: "/health,/ready")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val workers = (get("workers", "8") ?: "8").toInt().coerceAtLeast(1)
    val dur = (get("duration-sec", "30") ?: "30").toInt().coerceAtLeast(1)
    val rps = (get("target-rps", "0") ?: "0").toInt().coerceAtLeast(0)
    val p95 = (get("assert-p95-ms", "300") ?: "300").toLong().coerceAtLeast(1)
    val maxErr = (get("max-error-rate", "0.01") ?: "0.01").toDouble().coerceIn(0.0, 1.0)
    val warm = (get("warmup-sec", "3") ?: "3").toInt().coerceAtLeast(0)

    return Args(
        baseUrl = url.trimEnd('/'),
        endpoints = eps,
        workers = workers,
        durationSec = dur,
        targetRps = rps,
        assertP95Ms = p95,
        maxErrorRate = maxErr,
        warmupSec = warm
    )
}

private class Metrics {
    val started = AtomicLong(0)
    val successful = AtomicLong(0)
    val errors = AtomicLong(0)
    val latenciesMs = Collections.synchronizedList(mutableListOf<Long>())
}

suspend fun main(vararg raw: String) {
    val a = parseArgs(raw as Array<String>)
    val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    val workerPool = Executors.newFixedThreadPool(a.workers.coerceAtMost(256)) { r ->
        Thread(r, "perf-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(workerPool)

    val m = Metrics()

    if (a.warmupSec > 0) {
        val endWarmup = System.nanoTime() + a.warmupSec * 1_000_000_000L
        val jobs = (1..a.workers).map {
            scope.launch {
                var idx = 0
                while (System.nanoTime() < endWarmup) {
                    val ep = a.endpoints[idx % a.endpoints.size]
                    doOne(http, a.baseUrl, ep)
                    idx++
                }
            }
        }
        jobs.joinAll()
    }

    val testEnd = System.nanoTime() + a.durationSec * 1_000_000_000L
    val targetDelayNs: Long = if (a.targetRps > 0) {
        val perWorker = max(1.0, a.targetRps.toDouble() / a.workers.toDouble())
        (1_000_000_000.0 / perWorker).toLong()
    } else 0L

    val jobs = (1..a.workers).map { w ->
        scope.launch(Dispatchers.IO) {
            var idx = w % a.endpoints.size
            var nextTime = System.nanoTime()
            while (System.nanoTime() < testEnd) {
                if (targetDelayNs > 0) {
                    val now = System.nanoTime()
                    if (now < nextTime) {
                        val sleepNs = nextTime - now
                        delay(sleepNs.nanoseconds)
                    }
                    nextTime += targetDelayNs
                }
                val ep = a.endpoints[idx % a.endpoints.size]
                idx++
                val t0 = System.nanoTime()
                m.started.incrementAndGet()
                val status = doOne(http, a.baseUrl, ep)
                val tookMs = (System.nanoTime() - t0) / 1_000_000
                if (status in 200..399) {
                    m.successful.incrementAndGet()
                    m.latenciesMs.add(tookMs)
                } else {
                    m.errors.incrementAndGet()
                }
            }
        }
    }

    jobs.joinAll()
    scope.cancel()

    val total = m.started.get()
    val ok = m.successful.get()
    val err = m.errors.get()
    val errRate = if (total == 0L) 0.0 else err.toDouble() / total.toDouble()
    val rps = if (a.durationSec > 0) total.toDouble() / a.durationSec.toDouble() else 0.0

    val p50 = percentile(m.latenciesMs, 0.50)
    val p95 = percentile(m.latenciesMs, 0.95)

    printReport(a, total, ok, err, errRate, rps, p50, p95)

    val slaFail = (p95 != null && p95 > a.assertP95Ms) || errRate > a.maxErrorRate
    if (slaFail) {
        System.err.println("SLA FAILED: p95=${p95 ?: -1}ms (limit=${a.assertP95Ms}ms), errorRate=${"%.4f".format(Locale.ROOT, errRate)} (limit=${a.maxErrorRate})")
        kotlin.system.exitProcess(1)
    } else {
        println("SLA OK")
        kotlin.system.exitProcess(0)
    }
}

private fun percentile(values: List<Long>, q: Double): Long? {
    if (values.isEmpty()) return null
    val arr = values.toMutableList().sorted()
    val idx = min(arr.size - 1, max(0, ceil(q * arr.size).toInt() - 1))
    return arr[idx]
}

private fun printReport(
    a: Args,
    total: Long,
    ok: Long,
    err: Long,
    errRate: Double,
    rps: Double,
    p50: Long?,
    p95: Long?
) {
    println("=== PerfSmoke Report ===")
    println("URL: ${a.baseUrl}")
    println("Endpoints: ${a.endpoints.joinToString(",")}")
    println("Workers: ${a.workers}, Duration: ${a.durationSec}s, TargetRps: ${a.targetRps}")
    println("Total: $total, OK: $ok, Errors: $err (${String.format(Locale.ROOT, "%.2f%%", errRate * 100)})")
    println("RPS: ${String.format(Locale.ROOT, "%.2f", rps)}")
    println("p50: ${p50 ?: -1} ms, p95: ${p95 ?: -1} ms, SLA p95 <= ${a.assertP95Ms} ms")
    val json = """{"total":$total,"ok":$ok,"errors":$err,"errorRate":${"%.6f".format(Locale.ROOT, errRate)},"rps":${"%.2f".format(Locale.ROOT, rps)},"p50":${p50 ?: -1},"p95":${p95 ?: -1},"assertP95Ms":${a.assertP95Ms},"maxErrorRate":${a.maxErrorRate}}"""
    println(json)
}

private suspend fun doOne(client: HttpClient, baseUrl: String, endpoint: String): Int {
    val uri = URI.create(if (endpoint.startsWith("/")) "$baseUrl$endpoint" else "$baseUrl/$endpoint")
    val req = HttpRequest.newBuilder()
        .uri(uri)
        .GET()
        .timeout(Duration.ofSeconds(10))
        .build()
    val resp = client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
    return resp.statusCode()
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { result, ex ->
            if (ex == null) cont.resume(result) else cont.resumeWithException(ex)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
