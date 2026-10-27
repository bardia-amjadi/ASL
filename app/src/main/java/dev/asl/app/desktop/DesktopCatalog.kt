package dev.asl.app.desktop

enum class Maturity(val label: String) {
    RECOMMENDED("Recommended"),
    USUALLY_WORKS("Usually works"),
    EXPERIMENTAL("Experimental"),
}

/** A desktop environment that can be installed into an apt-based distro and shown through VNC. */
data class DesktopEnv(
    val id: String,
    val label: String,
    val maturity: Maturity,
    /** apt packages installed in addition to the VNC server basics. */
    val packages: List<String>,
    /** Command that starts the session inside the distro (run under dbus-launch). */
    val sessionCommand: List<String>,
    val xdgDesktop: String,
    /** File (relative to the distro root) that exists once the desktop is installed. */
    val marker: String,
    val approxMb: Int,
    val note: String,
)

object DesktopCatalog {
    val all: List<DesktopEnv> = listOf(
        DesktopEnv(
            "openbox", "Openbox (minimal)", Maturity.USUALLY_WORKS,
            listOf("openbox", "xterm", "tint2"), listOf("openbox-session"), "openbox", "usr/bin/openbox-session", 150,
            "Tiny window manager with a panel and a terminal. Best first test: if this does not appear, nothing bigger will.",
        ),
        DesktopEnv(
            "xfce", "Xfce", Maturity.RECOMMENDED,
            listOf("xfce4", "xfce4-terminal"), listOf("startxfce4"), "XFCE", "usr/bin/startxfce4", 500,
            "Light, complete desktop. The usual choice for desktops on PRoot.",
        ),
        DesktopEnv(
            "lxqt", "LXQt", Maturity.USUALLY_WORKS,
            listOf("lxqt-core", "qterminal"), listOf("startlxqt"), "LXQt", "usr/bin/startlxqt", 350,
            "Light Qt desktop.",
        ),
        DesktopEnv(
            "mate", "MATE", Maturity.USUALLY_WORKS,
            listOf("mate-desktop-environment-core", "mate-terminal"), listOf("mate-session"), "MATE", "usr/bin/mate-session", 600,
            "Classic GNOME 2-style desktop.",
        ),
        DesktopEnv(
            "gnome-flashback", "GNOME Flashback", Maturity.EXPERIMENTAL,
            listOf("gnome-session-flashback", "gnome-terminal", "nautilus"),
            listOf("gnome-session", "--session=gnome-flashback-metacity"), "GNOME-Flashback:GNOME", "usr/bin/gnome-session", 900,
            "The classic GNOME panel with the Metacity window manager. The realistic way to get GNOME here: it needs no GPU compositing.",
        ),
        DesktopEnv(
            "gnome", "GNOME (full)", Maturity.EXPERIMENTAL,
            listOf("gnome-core"), listOf("gnome-session"), "GNOME", "usr/bin/gnome-shell", 1500,
            "Full GNOME Shell expects systemd-logind and GPU compositing, which PRoot does not provide. It is likely to fail to start. " +
                "Installed only if you insist; try GNOME Flashback first.",
        ),
    )

    fun byId(id: String): DesktopEnv? = all.firstOrNull { it.id == id }
}
