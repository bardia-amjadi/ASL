package dev.asl.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sticky Ctrl/Alt toggles from the on-screen key row; consumed by the next key press. */
class InputModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun clear() {
        ctrl = false
        alt = false
    }
}
