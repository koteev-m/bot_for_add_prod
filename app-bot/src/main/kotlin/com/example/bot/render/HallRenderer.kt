package com.example.bot.render

import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.TableStatus
import com.example.bot.i18n.BotTexts
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders hall scheme with table statuses on top of a base image.
 */
class HallRenderer(
    private val baseImageProvider: (Long) -> BufferedImage,
    private val geometryProvider: TableGeometryProvider,
    private val texts: BotTexts,
) {
    /**
     * Renders hall for [clubId] using [tables] statuses and [scale].
     */
    private val strokeWidth = 2f
    private val labelFontSize = 14
    private val legendFontSize = 12
    private val legendPad = 4
    private val freeColor = Color(0x00, 0x80, 0x00)
    private val heldColor = Color(0xFF, 0xD7, 0x00)
    private val bookedColor = Color(0xB2, 0x22, 0x22)
    private val fillAlpha = 80

    fun render(clubId: Long, tables: List<TableAvailabilityDto>, scale: Int = 1): ByteArray {
        val base = baseImageProvider(clubId)
        val width = base.width * scale
        val height = base.height * scale
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.drawImage(base, 0, 0, width, height, null)
        g.stroke = BasicStroke(strokeWidth * scale)
        tables.forEach { table ->
            val rect = geometryProvider.geometry(clubId, table.tableId) ?: return@forEach
            val r =
                Rectangle2D.Double(
                    rect.x * scale,
                    rect.y * scale,
                    rect.width * scale,
                    rect.height * scale,
                )
            val color =
                when (table.status) {
                    TableStatus.FREE -> freeColor
                    TableStatus.HELD -> heldColor
                    TableStatus.BOOKED -> bookedColor
                }
            val fill = Color(color.red, color.green, color.blue, fillAlpha)
            g.color = fill
            g.fill(r)
            g.color = color
            g.draw(r)
            // label
            g.font = Font("SansSerif", Font.BOLD, labelFontSize * scale)
            val text = "#${table.tableNumber}"
            val fm = g.fontMetrics
            val tx = r.centerX - fm.stringWidth(text) / 2.0
            val ty = r.centerY + fm.ascent / 2.0
            g.color = Color.BLACK
            g.drawString(text, tx.toFloat(), ty.toFloat())
        }
        // legend block
        val legend = texts.legend(null)
        g.font = Font("SansSerif", Font.PLAIN, legendFontSize * scale)
        val fm = g.fontMetrics
        val pad = legendPad * scale
        val legendW = fm.stringWidth(legend) + pad * 2
        val legendH = fm.height + pad * 2
        val x = pad
        val y = height - legendH - pad
        g.color = Color.WHITE
        g.fillRect(x, y, legendW, legendH)
        g.color = Color.BLACK
        g.drawRect(x, y, legendW, legendH)
        g.drawString(legend, x + pad, y + fm.ascent + pad)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}

/**
 * Provides geometric information for tables.
 */
fun interface TableGeometryProvider {
    /** Returns rectangle describing table position for [clubId] and [tableId]. */
    fun geometry(clubId: Long, tableId: Long): Rectangle2D?
}
