package dev.pacer.wear

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Press-and-hold detection that doesn't consume the pager's horizontal swipes until pressed. */
fun Modifier.holdGesture(onPress: () -> Unit, onRelease: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        onPress()
        do {
            val e = awaitPointerEvent()
        } while (e.changes.any { it.pressed })
        onRelease()
    }
}
