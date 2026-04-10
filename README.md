# Agent CLI (IntelliJ Platform plugin)

An [IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/welcome.html) plugin that runs **AI agent CLI** sessions inside the IDE: a full **xterm.js** terminal in an embedded browser (**JCEF**), backed by a **PTY**, with a session sidebar and optional resume from local history.

Currently supported agents:

* **Claude Code** — Anthropic's CLI coding agent
* **Cursor** — Cursor's CLI agent
* **Gemini CLI** — Google's terminal-based Gemini agent

This is an independent, personally-developed project.

For a quick setup walkthrough, see [Getting Started](./GETTING_STARTED.md).

**Not Affiliated:** This plugin is not an official product of Anthropic PBC, Anysphere Inc., or Google LLC. It is not affiliated with, sponsored by, endorsed by, or in any way associated with Anthropic, Anysphere, or Google.

Trademarks: "Claude," "Claude Code," and the Claude logo are registered trademarks of Anthropic PBC. "Cursor" is a trademark of Anysphere Inc. "Gemini" and related marks are trademarks of Google LLC. These terms are used here solely for descriptive purposes to indicate compatibility and help users find relevant tools.

No Warranty: This software is provided "as is," without warranty of any kind. Use of this plugin is at your own risk. You are responsible for complying with the respective terms of service and brand guidelines of any agent CLI you use.

## Features

* **Tool window** — "Agent CLI" at the bottom of the IDE with an embedded terminal.
* **Multi-agent support** — launch sessions for Claude Code, Cursor, or Gemini CLI from the same tool window (pick the agent from the **+** menu).
* **Multiple sessions** — create, switch, and close sessions from the sidebar (`+` for new; middle-click or the close affordance to close).
* **Session history** — browse past sessions for the current project and resume by session ID when not already open. History is read from each agent's local data: Claude Code (`~/.claude/projects/…`), Cursor (`~/.cursor/projects/…/agent-transcripts/…`), and Gemini CLI (`~/.gemini`, including `projects.json` and chat JSON under `tmp/<project>/chats`).
* **Theme sync** — terminal colors follow the IDE look-and-feel / editor colors where applicable.
* **Settings** — configurable CLI commands and terminal font size (**Settings → Tools → Agent CLI**).

## Requirements

* **IDE build** — compatible range is defined in `build.gradle.kts` (`sinceBuild` / `untilBuild`; currently **261–263.\***).
* **JCEF** — the embedded terminal needs a **JetBrains Runtime (JBR) with JCEF**. If JCEF is unavailable, the tool window shows a short fallback message instead of the terminal.
* **Agent CLI(s)** — install and authenticate the agent(s) you want to use:
    * [Claude Code](https://www.anthropic.com/claude-code) (default command: `claude`)
    * [Cursor](https://www.cursor.com/) (default command: `agent`)
    * [Gemini CLI](https://github.com/google-gemini/gemini-cli) (default command: `gemini`)
* **JDK 17** — used to compile the plugin (see `build.gradle.kts`).

## Configuration

| Setting | Description |
| ------- | ----------- |
| **Claude command** | Command used to start Claude Code (default: `claude`). |
| **Cursor command** | Command used to start Cursor agent (default: `agent`). |
| **Gemini command** | Command used to start Gemini CLI (default: `gemini`). |
| **Terminal font size** | Font size for the embedded xterm (allowed range as in the settings UI). |

Persistent settings are stored in the application-level component configured in `plugin.xml`.

## Development

### Prerequisites

* JDK **17**
* Gradle (wrapper included: `./gradlew`)

### Common Gradle tasks

```bash
# Compile
./gradlew compileKotlin

# Build plugin distribution (ZIP under build/distributions/)
./gradlew buildPlugin

# Run a sandbox IDE with the plugin loaded
./gradlew runIde
```

Platform version and IDE type (e.g. IntelliJ Community vs other IDEs) are controlled via `gradle.properties` (`platformVersion`, `platformType`).

### Project layout (high level)

| Path | Role |
| ---- | ---- |
| `src/main/kotlin/.../toolwindow/` | Tool window factory, main panel, sidebar, history dialog |
| `src/main/kotlin/.../terminal/` | JCEF panel, PTY bridge, HTML shell, theme JSON |
| `src/main/kotlin/.../session/` | Session manager, history readers (Claude Code, Cursor, Gemini) |
| `src/main/kotlin/.../settings/` | Persistent settings and configurable UI |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor |
| `src/main/resources/META-INF/pluginIcon.svg` | Plugin logo (Plugins list / Marketplace) |
| `src/main/resources/icons/` | Tool window icon |
| `src/main/resources/terminal/` | Bundled xterm.js and addons |

## Troubleshooting

* **No embedded terminal / JCEF message** — use an IDE distribution that ships **JCEF** (typically recent JetBrains IDEs on supported OS/architectures).
* **CLI not found or wrong shell** — ensure `PATH` in the IDE environment includes the agent CLI binary; adjust the relevant command in **Settings → Tools → Agent CLI**.
* **History empty** — history is resolved from the agent's project folder under your home directory; paths must match how the agent encodes the project.

## Version

Plugin version is **`0.3.0`** (see `gradle.properties` and `plugin.xml`).

## License

This project is licensed under the Apache License 2.0. This license includes a specific "Limitation of Liability" and "Disclaimer of Warranty" to protect the contributors of this project. See the LICENSE file for full details.