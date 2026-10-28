package com.tux.tdroid

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The launcher's own data that Linux also sees. It lives outside the rootfs,
 * so it exists without Linux and survives installing or removing it. Home's
 * layout, the widgets and the style are in the Linux home's ~/.config/tdroid,
 * which removing Linux leaves ([clearHome]); Omarchy's are bound to the paths
 * it expects (see [com.tux.tdroid.linux.Proot]).
 */
object LauncherData {
    /** Omarchy's release: themes, wallpapers, templates. The guest sees ~/.local/share/omarchy. */
    fun omarchyDir(context: Context) = File(context.filesDir, "omarchy")

    /** The user's own Omarchy themes. The guest sees ~/.config/omarchy/themes, where omarchy-theme-install puts them. */
    fun userThemesDir(context: Context) = File(context.filesDir, "themes")

    /** The user's extra backgrounds, per theme. The guest sees ~/.config/omarchy/backgrounds. */
    fun userBackgroundsDir(context: Context) = File(context.filesDir, "backgrounds")

    /** The Linux user's home, ~ to the guest, at the same path. */
    fun userHome(context: Context) = File(context.filesDir, "home")

    /** The launcher's own files in it: ~/.config/tdroid. */
    fun configDir(context: Context) = File(userHome(context), CONFIG)

    /** Widget files: ~/.config/tdroid/widgets. */
    fun widgetsDir(context: Context) = File(configDir(context), "widgets")

    /** style.toml and its defaults, ~/.config/tdroid/style; see [com.tux.tdroid.theme.StyleStore]. */
    fun styleDir(context: Context) = File(configDir(context), "style")

    /** layout.toml, where home's tiles and pages are, ~/.config/tdroid/home; see [com.tux.tdroid.widgets.HomeStore]. */
    fun homeDir(context: Context) = File(configDir(context), "home")

    /**
     * Moves what an earlier version kept beside the Linux home into ~/.config/tdroid: files/widgets and files/style, and
     * layout.toml with its errors.txt from files/home itself, where they mixed with the user's own files. Run before
     * anything reads them; one already moved stays where it is.
     */
    fun moveIntoConfig(context: Context) {
        val files = context.filesDir
        // An earlier version left empty mount points for its binds at the new places, and may have written defaults there.
        for (name in listOf("widgets", "style")) moveOver(File(files, name), File(configDir(context), name))
        // The launcher's provisioned files are recorded by their path from filesDir: moved, the records follow, or the
        // defaults would read as the user's and deleted ones would come back.
        val manifest = File(files, LAUNCHER_MANIFEST)
        com.tux.tdroid.upgrade.Versioned.readFile(manifest, 1)?.let { json ->
            val keys = json.keys().asSequence().toList()
            if (keys.any { it.startsWith("widgets/") || it.startsWith("style/") }) {
                val moved = org.json.JSONObject()
                for (key in keys) {
                    val to = if (key.startsWith("widgets/") || key.startsWith("style/")) "home/$CONFIG/$key" else key
                    moved.put(to, json.getString(key))
                }
                com.tux.tdroid.upgrade.Versioned.writeFile(manifest, 1, moved)
            }
        }
        for (name in listOf("layout.toml", "errors.txt")) moveOver(File(userHome(context), name), File(homeDir(context), name))
    }

    /**
     * Moves [old] to [new], into it where both are directories. A file in both places is the old one's, the user's: what's
     * at [new] was at most written by the launcher since.
     */
    private fun moveOver(
        old: File,
        new: File,
    ) {
        if (!old.exists()) return
        if (old.isDirectory && new.isDirectory) {
            for (child in old.listFiles().orEmpty()) moveOver(child, File(new, child.name))
            if (!old.delete()) android.util.Log.e("launcher-data", "$old left behind")
            return
        }
        if (new.exists()) deleteTree(new)
        new.parentFile?.mkdirs()
        if (!old.renameTo(new)) android.util.Log.e("launcher-data", "can't move $old to $new")
    }

    /** Empties the Linux [home] but for the launcher's own ~/.config/tdroid, which isn't Linux's to take. */
    fun clearHome(home: File) {
        for (entry in home.listFiles().orEmpty()) {
            if (entry.name == ".config" && entry.isDirectory && !Files.isSymbolicLink(entry.toPath())) {
                for (sub in entry.listFiles().orEmpty()) if (sub.name != CONFIG.substringAfter('/')) deleteTree(sub)
            } else {
                deleteTree(entry)
            }
        }
    }

    private const val CONFIG = ".config/tdroid"

    /** Settings that belong to no one store: setup, gestures, the GPU, the home role. Keys carry their owner's prefix. */
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Manifest of the launcher-side [com.tux.tdroid.upgrade.Provisioner]; the guest's is managed.json. */
    const val LAUNCHER_MANIFEST = "launcher-managed.json"
    const val GUEST_MANIFEST = "managed.json"

    /**
     * Deletes [dir] and everything in it without following symlinks: the
     * rootfs holds absolute guest links that resolve elsewhere on the host.
     * Directories get write permission first, since the rootfs has read-only
     * ones (the tarball's modes). Throws if anything is left.
     */
    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    runCatching { Os.chmod(d.toString(), Os.lstat(d.toString()).st_mode and 0x1ff or 0x1c0) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                /** The walker lists a directory before [preVisitDirectory]: an unreadable one (mode 000) fails here first. */
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    val path = file.toString()
                    val mode = runCatching { Os.lstat(path).st_mode }.getOrNull() ?: throw exc
                    if (!OsConstants.S_ISDIR(mode) || mode and 0x1c0 == 0x1c0) throw exc
                    Os.chmod(path, mode and 0x1ff or 0x1c0)
                    deleteTree(file.toFile())
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    d: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
