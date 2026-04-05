# Claude Code (IntelliJ Platform plugin)

An [IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/welcome.html) plugin that runs **Claude Code** CLI sessions inside the IDE: a full **xterm.js** terminal in an embedded browser (**JCEF**), backed by a **PTY**, with a session sidebar and optional resume from local history.

This is an independent, personal-developed project. **Not Affiliated:** This plugin is not an official product of Anthropic PBC. It is not affiliated with, sponsored by, endorsed by, or in any way associated with Anthropic.

Trademarks: "Claude," "Claude Code," and the Claude logo are registered trademarks of Anthropic PBC. These terms are used here solely for descriptive purposes to indicate compatibility and help users find relevant tools.

No Warranty: This software is provided "as is," without warranty of any kind. Use of this plugin is at your own risk. You are responsible for complying with [Claude / Claude Code](https://www.anthropic.com/claude-code) terms and brand guidelines.

## Features

- **Tool window** — “Claude Code” at the bottom of the IDE with an embedded terminal.
- **Multiple sessions** — create, switch, and close sessions from the sidebar (`+` for new; middle-click or the close affordance to close).
- **Session history** — browse past sessions for the current project (from `~/.claude/projects/…`) and resume by ID when not already open.
- **Theme sync** — terminal colors follow the IDE look-and-feel / editor colors where applicable.
- **Settings** — configurable CLI command and terminal font size (**Settings → Tools → Claude Code Plugin**).

## Requirements

- **IDE build** — compatible range is defined in `build.gradle.kts` (`sinceBuild` / `untilBuild`; currently **241–262.\***).
- **JCEF** — the embedded terminal needs a **JetBrains Runtime (JBR) with JCEF**. If JCEF is unavailable, the tool window shows a short fallback message instead of the terminal.
- **Claude Code CLI** — install and authenticate [`claude-code`](https://www.anthropic.com/claude-code) (or your chosen wrapper, e.g. `cc`) on your machine; the plugin launches it inside a shell according to **Settings**.
- **JDK 17** — used to compile the plugin (see `build.gradle.kts`).

## Configuration

| Setting | Description |
|--------|-------------|
| **Claude command** | Command used to start Claude Code (default e.g. `cc`). |
| **Terminal font size** | Font size for the embedded xterm (allowed range as in the settings UI). |

Persistent settings are stored in the application-level component configured in `plugin.xml`.

## Development

### Prerequisites

- JDK **17**
- Gradle (wrapper included: `./gradlew`)

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
|------|------|
| `src/main/kotlin/.../toolwindow/` | Tool window factory, main panel, sidebar, history dialog |
| `src/main/kotlin/.../terminal/` | JCEF panel, PTY bridge, HTML shell, theme JSON |
| `src/main/kotlin/.../session/` | Session manager, Claude Code history reader |
| `src/main/kotlin/.../settings/` | Persistent settings and configurable UI |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor |
| `src/main/resources/META-INF/pluginIcon.svg` | Plugin logo (Plugins list / Marketplace) |
| `src/main/resources/icons/` | Tool window icon |
| `src/main/resources/terminal/` | Bundled xterm.js and addons |

## Troubleshooting

- **No embedded terminal / JCEF message** — use an IDE distribution that ships **JCEF** (typically recent JetBrains IDEs on supported OS/architectures).
- **CLI not found or wrong shell** — ensure `PATH` in the IDE environment includes your Claude Code binary; adjust **Claude command** in settings.
- **History empty** — history is resolved from Claude’s project folder under your home directory; paths must match how Claude Code encodes the project.

## Version

Plugin version is **`0.1.0`** (see `gradle.properties` and `plugin.xml`).

## License
This project is licensed under the Apache License 2.0. This license includes a specific "Limitation of Liability" and "Disclaimer of Warranty" to protect the contributors of this project. See the LICENSE file for full details.
