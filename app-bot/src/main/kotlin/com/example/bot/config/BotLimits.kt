package com.example.bot.config

import java.time.Duration

object BotLimits {
    // Idempotency
    val notifyIdempotencyTtl: Duration = Duration.ofHours(24)
    const val notifyIdempotencyCleanupSize: Int = 50_000

    // One-Time Tokens (OTT)
    val ottTokenTtl: Duration = Duration.ofSeconds(300)
    val ottTokenMinTtl: Duration = Duration.ofSeconds(30)
    const val ottMaxEntries: Int = 100_000
    const val ottMinEntries: Int = 1
    const val ottCleanupAbsoluteThreshold: Int = 10_000
    const val ottTokenBaseBytes: Int = 20
    val ottTokenExtraBytesRange: IntRange = 0..4
    const val ottTokenMaxBase64Length: Int = 64

    // Notify sender / backoff
    val notifySendBaseBackoff: Duration = Duration.ofMillis(500)
    val notifySendMaxBackoff: Duration = Duration.ofMillis(15_000)
    val notifySendJitter: Duration = Duration.ofMillis(100)
    const val notifySendMaxAttempts: Int = 3
    val notifyRetryAfterFallback: Duration = Duration.ofSeconds(1)
    const val notifyBackoffMaxShift: Int = 20
    val notifyDurationPercentiles: DoubleArray = doubleArrayOf(0.5, 0.95)
}
