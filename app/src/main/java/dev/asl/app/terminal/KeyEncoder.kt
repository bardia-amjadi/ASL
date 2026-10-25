package dev.asl.app.terminal

import android.view.KeyEvent

/** Maps Android key codes to the byte sequences a terminal application expects. */
object KeyEncoder {
    private fun modifierCode(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    fun cursorKey(letter: Char, appMode: Boolean, mod: Int): String = when {
        mod > 1 -> "\u001b[1;$mod$letter"
        appMode -> "\u001bO$letter"
        else -> "\u001b[$letter"
    }

    private fun tilde(n: Int, mod: Int): String = if (mod > 1) "\u001b[$n;$mod~" else "\u001b[$n~"

    private fun fkeyLow(letter: Char, mod: Int): String = if (mod > 1) "\u001b[1;$mod$letter" else "\u001bO$letter"

    /** Returns the sequence for non-character keys, or null if [keyCode] is not handled here. */
    fun encode(keyCode: Int, appCursor: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
        val mod = modifierCode(shift, alt, ctrl)
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3, mod)
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> cursorKey('A', appCursor, mod)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorKey('B', appCursor, mod)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKey('C', appCursor, mod)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorKey('D', appCursor, mod)
            KeyEvent.KEYCODE_MOVE_HOME -> cursorKey('H', appCursor, mod)
            KeyEvent.KEYCODE_MOVE_END -> cursorKey('F', appCursor, mod)
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5, mod)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6, mod)
            KeyEvent.KEYCODE_INSERT -> tilde(2, mod)
            KeyEvent.KEYCODE_F1 -> fkeyLow('P', mod)
            KeyEvent.KEYCODE_F2 -> fkeyLow('Q', mod)
            KeyEvent.KEYCODE_F3 -> fkeyLow('R', mod)
            KeyEvent.KEYCODE_F4 -> fkeyLow('S', mod)
            KeyEvent.KEYCODE_F5 -> tilde(15, mod)
            KeyEvent.KEYCODE_F6 -> tilde(17, mod)
            KeyEvent.KEYCODE_F7 -> tilde(18, mod)
            KeyEvent.KEYCODE_F8 -> tilde(19, mod)
            KeyEvent.KEYCODE_F9 -> tilde(20, mod)
            KeyEvent.KEYCODE_F10 -> tilde(21, mod)
            KeyEvent.KEYCODE_F11 -> tilde(23, mod)
            KeyEvent.KEYCODE_F12 -> tilde(24, mod)
            else -> null
        }
    }

    /** Control-key translation for a single typed character, or null if there is none. */
    fun ctrlChar(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c.code - 96).toChar()
        in 'A'..'Z' -> (c.code - 64).toChar()
        '@', ' ' -> 0.toChar()
        '[' -> 27.toChar()
        '\\' -> 28.toChar()
        ']' -> 29.toChar()
        '^' -> 30.toChar()
        '_' -> 31.toChar()
        '?' -> 127.toChar()
        else -> null
    }
}
