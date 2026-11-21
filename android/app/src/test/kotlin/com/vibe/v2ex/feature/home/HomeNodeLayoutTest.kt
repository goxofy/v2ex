package com.vibe.v2ex.feature.home

import org.junit.Assert.*
import org.junit.Test

class HomeNodeLayoutTest {
    @Test fun naturalWidthsKeepConfigurationOrder(): Unit {
        val sizes = listOf(50, 90, 70, 120, 40).map { NodeChipSize(it, 36) }
        val flow = HomeNodeLayout(sizes, 160, 3, 6, 2)
        assertEquals(sizes, flow.frames.map { it.size })
        assertEquals(listOf(0, 56, 0, 0, 126), flow.frames.map { it.x })
        assertEquals(listOf(0, 0, 38, 76, 76), flow.frames.map { it.y })
        assertEquals(112, flow.height)
    }

    @Test fun allFifteenChipsRemainInAtMostThreeRows(): Unit {
        val sizes = (0 until 15).map { NodeChipSize(50 + it * 13, 36) }
        val flow = HomeNodeLayout(sizes, 240, 3, 6, 2)
        assertTrue(flow.width > 240)
        assertEquals(sizes, flow.frames.map { it.size })
        assertTrue(flow.frames.map { it.y }.distinct().size <= 3)
        flow.frames.zipWithNext().forEach { (previous, current) ->
            assertTrue(current.y > previous.y || current.x > previous.x + previous.size.width)
        }
        assertTrue(flow.frames.all { it.x + it.size.width <= flow.width })
    }

    @Test fun longLabelIsNeverTruncated(): Unit {
        val flow = HomeNodeLayout(listOf(NodeChipSize(900, 36), NodeChipSize(45, 36)), 240, 3, 6, 2)
        assertEquals(900, flow.frames.first().size.width)
        assertTrue(flow.width >= 900)
        assertEquals(38, flow.frames.last().y)
    }

    @Test fun collapsedLayoutKeepsOneNaturalWidthRow(): Unit {
        val sizes = listOf(50, 100, 65, 200).map { NodeChipSize(it, 36) }
        val flow = HomeNodeLayout(sizes, 160, 1, 6, 2)
        assertEquals(sizes, flow.frames.map { it.size })
        assertTrue(flow.frames.all { it.y == 0 })
        assertEquals(36, flow.height)
        assertEquals(listOf(0, 56, 162, 233), flow.frames.map { it.x })
    }

    @Test fun shortAndEmptyListsDoNotReserveThreeRows(): Unit {
        assertEquals(36, HomeNodeLayout(listOf(NodeChipSize(50, 36)), 320, 3, 6, 2).height)
        assertEquals(0, HomeNodeLayout(emptyList(), 320, 3, 6, 2).height)
    }

    @Test fun variableHeightsAndInvalidMetricsAreHandled(): Unit {
        val flow = HomeNodeLayout(listOf(NodeChipSize(60, 40), NodeChipSize(60, 60), NodeChipSize(60, 36)), 126, 3, 6, 2)
        assertEquals(62, flow.frames.last().y)
        val invalid = HomeNodeLayout(listOf(NodeChipSize(-1, -1)), -1, 0, -1, -1)
        assertEquals(NodeChipSize(0, 0), invalid.frames.first().size)
        assertEquals(0, invalid.width)
    }

    @Test fun collapseAndExpandUseHysteresis(): Unit {
        assertFalse(homeNodesCollapsed(0, 120f, false))
        assertTrue(homeNodesCollapsed(0, 121f, false))
        assertTrue(homeNodesCollapsed(0, 45f, true))
        assertFalse(homeNodesCollapsed(0, 84f, false))
        assertTrue(homeNodesCollapsed(0, 50f, true))
        assertTrue(homeNodesCollapsed(0, 9f, true))
        assertFalse(homeNodesCollapsed(0, 8f, true))
        assertFalse(homeNodesCollapsed(0, -30f, true))
    }

    @Test fun laterItemsAndInvalidOffsetsDoNotExpandHeader(): Unit {
        assertTrue(homeNodesCollapsed(1, 0f, false))
        assertTrue(homeNodesCollapsed(100, 0f, true))
        assertTrue(homeNodesCollapsed(0, Float.NaN, true))
        assertFalse(homeNodesCollapsed(0, Float.POSITIVE_INFINITY, false))
    }
}
