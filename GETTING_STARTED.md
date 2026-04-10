# Getting Started

This guide walks through first-time setup for the Agent CLI plugin and mirrors how the plugin actually launches commands.

## 1) Install and authenticate at least one agent CLI

Install one or more supported CLIs on your machine, then complete their login/auth flow in a normal terminal first.

- Claude Code (`claude`)
- Cursor agent CLI (`agent`)
- Gemini CLI (`gemini`)

The plugin sends your configured command into a shell session, so the command must be runnable from your shell environment.

## 2) Verify commands work in your shell

From your terminal, run the same commands you plan to configure in the plugin, for example:

- `claude`
- `agent`
- `gemini`

If your command is custom (alias, wrapper script, or full path), verify that exact command works before configuring the plugin.

## 3) Configure commands in the IDE

Open:

- `Settings` -> `Tools` -> `Agent CLI`

Set the command fields you want to use:

- `Claude command` (default: `claude`)
- `Cursor command` (default: `agent`)
- `Gemini command` (default: `gemini`)
- Optional: `Terminal font size` (8-32, default 13)

Notes:

- The plugin starts your system shell (`$SHELL` on macOS/Linux, `COMSPEC` on Windows).
- On macOS/Linux it starts a login shell (`-l`), then sends the configured command to that shell.
- Because of this, shell startup files and PATH resolution matter.

## 4) Open the tool window and start a session

Open the `Agent CLI` tool window.

- Click `+` in the left icon strip.
- Choose `Claude`, `Cursor`, or `Gemini`.
- A terminal tab opens and runs the configured command in the current project directory.

## 5) Resume a previous session (optional)

The sidebar shows history from local agent data.

- Click a history entry to resume it.
- The plugin launches your configured command with: `--resume <sessionId>`

Make sure your configured CLI supports that `--resume` flag format.

## Troubleshooting

- CLI not found: ensure the command works in the same environment your IDE uses; if needed configure a full executable path.
- JCEF unavailable: the embedded terminal requires a JetBrains runtime with JCEF support.
- Wrong command launches: re-check `Settings` -> `Tools` -> `Agent CLI` values.
