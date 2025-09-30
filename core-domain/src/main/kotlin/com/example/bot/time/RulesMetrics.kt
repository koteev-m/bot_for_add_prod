package com.example.bot.time

import com.example.bot.telemetry.Telemetry

object RulesMetrics {
    private val registry get() = Telemetry.registry

    private fun boolTag(value: Boolean): String = value.toString()

    fun incHolidayInheritedOpen(
        dow: Int,
        overnight: Boolean,
    ) {
        registry
            .counter(
                "rules.holiday.inherited_open",
                "dow",
                dow.toString(),
                "overnight",
                boolTag(overnight),
            ).increment()
    }

    fun incHolidayInheritedClose(
        dow: Int,
        overnight: Boolean,
    ) {
        registry
            .counter(
                "rules.holiday.inherited_close",
                "dow",
                dow.toString(),
                "overnight",
                boolTag(overnight),
            ).increment()
    }

    fun incExceptionApplied(
        dow: Int,
        holidayApplied: Boolean,
        overnight: Boolean,
    ) {
        registry
            .counter(
                "rules.exception.applied",
                "dow",
                dow.toString(),
                "holiday",
                boolTag(holidayApplied),
                "overnight",
                boolTag(overnight),
            ).increment()
    }

    fun incDayOpen(
        dow: Int,
        exceptionApplied: Boolean,
        holidayApplied: Boolean,
        overnight: Boolean,
    ) {
        registry
            .counter(
                "rules.day.open",
                "dow",
                dow.toString(),
                "exception",
                boolTag(exceptionApplied),
                "holiday",
                boolTag(holidayApplied),
                "overnight",
                boolTag(overnight),
            ).increment()
    }
}
