package com.tux.tlauncher.widgets

import com.tux.tlauncher.linux.shellQuote

/**
 * Which declared packages are missing, and which programs widgets run
 * that aren't installed, from one guest shell call: [script] prints a
 * `pkg <name>` or `bin <name>` line for each, and [parse] reads them back.
 */
internal object DependencyCheck {
    class Missing(
        val packages: Set<String>,
        val programs: Set<String>,
    )

    /**
     * A package counts as there when its name is a command: `requires { pacman: [...] }` names Arch
     * packages, which Nix has no record of, and most are named after their command. Names come from
     * widget files: quoted.
     */
    fun script(
        packages: Collection<String>,
        programs: Collection<String>,
    ): String =
        (if (packages.isEmpty()) "" else "for p in ${packages.joinToString(" ") { shellQuote(it) }}; do command -v \"\$p\" >/dev/null || echo \"pkg \$p\"; done; ") +
            (if (programs.isEmpty()) "" else "for c in ${programs.joinToString(" ") { shellQuote(it) }}; do command -v \"\$c\" >/dev/null || echo \"bin \$c\"; done; ") +
            "true"

    fun parse(out: String): Missing {
        val lines = out.lines().map { it.trim() }
        return Missing(
            lines.filter { it.startsWith("pkg ") }.map { it.removePrefix("pkg ") }.toSet(),
            lines.filter { it.startsWith("bin ") }.map { it.removePrefix("bin ") }.toSet(),
        )
    }
}
