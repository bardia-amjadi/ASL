package dev.asl.app.terminal

/** Terminal colour scheme. [palette] holds the 16 ANSI colours (0-7 normal, 8-15 bright) as 0xFFRRGGBB. */
class TerminalTheme(
    val id: String,
    val label: String,
    val fg: Int,
    val bg: Int,
    val cursor: Int,
    val selection: Int,
    val palette: IntArray,
) {
    /** True for the scheme the emulator already bakes into cells (no remapping needed). */
    val isBase: Boolean get() = id == "campbell"
}

object TerminalThemes {
    private fun p(vararg rgb: Int) = IntArray(rgb.size) { 0xFF000000.toInt() or rgb[it] }

    val Campbell = TerminalTheme(
        "campbell", "Campbell", 0xFFCCCCCC.toInt(), 0xFF0C0C0C.toInt(), 0xB3F2F2F2.toInt(), 0x663B78FF,
        TerminalEmulator.PALETTE.copyOf(16),
    )

    val all: List<TerminalTheme> = listOf(
        Campbell,
        TerminalTheme(
            "onedark", "One Dark", 0xFFABB2BF.toInt(), 0xFF282C34.toInt(), 0xB3528BFF.toInt(), 0x663E4451,
            p(0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
                0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF),
        ),
        TerminalTheme(
            "solarized", "Solarized Dark", 0xFF839496.toInt(), 0xFF002B36.toInt(), 0xB393A1A1.toInt(), 0x66586E75,
            p(0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3),
        ),
        TerminalTheme(
            "dracula", "Dracula", 0xFFF8F8F2.toInt(), 0xFF282A36.toInt(), 0xB3F8F8F2.toInt(), 0x6644475A,
            p(0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
                0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF),
        ),
    )

    fun byId(id: String): TerminalTheme = all.firstOrNull { it.id == id } ?: Campbell
}
