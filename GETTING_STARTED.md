# Getting Started

This guide walks through first-time setup for the Agent CLI plugin and mirrors how the plugin actually launches commands.

## 1) Install and authenticate at least one agent CLI

Install one or more supported CLIs on your machine, then complete their login/auth flow in a normal terminal first.

* Claude Code (`claude`)
* Cursor agent CLI (`agent`)
* Gemini CLI (`gemini`)
* OpenAI Codex CLI (`codex`)

You can also run any of these through a wrapper command via the **Sandbox** agent type — see [step 7](#7-configure-the-sandbox-agent-optional).

The plugin sends your configured command into a shell session, so the command must be runnable from your shell environment.

## 2) Verify commands work in your shell

From your terminal, run the same commands you plan to configure in the plugin, for example:

* `claude`
* `agent`
* `gemini`
* `codex`

If your command is custom (alias, wrapper script, or full path), verify that exact command works before configuring the plugin.

## 3) Configure commands in the IDE

Open:

* `Settings` -> `Tools` -> `Agent CLI`

Set the command fields you want to use:

* `Claude command` (default: `claude`)
* `Cursor command` (default: `agent`)
* `Gemini command` (default: `gemini`)
* `Codex command` (default: `codex`)
* Each agent has an `Enable` checkbox — disabled agents are hidden from the `+` menu.
* Optional: `Terminal font size` (8-32, default 13)
* Optional: `Enable flow control` — throttle fast PTY output to prevent terminal buffer overflows (restart session to apply).

Notes:

* The plugin starts your system shell (`$SHELL` on macOS/Linux, `COMSPEC` on Windows).
* On macOS/Linux it starts a login shell (`-l`), then sends the configured command to that shell.
* Because of this, shell startup files and PATH resolution matter.

## 4) Open the tool window and start a session

Open the `Agent CLI` tool window.

* Click `+` in the left icon strip.
* Choose `Claude`, `Cursor`, `Gemini`, `Codex`, or `Sandbox`.
* A terminal tab opens and runs the configured command in the current project directory.

## 5) Resume a previous session (optional)

The sidebar shows history from local agent data.

* Click a history entry to resume it.
* The plugin launches your configured command with: `--resume <sessionId>`

Make sure your configured CLI supports that `--resume` flag format.

## 6) Enable attention notifications (optional)

When an agent is waiting on you (permission prompt, idle confirmation, Codex permission request), the plugin can mark the session row with a red dot and show an IDE balloon + OS banner.

This is opt-in because it writes hook entries into your per-agent config files.

1. Open `Settings` -> `Tools` -> `Agent CLI` -> `Attention Notifications`.
2. (Optional) Click `Preview…` to see exactly what will be merged into each file, one tab per agent, with JSON syntax highlighting.
3. Click `Install Hooks`. The plugin will:
    * Drop a notify script into `~/.agent-cli-plugin/notify.sh` (or `notify.ps1` on Windows).
    * Merge a sentinel-tagged hook entry into `~/.claude/settings.json`, `~/.gemini/settings.json`, and `~/.codex/hooks.json`.
    * Save a `.bak` sibling next to any file it modifies.
4. Start a new agent session from the tool window. The hook only posts to the IDE when the session is launched from the plugin — running the same CLI in a plain terminal is a no-op.
5. Click `Uninstall` any time to remove the hook entries, restore from `.bak` if you prefer, and delete the notify script.

For the OS banner to actually appear:

* macOS: System Settings -> Notifications -> your IDE -> set style to `Banners` or `Alerts` and allow while the app is in the background. Sound is controlled by the per-app "Play sound for notifications" toggle in that same pane.
* Windows / Linux: the plugin uses the IDE's `SystemNotifications` bridge, which routes to Windows toasts and `libnotify` respectively. No extra setup unless your OS blocks notifications for the IDE.

## 7) Configure the Sandbox agent (optional)

The **Sandbox** agent type runs an agent CLI through a generic wrapper command — for example a containerized runner that isolates the agent to the mounted project directory.

Open `Settings` -> `Tools` -> `Agent CLI` -> `Sandbox` and set:

* `Command` — the wrapper command. Use `{dir}` where the project path should go (inserted quoted); if omitted, the path is appended. Example: `claude-crate --workdir {dir}`
* `History dir` — base directory where the sandbox stores history (e.g. `~/.claude-crate`).
* `Runs as` — the agent running inside the sandbox (Claude / Cursor / Gemini / Codex). This selects the history parser and resume syntax.
* `Enable` — toggles the Sandbox entry in the `+` menu.

Then start a session from the `+` menu just like any other agent. New sessions run `<command>` with `{dir}` substituted; resuming runs the same with the underlying agent's resume flag appended (e.g. `claude-crate --workdir "<project>" --resume <sessionId>`), so the wrapper must forward trailing arguments to the underlying CLI.

Sandbox sessions show a green gradient bar at the top of the terminal so you can tell them apart at a glance.

A concrete example wrapper is [claude-crate](https://github.com/sajithdilshan/claude-crate), which runs Claude Code in a Docker container. With it installed, use `claude-crate --workdir {dir}`, history dir `~/.claude-crate`, and `Runs as` = `Claude`.

## Troubleshooting

* CLI not found: ensure the command works in the same environment your IDE uses; if needed configure a full executable path.
* JCEF unavailable: the embedded terminal requires a JetBrains runtime with JCEF support.
* Wrong command launches: re-check `Settings` -> `Tools` -> `Agent CLI` values.