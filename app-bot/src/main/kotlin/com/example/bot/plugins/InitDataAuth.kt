package com.example.bot.plugins

import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.auth.TelegramUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

val MiniAppUserKey: AttributeKey<TelegramMiniUser> = AttributeKey("miniapp.user")

@Serializable
data class TelegramMiniUser(
    val id: Long,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
)

private val logger = LoggerFactory.getLogger("InitDataAuth")

private var validator: (String, String) -> TelegramMiniUser? = { raw, token ->
    InitDataValidator.validate(raw, token)?.toMiniUser()
}

fun Route.withMiniAppAuth(botTokenProvider: () -> String) {
    intercept(ApplicationCallPipeline.Plugins) {
        val initData = extractInitData(call)
        if (initData.isNullOrBlank()) {
            call.respondUnauthorized("initData missing")
            finish()
            return@intercept
        }

        val botToken = botTokenProvider.invoke()
        if (botToken.isBlank()) {
            call.respondUnauthorized("bot token missing")
            finish()
            return@intercept
        }

        val user = runCatching { validator(initData, botToken) }.getOrElse { throwable ->
            logger.warn("initData validation threw", throwable)
            null
        }

        if (user == null) {
            call.respondUnauthorized("initData invalid")
            finish()
            return@intercept
        }

        call.attributes.put(MiniAppUserKey, user)
    }
}

internal fun overrideMiniAppValidatorForTesting(
    override: (String, String) -> TelegramMiniUser?,
) {
    validator = override
}

internal fun resetMiniAppValidator() {
    validator = { raw, token -> InitDataValidator.validate(raw, token)?.toMiniUser() }
}

private fun TelegramUser.toMiniUser(): TelegramMiniUser =
    TelegramMiniUser(id = id, username = username)

private suspend fun extractInitData(call: ApplicationCall): String? {
    val queryValue = call.request.queryParameters["initData"]?.takeIf { it.isNotBlank() }
    val headerValue = call.request.header("X-Telegram-InitData")?.takeIf { it.isNotBlank() }
        ?: call.request.header("X-Telegram-Init-Data")?.takeIf { it.isNotBlank() }

    if (!queryValue.isNullOrBlank()) {
        return queryValue
    }
    if (!headerValue.isNullOrBlank()) {
        return headerValue
    }

    return extractInitDataFromBodyOrNull(call)
}

private suspend fun ApplicationCall.respondUnauthorized(reason: String) {
    logger.info("Mini App request unauthorized: {}", reason)
    respond(HttpStatusCode.Unauthorized, mapOf("error" to reason))
}

private suspend fun extractInitDataFromBodyOrNull(call: ApplicationCall): String? {
    return try {
        when {
            call.request.contentType().match(ContentType.Application.FormUrlEncoded) -> {
                call.receiveParameters()["initData"]
            }

            call.request.contentType().match(ContentType.Application.Json) -> {
                val body = call.receiveText()
                INIT_DATA_REGEX.find(body)?.groupValues?.getOrNull(1)
            }

            else -> null
        }
    } catch (ignore: Throwable) {
        null
    }
}

private val INIT_DATA_REGEX = "\"initData\"\\s*:\\s*\"([^\"]+)\"".toRegex()
