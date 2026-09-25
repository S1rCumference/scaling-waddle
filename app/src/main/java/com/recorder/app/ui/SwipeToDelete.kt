package com.recorder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A row you can swipe sideways to delete, with the row behind it saying so.
 *
 * Written on foundation's drag detector and an [Animatable] rather than material3's
 * `SwipeToDismissBox`, because the dismiss API was renamed and reshaped between material3 1.2
 * and 1.3 and this module is pinned to a Compose BOM whose exact material3 version is resolved
 * at build time. Horizontal drag, offset and spring-back have been stable for years; a
 * screen-level gesture is not worth a build failure that only CI can find.
 *
 * Either direction works. Reaching for a specific direction is a thing you do while looking at
 * the screen, and half of the time this is used the phone is closed and the row is on a
 * four-inch strip.
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.toPx() }
        val threshold = SwipeRule.threshold(width)

        val travelled = abs(offset.value)
        if (travelled > 1f) {
            Box(
                Modifier.matchParentSize()
                    .background(
                        if (travelled >= threshold) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                        },
                    ),
                contentAlignment = if (offset.value < 0) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(
                    if (travelled >= threshold) "Release to delete" else "Delete",
                    color = Color.White,
                    fontSize = if (compact) 12.sp else 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        Box(
            Modifier
                // layout rather than offset { } so the row is moved without also being
                // clipped or re-measured: the text underneath has to stay put.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(offset.value.roundToInt(), 0)
                    }
                }
                .pointerInput(enabled, width) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offset.value) >= threshold) {
                                    // Off the edge first, then the delete, so the row is gone
                                    // by the time the list loses it rather than snapping back
                                    // and vanishing.
                                    val target = if (offset.value < 0) -width else width
                                    offset.animateTo(target)
                                    onDelete()
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { _, amount ->
                        scope.launch { offset.snapTo(offset.value + amount) }
                    }
                },
        ) {
            content()
        }
    }
}


/**
 * When a drag counts as "delete this". Pulled out of the composable so the one number that
 * decides whether a scroll deletes something can be tested without a screen.
 */
internal object SwipeRule {

    /** A third of the row's width: one flick, but further than a diagonal scroll travels. */
    const val FRACTION = 1f / 3f

    /**
     * A floor in pixels, so a narrow row — a list inside a split pane, or the cover screen
     * at its narrowest — does not end up with a threshold a stray touch can cross.
     */
    const val MINIMUM_PX = 96f

    fun threshold(width: Float): Float = (width * FRACTION).coerceAtLeast(MINIMUM_PX)

    /** Whether a drag of [offset] on a row [width] wide has gone far enough to delete. */
    fun past(offset: Float, width: Float): Boolean = abs(offset) >= threshold(width)
}
