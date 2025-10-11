package com.example.bot.webapp

import com.example.bot.plugins.InitDataAuth
import com.example.bot.plugins.InitDataAuth.requireInitData
import com.example.bot.plugins.envString
import com.example.bot.security.auth.TelegramUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.webAppRoutes() {
    install(DefaultHeaders) {
        header(
            name = "Content-Security-Policy",
            value =
                "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' https://*.telegram.org https://*.t.me; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https:; " +
                    "connect-src 'self' https://api.telegram.org https://*.t.me https://*.telegram.org; " +
                    "frame-ancestors https://web.telegram.org https://*.telegram.org https://*.t.me; " +
                    "base-uri 'self'; form-action 'self';",
        )
    }

    install(Compression) {
        gzip()
    }

    val classLoader = environment.classLoader

    routing {
        get("/app") {
            val bytes =
                classLoader
                    .getResource("webapp/app/index.html")
                    ?.readBytes()
                    ?: error("Mini App index.html not found at resources/webapp/app/index.html")
            call.respondBytes(bytes, ContentType.Text.Html)
        }

        staticResources("/app", "webapp/app")

        route("/miniapp") {
            get("/me") {
                val botToken = envString("BOT_TOKEN")
                if (!call.requireInitData(botToken)) {
                    return@get
                }
                val user = call.attributes[InitDataAuth.TelegramUserKey]
                call.respond(HttpStatusCode.OK, MiniAppMeResponse(ok = true, user = user))
            }
        }
    }
}

@Serializable
private data class MiniAppMeResponse(
    val ok: Boolean,
    val user: TelegramUser,
)
