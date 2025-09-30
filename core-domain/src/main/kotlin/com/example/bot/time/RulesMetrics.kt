package com.example.bot.time

import com.example.bot.telemetry.Telemetry
import io.micrometer.core.instrument.Counter

object RulesMetrics {
    private val inheritedOpen: Counter by lazy { Telemetry.registry.counter("rules.holiday.inherited_open") }
    private val inheritedClose: Counter by lazy { Telemetry.registry.counter("rules.holiday.inherited_close") }
    private val exceptionApplied: Counter by lazy { Telemetry.registry.counter("rules.exception.applied") }

    fun incHolidayInheritedOpen() = inheritedOpen.increment()

    fun incHolidayInheritedClose() = inheritedClose.increment()

    fun incExceptionApplied() = exceptionApplied.increment()
}
