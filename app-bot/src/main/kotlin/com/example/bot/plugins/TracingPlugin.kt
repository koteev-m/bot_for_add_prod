package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.micrometer.tracing.Tracer
import org.slf4j.MDC

fun Application.installTracing(tracer: Tracer) {
    intercept(ApplicationCallPipeline.Setup) {
        val span = tracer.nextSpan().name("${call.request.httpMethod.value} ${call.request.path()}").start()
        try {
            val ctx = span.context()
            MDC.put("traceId", ctx.traceId())
            MDC.put("spanId", ctx.spanId())
            proceed()
        } finally {
            span.end()
            MDC.remove("traceId")
            MDC.remove("spanId")
        }
    }
}
