# CLAUDE.md

## Project Overview

**Agent CLI** is a JetBrains IntelliJ Platform plugin that runs AI agent CLI sessions (Claude Code, Cursor, OpenAI Codex) inside the IDE with a fully embedded xterm.js terminal backed by a PTY process.

* Plugin ID: `org.sajith.agentcli.plugin`
* Version: `1.0.1` (defined in `gradle.properties`)
* Author: Sajith Edirisinghe
* License: Apache 2.0

## Tech Stack

* **Language:** Kotlin (JVM 17)
* **Build:** Gradle with Kotlin DSL (`build.gradle.kts`)
* **Platform:** IntelliJ Platform SDK (2026.1, builds 261–263.\*)
* **Terminal:** xterm.js rendered in JCEF (JBCefBrowser)
* **PTY:** pty4j library
* **Linting:** ktlint via `org.jlleitschuh.gradle.ktlint` plugin
* **Testing:** JUnit 5

## Build & Run

```bash
./gradlew compileKotlin          # Compile
./gradlew buildPlugin            # Build ZIP distribution (includes tests)
./gradlew runIde                 # Launch sandbox IDE with plugin
./gradlew test                   # Run unit tests
./gradlew ktlintCheck            # Check code style
./gradlew ktlintFormat           # Auto-fix code style
```

The `buildPlugin` task depends on `test`, so tests always run before packaging.

## Project Structure

```
src/main/kotlin/org/sajith/agentcli/plugin/
├── AgentType.kt                  # Enum: CLAUDE, CURSOR, CODEX
├── settings/                     # Persistent settings (PersistentStateComponent)
│   ├── AgentCliSettings.kt       # State: per-agent enable/command, font size, flow control
│   └── AgentCliSettingsConfigurable.kt  # Settings UI panel
├── terminal/                     # Embedded terminal rendering
│   ├── CefTerminalPanel.kt       # JCEF browser hosting xterm.js, JS↔JVM bridges
│   ├── CefTerminalPageHtml.kt    # HTML/JS template builder for the terminal page
│   ├── PtyBridge.kt              # PTY process lifecycle, reader thread, pause/resume
│   ├── TerminalFlowController.kt # Watermark-based backpressure (high/low watermark + ack)
│   ├── TerminalThemeProvider.kt  # IDE theme → xterm.js theme JSON
│   └── EmbeddedAgentTerminal.kt  # Composition root: wires CefPanel + FlowController + PtyBridge
├── session/                      # Session lifecycle & history
│   ├── AgentCliSession.kt        # Session data class
│   ├── SessionManager.kt         # Project-level service, creates/removes/finds sessions
│   ├── HistoricalSession.kt      # Data class for past sessions
│   ├── SessionPathResolver.kt    # Encodes/resolves agent project paths
│   ├── ClaudeCodeHistoryReader.kt # Reads ~/.claude/projects JSONL files
│   ├── CursorHistoryReader.kt    # Reads Cursor agent transcripts
│   ├── CodexHistoryReader.kt     # Reads Codex session data
│   ├── HistoryReaderUtils.kt     # Shared parallel file parsing utilities
│   └── SessionHistoryDeleter.kt  # Deletes session history from disk
├── toolwindow/                   # Tool window UI
│   ├── AgentCliToolWindowFactory.kt  # Registers the "Agent CLI" tool window
│   ├── AgentCliPanel.kt          # Main panel (session content + sidebar)
│   └── SessionSidebarPanel.kt    # Collapsible sidebar with active sessions & history
├── editor/                       # Editor-tab hosted sessions
│   ├── AgentCliEditorBridge.kt   # Routes open-in-editor / return-to-plugin requests
│   ├── AgentCliSessionVirtualFile.kt  # Virtual file for editor tabs
│   ├── AgentCliSessionFileEditor.kt   # FileEditor implementation
│   ├── AgentCliSessionFileEditorProvider.kt  # Registers the file editor
│   └── AgentCliSessionFileIconProvider.kt    # Custom icon for editor tabs
└── notify/                       # Attention notification system
    ├── AgentCliNotifyHandler.kt  # HTTP endpoint (built-in server) receiving hook callbacks
    ├── SessionAttentionService.kt # Fires IDE balloons + OS banners
    └── HookInstaller.kt          # Installs/uninstalls hook scripts into agent configs

src/main/resources/
├── META-INF/plugin.xml           # Plugin descriptor (extensions, services, actions)
├── terminal/                     # Bundled xterm.js, addons, CSS
├── notify/                       # Shell/PowerShell notification scripts
├── fonts/                        # Inconsolata Nerd Font Mono (embedded in terminal HTML)
└── icons/                        # Tool window SVG icon

src/test/kotlin/                  # Unit tests (JUnit 5)
```

## Architecture Notes

### Terminal Rendering

The terminal uses JCEF (Chromium Embedded Framework) to host an xterm.js instance. Communication between JVM and JS is via `JBCefJSQuery` bridges:

* **Input:** JS → JVM (base64-encoded keystrokes)
* **Resize:** JS → JVM (cols/rows JSON)
* **Output:** JVM → JS (base64-encoded PTY data via `executeJavaScript`)
* **Ack:** JS → JVM (flow control acknowledgment)
* **Links:** JS → JVM (URL opened in system browser)

### Flow Control

`TerminalFlowController` implements watermark-based backpressure:

* Every N bytes (`callbackByteLimit`, default 200KB), a write is flagged for ack
* When pending un-acked callbacks exceed `highWatermark`, PTY reader is paused
* When pending drops below `lowWatermark`, PTY reader resumes
* Disabled by default; toggled via settings

### Session Management

* `SessionManager` is a project-level service tracking active sessions
* Communication between components uses IntelliJ `messageBus` topics:
    * `SESSION_LIFECYCLE_TOPIC` — session added/removed
    * `SESSION_ATTENTION_TOPIC` — attention state changed
    * `RESUME_IN_PLUGIN_TOPIC` — editor→plugin view transition
    * `SETTINGS_CHANGED_TOPIC` — settings updates

### Attention Notifications

* A bundled shell script is installed into agent config files (Claude, Codex)
* The script POSTs to the IDE's built-in HTTP server at `/agent-cli-plugin/notify`
* `AgentCliNotifyHandler` receives the POST and triggers IDE balloon + OS notification
* Hook entries carry a sentinel tag for idempotent install/uninstall

### Editor-Hosted Sessions

Sessions can live in either the tool window or a regular editor tab. Transitions are clean close+resume cycles coordinated through `AgentCliEditorBridge`.

## Key Conventions

* All log messages are prefixed with `[AgentCLI]`
* IntelliJ services use `@Service` annotations (APP level for settings, PROJECT level for session manager)
* Disposable hierarchy: parent disposable → terminal panel → PTY bridge
* Thread safety: PTY reader is a daemon thread; flow controller uses atomics; JCEF callbacks arrive on the CEF thread
* Resources (xterm.js, fonts) are loaded lazily once and cached in a companion object

## Configuration Files

* `gradle.properties` — plugin version, platform version, build range
* `build.gradle.kts` — dependencies, signing config, JVM toolchain
* `local.properties` — machine-local signing credentials (gitignored)
* `src/main/resources/META-INF/plugin.xml` — plugin descriptor

## Adding a New Agent

1. Add entry to `AgentType` enum
2. Add enable/command fields to `AgentCliSettings.State`
3. Add settings UI controls in `AgentCliSettingsConfigurable`
4. Create a history reader in `session/` (implement file parsing for the agent's local data format)
5. Add hook config in `HookInstaller.configs()` if the agent supports hooks
6. Update `SessionPathResolver` if needed for path encoding