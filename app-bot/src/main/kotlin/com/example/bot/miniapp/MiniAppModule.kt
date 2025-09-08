package com.example.bot.miniapp

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File

/** Ktor module serving Mini App static files. */
fun Application.miniAppModule() {
    install(Compression) {
        gzip()
    }
    install(DefaultHeaders) {
        header(HttpHeaders.XFrameOptions, "SAMEORIGIN")
        header(
            HttpHeaders.ContentSecurityPolicy,
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src *"
        )
    }
    routing {
        static("/app") {
            staticRootFolder = File("miniapp/dist")
            files(".")
            default("index.html")
        }
    }
}
