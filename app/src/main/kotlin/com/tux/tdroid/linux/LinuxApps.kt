package com.tux.tdroid.linux

import java.io.File

data class LinuxApp(
    val name: String,
    /** The command, without the terminal wrapper for terminal apps. */
    val exec: String,
    val terminal: Boolean,
    /** Desktop file id (foot.desktop -> "foot"); GUI apps usually use it as their Wayland app_id. */
    val id: String,
    val wmClass: String?,
    /** Icon= value: a theme icon name or an absolute guest path. */
    val icon: String? = null,
)

/** Whether a window with Wayland [appId] belongs to this app: by desktop id, StartupWMClass, or reverse-DNS tail. */
fun LinuxApp.matches(appId: String): Boolean {
    val id = appId.lowercase()
    return this.id.lowercase() == id || wmClass?.lowercase() == id || this.id.substringAfterLast('.').lowercase() == id
}

private val FIELD_CODE = Regex("%[fFuUdDnNickvm]")

/**
 * Reads launchable entries from the guest's user and system application dirs.
 * A user entry replaces the system one with the same file name (XDG), so a
 * NoDisplay=true file in ~/.local/share/applications hides a package's entry.
 */
fun loadLinuxApps(proot: Proot): List<LinuxApp> {
    // The Nix profile's entries are links to absolute guest paths in /nix/store, which the guest resolves for us.
    val system =
        proot.guestFile(PROFILE_APPLICATIONS)
            ?.listFiles { f -> f.extension == "desktop" }
            .orEmpty()
            .mapNotNull { proot.guestFile("$PROFILE_APPLICATIONS/${it.name}") }
    return (proot.applications.listFiles { f -> f.extension == "desktop" }.orEmpty().toList() + system)
        .distinctBy { it.name }
        .mapNotNull { runCatching { parseDesktopEntry(it) }.getOrNull() }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
}

private fun parseDesktopEntry(file: File): LinuxApp? {
    var inEntry = false
    val fields = mutableMapOf<String, String>()
    file.forEachLine { raw ->
        val line = raw.trim()
        if (line.startsWith("[")) {
            inEntry = line == "[Desktop Entry]"
        } else if (inEntry && '=' in line) {
            val key = line.substringBefore('=')
            fields.putIfAbsent(key, line.substringAfter('='))
        }
    }
    if (fields["Type"] != "Application") return null
    if (fields["NoDisplay"] == "true" || fields["Hidden"] == "true") return null
    val name = fields["Name"] ?: return null
    val exec = fields["Exec"]?.replace(FIELD_CODE, "")?.trim() ?: return null
    return LinuxApp(name, exec, fields["Terminal"] == "true", file.nameWithoutExtension, fields["StartupWMClass"], fields["Icon"])
}

private val ICON_SIZES = listOf("256x256", "192x192", "128x128", "96x96", "72x72", "64x64", "48x48")

/** The Nix profile's share dir, where Nix-on-Droid's packages put their desktop entries and icons. */
private const val PROFILE_SHARE = "${Proot.GUEST_HOME}/.nix-profile/share"
private const val PROFILE_APPLICATIONS = "$PROFILE_SHARE/applications"
private const val USER_SHARE = "${Proot.GUEST_HOME}/.local/share"

/**
 * Finds an icon file for [icon] in the guest: an absolute path, or a hicolor/pixmaps
 * lookup by name, largest PNG first, then SVG (many apps only ship hicolor/scalable).
 * [guestFile] is where a guest path is on Android, links followed in the guest ([Proot.guestFile]).
 */
fun resolveLinuxIcon(
    guestFile: (String) -> File?,
    icon: String,
): File? {
    if (icon.startsWith("/")) return guestFile(icon)?.takeIf { it.isFile && it.extension in ICON_TYPES }
    val candidates =
        ICON_SIZES.map { "$PROFILE_SHARE/icons/hicolor/$it/apps/$icon.png" } +
            "$PROFILE_SHARE/pixmaps/$icon.png" +
            "$USER_SHARE/icons/hicolor/256x256/apps/$icon.png" +
            "$PROFILE_SHARE/icons/hicolor/scalable/apps/$icon.svg" +
            "$PROFILE_SHARE/pixmaps/$icon.svg" +
            "$USER_SHARE/icons/hicolor/scalable/apps/$icon.svg"
    return candidates.firstNotNullOfOrNull { path -> guestFile(path)?.takeIf { it.isFile } }
}

private val ICON_TYPES = setOf("png", "svg")
