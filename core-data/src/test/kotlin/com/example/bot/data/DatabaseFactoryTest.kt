package com.example.bot.data

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull

class DatabaseFactoryTest : StringSpec({
    "connects to H2" {
        val db = DatabaseFactory.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        db.shouldNotBeNull()
    }
})
