package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JourneyPlannerNestedScrollTest {
    @Test
    fun reversingBodyDragReturnsSheetToExpandedBeforeScrollingContent() = runTest {
        var offset = 0f
        var totalDrag = 0f
        var finishes = 0
        val connection = JourneyPlannerNestedScrollConnection(
            sheetOffset = { offset },
            moveSheet = { delta -> offset = (offset + delta).coerceAtLeast(0f); totalDrag += delta },
            finishSheetDrag = { finishes++ },
        )

        assertEquals(Offset(0f, 100f), connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, -100f), connection.onPreScroll(Offset(0f, -150f), NestedScrollSource.UserInput))
        assertEquals(0f, offset)
        assertEquals(0f, totalDrag, "Reversing the gesture must cancel its accumulated collapse distance")
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -30f), NestedScrollSource.UserInput))
        connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertEquals(1, finishes)
    }

    @Test
    fun childKeepsUpwardScrollUntilSheetTakesTheGesture() {
        var moved = 0f
        val connection = JourneyPlannerNestedScrollConnection({ 0f }, { moved += it }, {})

        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(0f, -50f), NestedScrollSource.UserInput))
        assertEquals(0f, moved)
    }
}
