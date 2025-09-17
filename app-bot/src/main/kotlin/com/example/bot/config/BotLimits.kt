package com.example.bot.config

import java.time.Duration

object BotLimits {
    // Idempotency
    val notifyIdempotencyTtl: Duration = Duration.ofHours(24)
    val notifyIdempotencyCleanupSize: Int = 50_000

    // One-Time Tokens (OTT)
    val ottTokenTtl: Duration = Duration.ofSeconds(300)
    val ottTokenMinTtl: Duration = Duration.ofSeconds(30)
    val ottMaxEntries: Int = 100_000
    val ottMinEntries: Int = 1
    val ottCleanupAbsoluteThreshold: Int = 10_000
    val ottTokenBaseBytes: Int = 20
    val ottTokenExtraBytesRange: IntRange = 0..4
    val ottTokenMaxBase64Length: Int = 64

    // Notify sender / backoff
    val notifySendBaseBackoff: Duration = Duration.ofMillis(500)
    val notifySendMaxBackoff: Duration = Duration.ofMillis(15_000)
    val notifySendJitter: Duration = Duration.ofMillis(100)
    val notifySendMaxAttempts: Int = 3
    val notifyRetryAfterFallback: Duration = Duration.ofSeconds(1)
    val notifyBackoffMaxShift: Int = 20
    val notifyDurationPercentiles: DoubleArray = doubleArrayOf(0.5, 0.95)
}
