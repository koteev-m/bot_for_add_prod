package com.example.bot

import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.TableStatus
import com.example.bot.i18n.BotTexts
import com.example.bot.render.HallRenderer
import com.example.bot.render.TableGeometryProvider
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class HallRendererTest : StringSpec({
    val base = BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 100, 60); dispose() }
    }
    val geometry = TableGeometryProvider { _, id ->
        when (id) {
            1L -> Rectangle2D.Double(0.0, 0.0, 30.0, 30.0)
            2L -> Rectangle2D.Double(35.0, 0.0, 30.0, 30.0)
            3L -> Rectangle2D.Double(70.0, 0.0, 30.0, 30.0)
            else -> null
        }
    }
    val renderer = HallRenderer({ base }, geometry, BotTexts())

    "renders colored tables" {
        val tables = listOf(
            TableAvailabilityDto(1, "1", "A", 4, 100, TableStatus.FREE),
            TableAvailabilityDto(2, "2", "A", 4, 100, TableStatus.HELD),
            TableAvailabilityDto(3, "3", "A", 4, 100, TableStatus.BOOKED),
        )
        val bytes = renderer.render(1, tables)
        bytes.size shouldBeGreaterThan 0
        val img = ImageIO.read(bytes.inputStream())
        val c1 = Color(img.getRGB(15, 15), true)
        (c1.green > c1.red && c1.green > c1.blue) shouldBe true
        val c2 = Color(img.getRGB(50, 15), true)
        (c2.red > 200 && c2.green > 200) shouldBe true
        val c3 = Color(img.getRGB(85, 15), true)
        (c3.red > c3.green && c3.red > c3.blue) shouldBe true
    }
})
