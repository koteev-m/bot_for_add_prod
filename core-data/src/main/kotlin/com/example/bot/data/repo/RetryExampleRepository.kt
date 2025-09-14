package com.example.bot.data.repo

import com.example.bot.data.db.txRetrying

/**
 * Пример применения txRetrying в репозитории.
 * Здесь мы не привязываемся к конкретным таблицам, а демонстрируем шаблон.
 */
class RetryExampleRepository {

    suspend fun doSomethingWithRetry(): Int = txRetrying {
        // Здесь могла быть ваша Exposed-логика; ниже — заглушка для примера:
        42
    }
}

