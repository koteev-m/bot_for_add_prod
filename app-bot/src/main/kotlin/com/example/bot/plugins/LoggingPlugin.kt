package com.example.bot.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.event.Level
import java.util.concurrent.ThreadLocalRandom
import kotlin.text.buildString

private const val ID_LENGTH = 16
private const val MAX_ID_LENGTH = 64

fun Application.installLogging() {
    val startTimeKey = AttributeKey<Long>("call-start")

    install(CallId) {
        header(HttpHeaders.XRequestId)
        header("X-Correlation-ID")
        replyToHeader(HttpHeaders.XRequestId)
        generate { randomId() }
        verify { id ->
            id.length <= MAX_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        mdc("callId") { it.callId }
        mdc("path") { it.request.path() }
        mdc("method") { it.request.httpMethod.value }
        mdc("status") {
            it.response
                .status()
                ?.value
                ?.toString()
        }
        mdc("took_ms") {
            val start = if (it.attributes.contains(startTimeKey)) it.attributes[startTimeKey] else null
            start?.let { st -> (System.currentTimeMillis() - st).toString() }
        }
    }

    intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
        call.attributes.put(startTimeKey, System.currentTimeMillis())
    }
}

private fun randomId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_ .".replace(" ", "")
    val rnd = ThreadLocalRandom.current()
    return buildString(ID_LENGTH) { repeat(ID_LENGTH) { append(chars[rnd.nextInt(chars.length)]) } }
}
