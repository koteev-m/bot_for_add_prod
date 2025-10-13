package com.example.bot.payments.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

fun interface ProviderRefundClient {
    suspend fun send(command: ProviderRefundCommand): ProviderRefundResult
}

data class ProviderRefundCommand(
    val topic: String,
    val payload: JsonObject,
    val idempotencyKey: String,
)

sealed interface ProviderRefundResult {
    data object Success : ProviderRefundResult

    data class Retry(
        val status: Int?,
        val retryAfter: Duration?,
        val body: String? = null,
        val cause: Throwable? = null,
    ) : ProviderRefundResult

    data class Failure(val status: Int, val body: String?) : ProviderRefundResult
}

data class ProviderRefundClientConfig(
    val url: String,
    val token: String,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(10),
) {
    companion object {
        fun fromEnv(): ProviderRefundClientConfig {
            val url = System.getenv("REFUND_PROVIDER_URL")?.takeIf { it.isNotBlank() }
                ?: error("REFUND_PROVIDER_URL is not configured")
            val token = System.getenv("REFUND_PROVIDER_TOKEN")?.takeIf { it.isNotBlank() }
                ?: error("REFUND_PROVIDER_TOKEN is not configured")
            return ProviderRefundClientConfig(url = url, token = token)
        }
    }
}

class HttpProviderRefundClient(
    private val config: ProviderRefundClientConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json,
) : ProviderRefundClient {
    private val logger = LoggerFactory.getLogger(HttpProviderRefundClient::class.java)
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeout.toMillis()
                requestTimeoutMillis = config.requestTimeout.toMillis()
                socketTimeoutMillis = config.readTimeout.toMillis()
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }

    override suspend fun send(command: ProviderRefundCommand): ProviderRefundResult {
        return try {
            val response =
                client.post(config.url) {
                    buildHeaders(this, command)
                    contentType(ContentType.Application.Json)
                    setBody(ProviderRefundRequest(command.topic, command.payload))
                }
            mapResponse(response)
        } catch (ex: Exception) {
            logger.warn("Provider refund call failed", ex)
            ProviderRefundResult.Retry(status = null, retryAfter = null, cause = ex)
        }
    }

    private fun buildHeaders(
        builder: HttpRequestBuilder,
        command: ProviderRefundCommand,
    ) {
        builder.header(HttpHeaders.Authorization, "Bearer ${config.token}")
        builder.header("Idempotency-Key", command.idempotencyKey)
    }

    private suspend fun mapResponse(response: HttpResponse): ProviderRefundResult {
        val status = response.status.value
        val body = runCatching { response.bodyAsText() }.getOrNull()
        return when {
            status in 200..299 -> ProviderRefundResult.Success
            status == 429 -> ProviderRefundResult.Retry(status, parseRetryAfter(response), body)
            status >= 500 -> ProviderRefundResult.Retry(status, parseRetryAfter(response), body)
            else -> ProviderRefundResult.Failure(status, body)
        }
    }

    private fun parseRetryAfter(response: HttpResponse): Duration? {
        val header = response.headers[HttpHeaders.RetryAfter] ?: return null
        header.toLongOrNull()?.let { seconds ->
            return Duration.ofSeconds(seconds)
        }
        return runCatching {
            val instant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header))
            val now = Instant.now(clock)
            if (instant.isAfter(now)) Duration.between(now, instant) else Duration.ZERO
        }.getOrNull()
    }

    @Serializable
    private data class ProviderRefundRequest(
        val event: String,
        val payload: JsonObject,
    )
}
