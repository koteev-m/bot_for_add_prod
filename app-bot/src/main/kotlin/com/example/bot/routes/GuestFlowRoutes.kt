package com.example.bot.routes

import com.example.bot.availability.AvailabilityService
import com.example.bot.render.HallRenderer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail
import java.security.MessageDigest
import java.time.Instant

/**
 * Routes serving hall images and open nights for guest flow.
 */
fun Route.guestFlowRoutes(availability: AvailabilityService, renderer: HallRenderer) {
    route("/clubs/{clubId}") {
        get("/nights") {
            val clubId = call.parameters.getOrFail("clubId").toLong()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_NIGHTS_LIMIT
            val nights = availability.listOpenNights(clubId, limit)
            call.respond(nights)
        }

        get("/nights/{startUtc}/hall.png") {
            val clubId = call.parameters.getOrFail("clubId").toLong()
            val startUtc = call.parameters.getOrFail("startUtc")
            val instant =
                runCatching { Instant.parse(startUtc) }.getOrElse {
                    call.respond(HttpStatusCode.UnprocessableEntity)
                    return@get
                }
            val scale = call.request.queryParameters["scale"]?.toIntOrNull() ?: 1
            val tables = availability.listFreeTables(clubId, instant)
            val bytes = renderer.render(clubId, tables, scale)
            val statusVector = tables.joinToString(";") { "${it.tableId}:${it.status}" }
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$clubId|$instant|$statusVector".toByteArray())
                    .joinToString("") { java.lang.String.format(java.util.Locale.ROOT, "%02x", it) }
            val etag = "\"$hash\""
            val ifNone = call.request.headers[HttpHeaders.IfNoneMatch]
            if (ifNone == etag) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.response.headers.append(HttpHeaders.ETag, etag)
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=60")
                call.respondBytes(bytes, ContentType.Image.PNG)
            }
        }
    }
}

private const val DEFAULT_NIGHTS_LIMIT = 8
