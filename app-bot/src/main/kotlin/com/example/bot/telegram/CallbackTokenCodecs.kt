package com.example.bot.telegram

import java.time.DateTimeException
import java.time.Instant

private const val TELEGRAM_CALLBACK_LIMIT = 64
private const val CLUB_CALLBACK_PREFIX = "club:"
private const val CLUB_TOKEN_PREFIX = "c"
private const val NIGHT_CALLBACK_PREFIX = "night:"
private const val NIGHT_TOKEN_PREFIX = "n"
private const val NIGHT_TOKEN_SEPARATOR = '-'
private const val NIGHT_NANOS_SEPARATOR = '~'
private const val BASE36_RADIX = 36
private const val CLUB_TOKEN_MAX_LENGTH = TELEGRAM_CALLBACK_LIMIT - CLUB_CALLBACK_PREFIX.length
private const val NIGHT_TOKEN_MAX_LENGTH = TELEGRAM_CALLBACK_LIMIT - NIGHT_CALLBACK_PREFIX.length

/**
 * Encodes/decodes club callback tokens using a base36 payload with a short prefix.
 */
object ClubTokenCodec {
    fun encode(clubId: Long): String {
        require(clubId >= 0) { "club id must be non-negative" }
        val payload = clubId.toString(BASE36_RADIX)
        val token = CLUB_TOKEN_PREFIX + payload
        require(token.length <= CLUB_TOKEN_MAX_LENGTH) { "club token exceeds Telegram callback limit" }
        return token
    }

    fun decode(token: String): Long? {
        if (token.isBlank() || token.length > CLUB_TOKEN_MAX_LENGTH) return null
        if (!token.startsWith(CLUB_TOKEN_PREFIX)) return null
        val payload = token.substring(CLUB_TOKEN_PREFIX.length)
        if (payload.isEmpty()) return null
        val clubId =
            try {
                payload.toLong(BASE36_RADIX)
            } catch (_: NumberFormatException) {
                return null
            }
        return if (clubId >= 0) clubId else null
    }
}

/**
 * Encodes/decodes night callback tokens using base36 payloads with a short prefix.
 */
object NightTokenCodec {
    fun encode(clubId: Long, startUtc: Instant): String {
        require(clubId >= 0) { "club id must be non-negative" }
        val clubPayload = clubId.toString(BASE36_RADIX)
        val seconds = startUtc.epochSecond
        val secondsPayload = seconds.toString(BASE36_RADIX)
        val nanos = startUtc.nano
        val token = buildString {
            append(NIGHT_TOKEN_PREFIX)
            append(clubPayload)
            append(NIGHT_TOKEN_SEPARATOR)
            append(secondsPayload)
            if (nanos != 0) {
                append(NIGHT_NANOS_SEPARATOR)
                append(nanos.toString(BASE36_RADIX))
            }
        }
        require(token.length <= NIGHT_TOKEN_MAX_LENGTH) { "night token exceeds Telegram callback limit" }
        return token
    }

    fun decode(token: String): Pair<Long, Instant>? {
        if (token.isBlank() || token.length > NIGHT_TOKEN_MAX_LENGTH) return null
        if (!token.startsWith(NIGHT_TOKEN_PREFIX)) return null
        val payload = token.substring(NIGHT_TOKEN_PREFIX.length)
        val separatorIndex = payload.indexOf(NIGHT_TOKEN_SEPARATOR)
        if (separatorIndex <= 0) return null
        val clubPart = payload.substring(0, separatorIndex)
        val timePart = payload.substring(separatorIndex + 1)
        if (timePart.isEmpty()) return null
        val nanosSeparatorIndex = timePart.indexOf(NIGHT_NANOS_SEPARATOR)
        val secondsPart: String
        val nanosPart: String?
        if (nanosSeparatorIndex >= 0) {
            secondsPart = timePart.substring(0, nanosSeparatorIndex)
            nanosPart = timePart.substring(nanosSeparatorIndex + 1)
            if (secondsPart.isEmpty() || nanosPart.isEmpty()) return null
        } else {
            secondsPart = timePart
            nanosPart = null
        }
        val clubId = try {
            clubPart.toLong(BASE36_RADIX)
        } catch (_: NumberFormatException) {
            return null
        }
        val seconds =
            try {
                secondsPart.toLong(BASE36_RADIX)
            } catch (_: NumberFormatException) {
                return null
            }
        val nanos = if (nanosPart != null) {
            val parsed = try {
                nanosPart.toInt(BASE36_RADIX)
            } catch (_: NumberFormatException) {
                return null
            }
            if (parsed !in 0..999_999_999) return null
            parsed
        } else {
            0
        }
        return try {
            if (clubId < 0) {
                null
            } else {
                clubId to Instant.ofEpochSecond(seconds, nanos.toLong())
            }
        } catch (_: DateTimeException) {
            null
        }
    }
}
