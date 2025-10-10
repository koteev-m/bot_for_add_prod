package com.example.bot.di

import com.example.bot.observability.DefaultHealthService
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.MigrationState
import org.koin.core.scope.Scope
import org.koin.dsl.module

val healthModule = module {
    single {
        DefaultHealthService(
            dataSourceProvider = {
                DataSourceHolder.dataSource ?: error("DataSource is not initialised")
            },
            migrationsApplied = { MigrationState.migrationsApplied },
        )
    }
}

private inline fun <reified T : Any> Scope.getOrNull(): T? =
    runCatching { get<T>() }.getOrNull()
