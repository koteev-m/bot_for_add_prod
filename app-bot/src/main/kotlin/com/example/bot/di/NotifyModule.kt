package com.example.bot.di

import com.example.bot.routes.CampaignService
import com.example.bot.routes.TxNotifyService
import org.koin.dsl.module

val notifyModule = module {
    single { TxNotifyService() }
    single { CampaignService() }
}
