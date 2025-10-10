package com.example.bot.observability

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultHealthServiceTest {
    @Test
    fun `health reports database outage when data source is unavailable`() = runBlocking {
        val service =
            DefaultHealthService(
                dataSourceProvider = { throw IllegalStateException("no datasource") },
            )

        val report = service.health()

        assertEquals(CheckStatus.DOWN, report.status)
        val dbCheck = report.checks.firstOrNull { it.name == "db" }
        assertTrue(dbCheck != null && dbCheck.status == CheckStatus.DOWN)
    }
}
