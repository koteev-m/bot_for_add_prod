package com.example.bot.plugins

import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.auth.TelegramUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

object InitDataAuth {
    val TelegramUserKey: AttributeKey<TelegramUser> = AttributeKey("miniapp.telegram.user")

    private val log = LoggerFactory.getLogger(InitDataAuth::class.java)
    private val installKey: AttributeKey<Boolean> = AttributeKey("miniapp.initdata.installed")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var validator: (String, String) -> TelegramUser? = { raw, token ->
        InitDataValidator.validate(raw, token)
    }

    fun Application.installInitDataAuth() {
        if (attributes.contains(installKey)) {
            return
        }
        attributes.put(installKey, true)

        val enabled = envBool("MINIAPP_AUTH_ENABLED", default = true)
        if (!enabled) {
            log.info("InitDataAuth disabled by env")
            return
        }

        log.info("InitDataAuth enabled for Mini App endpoints")
        val botToken = envString("BOT_TOKEN")
        if (botToken.isNullOrBlank()) {
            log.warn("InitDataAuth enabled but BOT_TOKEN is missing; Mini App endpoints will reject as 401")
        }
    }

    suspend fun ApplicationCall.requireInitData(botToken: String?): Boolean {
        suspend fun unauthorized(reason: String): Boolean {
            log.warn("MiniApp auth failed: {}", reason)
            val payload = mapOf("error" to "unauthorized", "reason" to reason)
            respond(HttpStatusCode.Unauthorized, payload)
            return false
        }

        val enabled = application.envBool("MINIAPP_AUTH_ENABLED", default = true)
        if (!enabled) {
            return true
        }

        var failureReason: String? = null
        val raw = extractInitData()
        when {
            raw.isNullOrBlank() -> failureReason = "initData missing"
            botToken.isNullOrBlank() -> failureReason = "BOT_TOKEN missing on server"
            else -> {
                val user = validator(raw, botToken)
                if (user == null) {
                    failureReason = "invalid initData"
                } else {
                    attributes.put(TelegramUserKey, user)
                }
            }
        }
        return if (failureReason == null) {
            true
        } else {
            unauthorized(failureReason)
        }
    }

    private suspend fun ApplicationCall.extractInitData(): String? {
        val header = request.headers["X-Telegram-Init-Data"]?.takeIf { it.isNotBlank() }
        val query = request.queryParameters["initData"]?.takeIf { it.isNotBlank() }
        val fromBody = if (header == null && query == null) readInitDataFromBody() else null
        return header ?: query ?: fromBody
    }

    private suspend fun ApplicationCall.readInitDataFromBody(): String? {
        return when (request.contentType().withoutParameters()) {
            ContentType.Application.FormUrlEncoded -> readInitDataFromForm()
            ContentType.Application.Json -> readInitDataFromJson()
            else -> null
        }
    }

    private suspend fun ApplicationCall.readInitDataFromForm(): String? =
        try {
            receiveParameters()["initData"]
        } catch (_: Throwable) {
            null
        }

    private suspend fun ApplicationCall.readInitDataFromJson(): String? =
        try {
            val body = receiveText()
            if (body.isBlank()) {
                null
            } else {
                val element = json.decodeFromString<JsonElement>(body)
                element.jsonObject["initData"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Throwable) {
            null
        }

    internal fun overrideValidatorForTesting(override: (String, String) -> TelegramUser?) {
        validator = override
    }

    internal fun resetValidator() {
        validator = { raw, token -> InitDataValidator.validate(raw, token) }
    }
}
