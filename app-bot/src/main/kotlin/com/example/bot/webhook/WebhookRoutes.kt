package com.example.bot.webhook

import com.example.bot.dedup.UpdateDeduplicator
import com.example.bot.telegram.TelegramClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebhookRoutes")

/**
 * Registers the `/webhook` route that processes updates sent by Telegram.
 */
fun Route.webhookRoute(
    secretToken: String,
    deduplicator: UpdateDeduplicator,
    handler: suspend (UpdateDto) -> WebhookReply?,
    client: TelegramClient,
) {
    post("/webhook") {
        call.verifyWebhookSecret(secretToken)
        val update = call.receive<UpdateDto>()
        if (deduplicator.isDuplicate(update.updateId)) {
            logger.debug("Duplicate update {}", update.updateId)
            call.respond(HttpStatusCode.OK)
            return@post
        }

        when (val reply = handler(update)) {
            null -> call.respond(HttpStatusCode.OK)
            is WebhookReply.Inline -> call.respond(reply.payload)
            is WebhookReply.Async -> {
                call.application.launch { client.send(reply.request) }
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

/** Lightweight representation of a Telegram update used for webhook processing. */
@Serializable
data class UpdateDto(
    @SerialName("update_id") val updateId: Long,
    val message: MessageDto? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQueryDto? = null,
)

@Serializable
data class MessageDto(
    @SerialName("message_id") val messageId: Long,
    val chat: ChatDto,
    val text: String? = null,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
)

@Serializable
data class ChatDto(@SerialName("id") val id: Long)

@Serializable
data class CallbackQueryDto(
    val id: String,
    @SerialName("from") val from: UserDto? = null,
    val data: String? = null,
)

@Serializable
data class UserDto(@SerialName("id") val id: Long)

/** Represents result of handling an update. */
sealed interface WebhookReply {
    /** Inline reply returned directly in webhook response body. */
    data class Inline(val payload: Map<String, Any?>) : WebhookReply

    /** Asynchronous response that should be executed via Telegram API. */
    data class Async(val request: Any) : WebhookReply
}
