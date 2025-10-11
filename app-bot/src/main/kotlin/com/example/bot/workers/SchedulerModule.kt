package com.example.bot.workers

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

val schedulerModule =
    module {
        single { CampaignScheduler(scope = get(), api = get()) }
    }

fun Application.launchCampaignSchedulerOnStart() {
    environment.monitor.subscribe(ApplicationStarted) {
        getKoin().get<CampaignScheduler>().start()
    }
}
