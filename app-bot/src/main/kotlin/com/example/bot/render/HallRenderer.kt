package com.example.bot.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HallRenderer {
    /**
     * Вернуть PNG-байты схемы зала.
     * scale — множитель для Retina/увеличенного качества (например, 2.0).
     */
    suspend fun render(
        clubId: Long,
        startUtc: String,
        scale: Double,
        stateKey: String
    ): ByteArray
}

/**
 * Демонстрационный renderer на Java2D (headless). Не требует внешних файлов.
 * Рисует сетку, рамку, минимальные подписи.
 */
class DefaultHallRenderer : HallRenderer {

    override suspend fun render(
        clubId: Long,
        startUtc: String,
        scale: Double,
        stateKey: String
    ): ByteArray = withContext(Dispatchers.Default) {
        val baseW = 800
        val baseH = 500
        val s = if (scale.isFinite() && scale > 0.1) scale else 1.0
        val w = (baseW * s).toInt()
        val h = (baseH * s).toInt()

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // фон
            g.color = Color(15, 17, 21)
            g.fillRect(0, 0, w, h)

            // сетка
            g.color = Color(32, 38, 50)
            val step = (40 * s).toInt().coerceAtLeast(20)
            var x = 0
            while (x < w) {
                g.drawLine(x, 0, x, h)
                x += step
            }
            var y = 0
            while (y < h) {
                g.drawLine(0, y, w, y)
                y += step
            }

            // рамка зала
            g.color = Color(96, 165, 250)
            g.stroke = BasicStroke((3f * s).toFloat())
            g.drawRect((20 * s).toInt(), (20 * s).toInt(), (w - 40 * s).toInt(), (h - 40 * s).toInt())

            // подписи
            g.font = Font("SansSerif", Font.BOLD, (18 * s).toInt())
            g.color = Color(203, 213, 225)
            g.drawString("Club #$clubId  •  Night: $startUtc", (30 * s).toInt(), (40 * s).toInt())
            g.font = Font("SansSerif", Font.PLAIN, (14 * s).toInt())
            g.drawString("state: $stateKey", (30 * s).toInt(), (60 * s).toInt())
            val ver = System.getenv("HALL_BASE_IMAGE_VERSION") ?: "1"
            g.drawString("v$ver  •  scale=${"%.1f".format(s)}", (30 * s).toInt(), (80 * s).toInt())

            // легенда
            val legendY = h - (60 * s).toInt()
            drawLegend(g, (30 * s).toInt(), legendY, s)
        } finally {
            g.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        baos.toByteArray()
    }

    private fun drawLegend(g: java.awt.Graphics2D, x: Int, y: Int, s: Double) {
        val r = (8 * s).toInt()
        fun dot(cx: Int, cy: Int, color: Color) {
            g.color = color
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
        }
        var cx = x
        val cy = y
        g.font = Font("SansSerif", Font.PLAIN, (14 * s).toInt())
        dot(cx, cy, Color(34, 197, 94))
        g.color = Color(203, 213, 225)
        g.drawString("FREE", cx + (14 * s).toInt(), cy + (5 * s).toInt())
        cx += (80 * s).toInt()
        dot(cx, cy, Color(234, 179, 8))
        g.color = Color(203, 213, 225)
        g.drawString("HOLD", cx + (14 * s).toInt(), cy + (5 * s).toInt())
        cx += (80 * s).toInt()
        dot(cx, cy, Color(239, 68, 68))
        g.color = Color(203, 213, 225)
        g.drawString("BOOKED", cx + (14 * s).toInt(), cy + (5 * s).toInt())
    }
}
