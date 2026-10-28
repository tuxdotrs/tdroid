package com.tux.tdroid.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tux.tdroid.upgrade.Managed
import com.tux.tdroid.upgrade.Policy
import java.io.File

/** A coding agent CLI, installed from nixpkgs into the guest's Nix-on-Droid config; see [NixConfig]. */
data class Agent(
    val bin: String,
    val name: String,
    val description: String,
    /** Its nixpkgs attribute. */
    val attr: String,
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/**
 * Omarchy's agent list (omarchy-default-agent), limited to ones nixpkgs has for aarch64-linux; "unstable." ones come from
 * nixos-unstable, which the tdroid flake adds to the config's pkgs.
 */
val AGENTS =
    listOf(
        Agent("claude", "Claude Code", "Anthropic's coding agent", "claude-code"),
        Agent("opencode", "OpenCode", "Open source agent, any model provider", "opencode"),
        Agent("codex", "Codex", "OpenAI's coding agent", "codex"),
        Agent("copilot", "GitHub Copilot", "GitHub's coding agent", "github-copilot-cli"),
        Agent("crush", "Crush", "Charm's agent for the terminal", "crush"),
        Agent("cursor-agent", "Cursor CLI", "Cursor's agent for the terminal", "cursor-cli"),
        Agent("pi", "Pi", "Minimal, extensible coding agent", "pi-coding-agent"),
        Agent("omp", "Oh My Pi", "Pi with tools and plugins built in", "unstable.omp"),
    )

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Copilot as -i (it refuses a bare
 * argument), the others as an argument.
 * [readOnly], for reading something untrusted: Claude Code starts in plan mode, where it
 * can read but not run commands or change files whatever the user's settings say. The others have no flag
 * for that we can rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
): String {
    // A prompt starting with a dash would be read as an option (claude --settings=... runs hooks); a space in front isn't.
    val quoted = shellQuote(if (prompt.startsWith("-")) " $prompt" else prompt)
    return when {
        agentBin == "opencode" -> "$agentBin --prompt $quoted"
        agentBin == "copilot" -> "$agentBin -i $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        else -> "$agentBin $quoted"
    }
}

class AgentStore(
    private val proot: Proot,
    private val nix: NixConfig,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    val states = mutableStateMapOf<String, JobState>()

    /** In the profile, from the launcher's packages or the user's own config. */
    fun isInstalled(agent: Agent) = proot.guestFile("${Proot.GUEST_HOME}/.nix-profile/bin/${agent.bin}")?.exists() == true

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    fun choose(agent: Agent) {
        preferred = agent.bin
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    suspend fun install(agent: Agent) = change(agent, "installing", add = listOf(agent.attr))

    suspend fun remove(agent: Agent) = change(agent, "removing", remove = listOf(agent.attr))

    /** Call after the agents' packages were switched to outside [install]: makes them show up in the drawer. */
    fun onInstalled() {
        refresh()
        onChanged()
    }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it. Codex's sandbox can't run either:
     * before calling bwrap it looks up its socket directory in /proc/self/mountinfo, which is Android's
     * under proot, and fails every command ("cannot establish app-server socket mount isolation"). The
     * bwrap shim drops the sandbox anyway, so full access is what Codex already had. Seeded: Nix-on-Droid
     * has no /etc/codex, so this is the user's ~/.codex/config.toml, and theirs to change once they do.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.home, ".codex/config.toml"), Policy.Seeded) {
                    "# Written by the launcher.\nsandbox_mode = \"danger-full-access\"\n\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun change(
        agent: Agent,
        action: String,
        add: List<String> = emptyList(),
        remove: List<String> = emptyList(),
    ): Boolean {
        val ok = nix.change(add, remove, action) { if (it == JobState.Idle) states.remove(agent.bin) else states[agent.bin] = it }
        Log.i("agents", "$action ${agent.bin}: ${if (ok) "ok" else "failed"}")
        refresh()
        onChanged()
        return ok
    }
}
