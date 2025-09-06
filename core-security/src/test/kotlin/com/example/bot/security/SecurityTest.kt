package com.example.bot.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SecurityTest : StringSpec({
    "rbac check" {
        val controller =
            AccessController(
                mapOf(
                    Role.ADMIN to setOf("write"),
                    Role.USER to setOf("read"),
                ),
            )
        controller.isAllowed(Role.USER, "read") shouldBe true
        controller.isAllowed(Role.USER, "write") shouldBe false
    }

    "signature" {
        val sig = SignatureValidator.sign("k", "data")
        SignatureValidator.verify("k", "data", sig) shouldBe true
    }
})
