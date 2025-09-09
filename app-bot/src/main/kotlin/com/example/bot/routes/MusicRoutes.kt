package com.example.bot.routes

import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicService
import com.example.bot.music.MusicService.ItemFilter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.security.MessageDigest

/** Routes serving music items. */
fun Route.musicRoutes(service: MusicService) {
    route("/api/music") {
        get("/items") {
            val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
            val tag = call.request.queryParameters["tag"]
            val q = call.request.queryParameters["q"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val filter = ItemFilter(clubId, tag, q, limit, offset)
            val items = service.listItems(filter)
            val updatedMax = items.maxOfOrNull { it.publishedAt ?: java.time.Instant.EPOCH } ?: java.time.Instant.EPOCH
            val etagSource = "$clubId|$tag|$q|${updatedMax.toEpochMilli()}"
            val etag =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(etagSource.toByteArray())
                    .joinToString(separator = "") { String.format("%02x", it) }
            val ifNone = call.request.headers["If-None-Match"]
            val weak = "W/\"$etag\""
            if (ifNone == weak) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.response.headers.append("ETag", weak)
                call.respond(items)
            }
        }

        post("/items") {
            val req = call.receive<MusicItemCreate>()
            val actor = 0L // authentication omitted for brevity
            val item = service.createItem(req, actor)
            call.respond(HttpStatusCode.Created, item)
        }
    }
}
