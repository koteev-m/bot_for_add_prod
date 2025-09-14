package com.example.bot

import com.example.bot.render.DefaultHallRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest

class DefaultHallRendererTest : StringSpec({
    "renders image" {
        val renderer = DefaultHallRenderer()
        runTest {
            val bytes = renderer.render(1L, "2025-09-20T22:00:00Z", 2.0, "v1")
            bytes.size shouldBeGreaterThan 0
            val img = ImageIO.read(bytes.inputStream())
            img.width shouldBeGreaterThan 0
        }
    }
})
