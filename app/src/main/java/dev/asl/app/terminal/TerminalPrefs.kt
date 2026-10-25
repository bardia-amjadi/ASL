package dev.asl.app.terminal

import android.content.Context

/** Tiny SharedPreferences wrapper for terminal settings (font size is the only one in Phase 3). */
class TerminalPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat("font_sp", 13f)
        set(value) = prefs.edit().putFloat("font_sp", value.coerceIn(8f, 34f)).apply()
}
