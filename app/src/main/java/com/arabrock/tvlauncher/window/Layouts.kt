package com.arabrock.tvlauncher.window

import android.graphics.Rect

enum class LayoutType(val label: String, val slots: Int) {
    SINGLE("نافذة واحدة", 1),
    DUAL_VERTICAL("نافذتان جنباً إلى جنب", 2),
    DUAL_HORIZONTAL("نافذتان فوق بعض", 2),
    TRIPLE("ثلاث نوافذ", 3),
    QUAD("أربع نوافذ", 4),
}

/**
 * A workspace layout. splitX / splitY are the divider positions (0.2..0.8) and can be moved
 * with the remote, which gives adjustable dividers and user-defined (custom) layouts.
 */
data class WorkspaceLayout(
    val type: LayoutType = LayoutType.QUAD,
    val splitX: Float = 0.5f,
    val splitY: Float = 0.5f,
    val gapPx: Int = 6,
) {
    /** Window rectangles in screen pixels, inside [area]. LTR geometry, always. */
    fun rects(area: Rect): List<Rect> {
        val g = gapPx
        val x = area.left + (area.width() * splitX.coerceIn(0.2f, 0.8f)).toInt()
        val y = area.top + (area.height() * splitY.coerceIn(0.2f, 0.8f)).toInt()
        return when (type) {
            LayoutType.SINGLE -> listOf(Rect(area))
            LayoutType.DUAL_VERTICAL -> listOf(
                Rect(area.left, area.top, x - g, area.bottom),
                Rect(x + g, area.top, area.right, area.bottom),
            )
            LayoutType.DUAL_HORIZONTAL -> listOf(
                Rect(area.left, area.top, area.right, y - g),
                Rect(area.left, y + g, area.right, area.bottom),
            )
            LayoutType.TRIPLE -> listOf(
                Rect(area.left, area.top, x - g, area.bottom),
                Rect(x + g, area.top, area.right, y - g),
                Rect(x + g, y + g, area.right, area.bottom),
            )
            LayoutType.QUAD -> listOf(
                Rect(area.left, area.top, x - g, y - g),
                Rect(x + g, area.top, area.right, y - g),
                Rect(area.left, y + g, x - g, area.bottom),
                Rect(x + g, y + g, area.right, area.bottom),
            )
        }
    }
}
