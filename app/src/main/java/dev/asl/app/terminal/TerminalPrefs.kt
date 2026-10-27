package dev.asl.app.terminal

import android.content.Context

/** Tiny SharedPreferences wrapper for terminal settings (font size is the only one in Phase 3). */
class TerminalPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat("font_sp", 13f)
        set(value) = prefs.edit().putFloat("font_sp", value.coerceIn(8f, 34f)).apply()

    /** Applies to sessions opened after the change. */
    var scrollbackLines: Int
        get() = prefs.getInt("scrollback", 3000)
        set(value) = prefs.edit().putInt("scrollback", value.coerceIn(500, 20000)).apply()

    var themeId: String
        get() = prefs.getString("theme", "campbell") ?: "campbell"
        set(value) = prefs.edit().putString("theme", value).apply()

    /** 0 = follow the program, 1 = block, 2 = underline, 3 = bar. */
    var cursorStyle: Int
        get() = prefs.getInt("cursor_style", 0)
        set(value) = prefs.edit().putInt("cursor_style", value.coerceIn(0, 3)).apply()
}
