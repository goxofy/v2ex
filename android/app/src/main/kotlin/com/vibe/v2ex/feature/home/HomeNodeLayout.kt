package com.vibe.v2ex.feature.home

internal data class NodeChipSize(val width: Int, val height: Int)
internal data class NodeChipFrame(val x: Int, val y: Int, val size: NodeChipSize)

/** Natural-width, row-major flow. Overflow widens the canvas, never truncates a chip. */
internal class HomeNodeLayout(
    sizes: List<NodeChipSize>,
    viewportWidth: Int,
    maxRows: Int,
    columnSpacing: Int,
    rowSpacing: Int,
) {
    val frames: List<NodeChipFrame>
    val width: Int
    val height: Int

    init {
        val clean = sizes.map { NodeChipSize(it.width.coerceAtLeast(0), it.height.coerceAtLeast(0)) }
        val gap = columnSpacing.coerceAtLeast(0)
        val rowGap = rowSpacing.coerceAtLeast(0)
        var low = maxOf(0, viewportWidth, clean.maxOfOrNull { it.width } ?: 0)
        var high = maxOf(low.toLong(), clean.sumOf { it.width.toLong() } + (clean.size - 1).coerceAtLeast(0).toLong() * gap)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (low < high) {
            val middle = low + (high - low) / 2
            if (rowCount(clean, middle, gap) <= maxRows.coerceAtLeast(1)) high = middle
            else low = middle + 1
        }
        width = high
        var x = 0
        var y = 0
        var rowHeight = 0
        frames = clean.map { size ->
            if (x > 0 && x.toLong() + size.width > width) {
                x = 0
                y += rowHeight + rowGap
                rowHeight = 0
            }
            val frame = NodeChipFrame(x, y, size)
            x += size.width + gap
            rowHeight = maxOf(rowHeight, size.height)
            frame
        }
        height = if (clean.isEmpty()) 0 else y + rowHeight
    }

    private fun rowCount(sizes: List<NodeChipSize>, width: Int, gap: Int): Int {
        var rows = if (sizes.isEmpty()) 0 else 1
        var x = 0L
        for (size in sizes) {
            if (x > 0 && x + size.width > width) { rows++; x = 0 }
            x += size.width.toLong() + gap
        }
        return rows
    }
}

internal fun homeNodesCollapsed(firstVisibleIndex: Int, offsetDp: Float, wasCollapsed: Boolean): Boolean {
    if (firstVisibleIndex > 0) return true
    if (!offsetDp.isFinite()) return wasCollapsed
    return offsetDp > if (wasCollapsed) 8f else 120f
}
