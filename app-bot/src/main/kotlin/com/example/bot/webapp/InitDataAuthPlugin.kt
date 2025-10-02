package com.example.bot.webapp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

private const val MAX_HEADER_LENGTH = 8192

data class TelegramPrincipal(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

class InitDataAuthConfig {
    lateinit var botTokenProvider: () -> String
    var maxAge: Duration = Duration.ofHours(24)
    var clock: Clock = Clock.systemUTC()
    var headerName: String = "X-Telegram-Init-Data"

    /** Возврати true, чтобы пропустить проверку initData для конкретного запроса. */
    var exclude: (ApplicationCall) -> Boolean = { false }

    internal fun validate() {
        check(::botTokenProvider.isInitialized) { "botTokenProvider must be provided" }
    }
}

val InitDataPrincipalKey: AttributeKey<TelegramPrincipal> = AttributeKey("webapp.principal")

val InitDataAuthPlugin =
    createApplicationPlugin("InitDataAuthPlugin", ::InitDataAuthConfig) {
        pluginConfig.validate()
        val logger = LoggerFactory.getLogger("InitDataAuth")

        onCall { call ->
            // >>> пропуск для публичных эндпоинтов
            if (pluginConfig.exclude(call)) return@onCall

            val header = call.request.headers[pluginConfig.headerName]
            if (header == null || header.length > MAX_HEADER_LENGTH) {
                logger.warn("initData header missing or too large")
                call.respond(HttpStatusCode.Unauthorized)
                return@onCall
            }
            val botToken = pluginConfig.botTokenProvider.invoke()
            val verified =
                WebAppInitDataVerifier.verify(header, botToken, pluginConfig.maxAge, pluginConfig.clock)
            if (verified == null) {
                logger.warn("initData verification failed")
                call.respond(HttpStatusCode.Unauthorized)
                return@onCall
            }
            val principal =
                TelegramPrincipal(
                    userId = verified.userId,
                    username = verified.username,
                    firstName = verified.firstName,
                    lastName = verified.lastName,
                )
            call.attributes.put(InitDataPrincipalKey, principal)
        }
    }
