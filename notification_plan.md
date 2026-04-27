# Notification Feature — Implementation Plan

Goal: when an active CLI session needs user attention, show a red indicator on its row in the right-side sessions panel and surface an IntelliJ balloon notification. Clear the state once the user responds.

Scope: Claude Code, Gemini CLI, and OpenAI Codex CLI get first-class support. Cursor gets a best-effort "task stopped" signal (v1.1).

---

## 1. Verified facts (ground truth for design)

### Claude Code
- `Notification` hook matchers are **exact strings** from an enum: `permission_prompt`, `idle_prompt`, `auth_success`, `elicitation_dialog`. Multiple values joined with `|`.
- Stdin payload includes: `session_id` (stable per session), `transcript_path`, `cwd`, `permission_mode`, `hook_event_name`, `notification_type`, `message`, `severity`.
- `Notification` hooks are **fire-and-forget** — exit code and stdout are ignored, they cannot block.
- No dedicated "resolved" hook exists. Candidate clear signals: `Stop` (response finished), `UserPromptSubmit` (user replied), `SessionEnd`.
- Settings hierarchy: managed → CLI args → `.claude/settings.local.json` → `.claude/settings.json` → `~/.claude/settings.json`.
- `CLAUDE_ENV_FILE` is only available for `SessionStart`/`CwdChanged`/`FileChanged`, not `Notification`. **We don't need it** — `session_id` from stdin is enough.

### Gemini CLI
- `Notification` event exists, but only fires on tool-permission prompts (`notification_type: "ToolPermission"` is the only documented value). Not a generic "needs attention" event.
- For "task complete / idle" we need `AfterAgent` additionally.
- Payload: `session_id`, `transcript_path`, `cwd`, `hook_event_name`, `timestamp`, plus `notification_type`, `message`, `details` for Notification.
- Config paths: `~/.gemini/settings.json`, `.gemini/settings.json`, `/etc/gemini-cli/settings.json`.
- Schema is near-identical to Claude Code's, only `type: "command"` supported.

### Cursor CLI
- **No `Notification` event.** Cursor's third-party-hooks doc explicitly drops `Notification` and `PermissionRequest` from the Claude Code compat shim.
- Native schema is materially different: `hooks.json` (not `settings.json`), camelCase event names, `version: 1`, object-based `matcher` (not regex).
- Payload uses `conversation_id`, not `session_id`.
- Best available signals: `stop` (agent finished) and `preToolUse` with a tool-type matcher for destructive actions.
- **Decision:** ship Cursor support as v1.1. Start with `stop` = clear (no "set" signal), mark Cursor sessions as "attention tracking not available" in v1.

### OpenAI Codex CLI
- **Has a first-class hooks system** (verified in `github.com/openai/codex`, `codex-rs/hooks/`). Schema is intentionally close to Claude Code's.
- Events: `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, **`PermissionRequest`** (the "needs attention" signal), `Stop`. Legacy top-level `notify = [...]` in `config.toml` also fires on `agent-turn-complete`.
- Config locations (discovered per layer): `~/.codex/hooks.json` (Claude-Code-shaped JSON) **or** a `[hooks]` table in `~/.codex/config.toml`. `$CODEX_HOME` overrides the directory.
- Stdin payload (common): `session_id` (UUID), `turn_id`, `transcript_path`, `cwd`, `model`, `permission_mode`, `hook_event_name`. `PreToolUse`/`PostToolUse`/`PermissionRequest` add `tool_name`, `tool_input`, (±) `tool_use_id`.
- Matcher is regex on `tool_name` for tool events (e.g. `"^Bash$"`); lifecycle events use empty/`*` matcher.
- `codex exec --json` also emits JSONL events including `session_configured` with the same `session_id`, which is a fallback path if users don't want to install hooks.
- **No documented `Notification` event** (unlike Claude Code/Gemini). `PermissionRequest` is the direct "needs approval" signal — arguably cleaner than Claude's `Notification` because it fires *as* the prompt is shown, not on a generic notification channel.

---

## 2. Architecture

```
CLI (claude/gemini/codex) --hook--> shell script --POST--> IntelliJ built-in server (63342)
                                                                         │
                                                            AgentCliNotifyHandler
                                                                         │
                                                            SessionAttentionService
                                                                         │
                                                   ┌─────────────────────┴──────────────────┐
                                                   ▼                                        ▼
                                       SessionSidebarPanel (red dot)           NotificationGroupManager (balloon)
```

Key design choices:
- **Reuse IntelliJ's built-in server** on port 63342 via `httpRequestHandler` — no port conflicts, no new config.
- **Session identity via env var + stdin.** Inject `AGENT_CLI_PLUGIN_SESSION_ID=<uuid>` into the PTY env. The hook script prefers the env var (works for all agents, matches plugin-side session) and falls back to parsing `session_id` from stdin JSON. This survives Cursor's different field names and avoids coupling to any single CLI's payload shape.
- **Shell script in plugin data dir**, not inline commands, so all three agents call the same script and we can evolve it without rewriting user settings.

---

## 3. File-level change plan

### 3.1 Session model — add attention state
**`session/AgentCliSession.kt`**
- Add `var needsAttention: Boolean = false` and `var attentionMessage: String? = null`.

**`session/SessionManager.kt`**
- Add a message-bus `Topic<SessionAttentionListener>` (follow the existing `AgentCliSettings.SETTINGS_CHANGED_TOPIC` precedent).
- Methods: `markAttention(sessionId, message)`, `clearAttention(sessionId)`. Both fire on the topic.
- `findById(sessionId: String): AgentCliSession?`.

### 3.2 HTTP endpoint
**New `notify/AgentCliNotifyHandler.kt`** — extends `org.jetbrains.ide.HttpRequestHandler`
- `isSupported`: path starts with `/agent-cli-plugin/notify`.
- `process`: parse JSON body, extract `plugin_session_id` (primary) and `session_id` (fallback), plus `event` (`set` | `clear`), `message`, `agent`. Dispatch on EDT to `SessionAttentionService`. Return 204.
- Only accept `localhost`/`127.0.0.1` (belt-and-braces; IntelliJ's server already restricts this).

**`plugin.xml`**
- Register `<httpRequestHandler implementation="...AgentCliNotifyHandler"/>`.
- Register notification group: `<notificationGroup id="AgentCliPlugin.Attention" displayType="BALLOON"/>`.

### 3.3 Attention service
**New `notify/SessionAttentionService.kt`** — `@Service(Level.PROJECT)`
- `onNotify(sessionId, message, agent)`: call `SessionManager.markAttention`, post an IntelliJ balloon via `NotificationGroupManager`, optionally flash the tool window.
- `onClear(sessionId)`: call `SessionManager.clearAttention`.
- Auto-clear when the session's terminal gains focus or receives keyboard input (subscribe to a listener in `AgentCliPanel`). This gives us a "user saw it" signal without relying on cross-agent resolved hooks.

### 3.4 Sessions panel rendering
**`toolwindow/SessionSidebarPanel.kt` → `ActiveSessionCellRenderer` (~line 552-614)**
- Line 571 currently sets `AllIcons.Actions.Execute`. Change to: if `session.needsAttention` → a red circle icon (bundled SVG or tinted `AllIcons.Nodes.ErrorMark`), else existing green execute icon.
- Subscribe the panel to `SessionManager`'s attention topic and call `activeSessionList.repaint()` on events.

### 3.5 PTY env var injection
**`toolwindow/AgentCliPanel.kt:152-168`**
- Add `env["AGENT_CLI_PLUGIN_SESSION_ID"] = session.id` before passing `env` to `PtyBridge`.
- Also add `AGENT_CLI_PLUGIN_NOTIFY_URL=http://localhost:63342/agent-cli-plugin/notify` so the script doesn't hardcode the port (IntelliJ's built-in port can vary when 63342 is taken).

### 3.6 Hook installer
**New `notify/HookInstaller.kt`** — invoked by a settings-page button and on first run (with user consent prompt).
- Writes the notify shell script to `PathManager.getPluginsPath()/agent-cli-plugin/notify.sh` (chmod +x).
- Merges hook entries into `~/.claude/settings.json`, `~/.gemini/settings.json`, and `~/.codex/hooks.json` (read → merge → write, preserving existing hooks). Idempotent: re-running replaces only our entries, keyed by a stable name like `"agent-cli-plugin-notify"`.
- For Codex, detect whether the installed `codex` version supports hooks (run `codex --version` and parse, or probe for the `codex-rs/hooks` feature by checking if writing to `~/.codex/hooks.json` is honored). If unsupported, fall back to writing the legacy `notify` line in `~/.codex/config.toml`.
- Shows a diff preview dialog before writing.
- Uninstall action that removes just our entries.

**New `resources/notify.sh`** (shipped and copied out on install):
```bash
#!/usr/bin/env bash
set -u
URL="${AGENT_CLI_PLUGIN_NOTIFY_URL:-http://localhost:63342/agent-cli-plugin/notify}"
PAYLOAD=$(cat)
EVENT="${1:-set}"
AGENT="${2:-unknown}"
# Prefer plugin-injected id; fall back to whatever the CLI provided.
PLUGIN_ID="${AGENT_CLI_PLUGIN_SESSION_ID:-}"
BODY=$(printf '%s' "$PAYLOAD" | jq -c \
  --arg pid "$PLUGIN_ID" --arg ev "$EVENT" --arg ag "$AGENT" \
  '. + {plugin_session_id: $pid, event: $ev, agent: $ag}')
curl -sS -m 2 -X POST -H 'Content-Type: application/json' -d "$BODY" "$URL" >/dev/null 2>&1 || true
```

### 3.7 Per-agent hook configuration

**Claude Code** (`~/.claude/settings.json`):
```jsonc
{
  "hooks": {
    "Notification": [{
      "matcher": "permission_prompt|idle_prompt",
      "hooks": [{ "type": "command", "command": "<notify.sh> set claude" }]
    }],
    "Stop":       [{ "matcher": "", "hooks": [{ "type": "command", "command": "<notify.sh> clear claude" }] }],
    "SessionEnd": [{ "matcher": "", "hooks": [{ "type": "command", "command": "<notify.sh> clear claude" }] }]
  }
}
```

**Gemini CLI** (`~/.gemini/settings.json`):
```jsonc
{
  "hooks": {
    "Notification": [{
      "matcher": "*",
      "hooks": [{ "type": "command", "command": "<notify.sh> set gemini" }]
    }],
    "AfterAgent": [{
      "matcher": "*",
      "hooks": [{ "type": "command", "command": "<notify.sh> clear gemini" }]
    }],
    "SessionEnd": [{
      "matcher": "*",
      "hooks": [{ "type": "command", "command": "<notify.sh> clear gemini" }]
    }]
  }
}
```

**OpenAI Codex CLI** (`~/.codex/hooks.json` — preferred JSON form; TOML variant is equivalent):
```jsonc
{
  "hooks": {
    "PermissionRequest": [{
      "matcher": "",
      "hooks": [{ "type": "command", "command": "<notify.sh> set codex" }]
    }],
    "Stop": [{
      "matcher": "",
      "hooks": [{ "type": "command", "command": "<notify.sh> clear codex" }]
    }]
  }
}
```
Notes:
- Codex has no `SessionEnd` event; `Stop` covers "task done / idle". Process exit is already observed plugin-side via `PtyBridge.onExit` and clears the session.
- `PermissionRequest` fires on the approval prompt itself — no matcher filtering needed (the event IS the attention signal). Leave `matcher` empty.
- If the user's Codex is too old to have hooks, fall back to the legacy `notify = ["<notify.sh>", "set", "codex"]` line in `~/.codex/config.toml` — this fires on `agent-turn-complete` only (task-done, no permission signal), which still beats nothing.

**Cursor** (v1.1 — deferred): `~/.cursor/hooks.json` with `stop` → clear only. Document in UI that Cursor has no "attention" signal upstream.

---

## 4. Clear-state strategy

A red dot must be clearable. Multiple independent signals, first wins:
1. **Agent-side clear hook** — `Stop` / `SessionEnd` / `AfterAgent` (see above).
2. **Terminal focus/typing** — when the PTY for that session receives keyboard input, call `SessionAttentionService.onClear` locally. Covers the case where the user just types `y<enter>` and the CLI never fires a clear hook.
3. **Balloon action** — include a "Mark as seen" action on the IntelliJ balloon.
4. **Session close** — already handled: `closeSession` clears everything.

---

## 5. User flow

1. User installs/updates the plugin.
2. Plugin shows a one-time "Enable attention notifications?" dialog with a diff preview of the files it will modify (`~/.claude/settings.json`, `~/.gemini/settings.json`). Cursor section says "unsupported upstream".
3. On approval, plugin writes the script + merges hook entries.
4. Settings page exposes: enable/disable per agent, reinstall, uninstall, "Show preview".

---

## 6. Testing

Unit:
- JSON merge in `HookInstaller` — preserves unrelated hook entries, idempotent on rerun, correctly removes on uninstall.
- `AgentCliNotifyHandler` — accepts well-formed POST, rejects malformed, dispatches to service.

Integration (manual, documented in `notification_plan.md` follow-up):
- Claude Code: run `claude`, trigger a tool permission → red dot appears, balloon shows → approve → dot clears.
- Gemini: same flow with a tool that prompts.
- Codex: run `codex`, trigger a `PermissionRequest` (e.g. ask it to run a shell command under `default` approval mode) → red dot + balloon → approve → `Stop` clears on turn end.
- Codex legacy fallback: remove `~/.codex/hooks.json`, set `notify` in `config.toml` → verify task-complete signal still works.
- Multiple concurrent sessions across different agents: verify `plugin_session_id` maps to the correct row.
- Port variation: kill port 63342 owner, verify plugin uses the fallback port via `AGENT_CLI_PLUGIN_NOTIFY_URL`.

---

## 7. Open questions (decide before coding)

1. **Port discovery.** IntelliJ's built-in port is usually 63342 but falls back if taken. We need to read `BuiltInServerManager.getInstance().port` at PTY spawn time and inject it into `AGENT_CLI_PLUGIN_NOTIFY_URL`. Confirm.
2. **Settings file conflicts.** If the user already has hand-written `Notification` hooks, we merge as an additional entry — do we offer a "replace" option too, or always additive?
3. **Cursor in v1 vs v1.1.** Skip entirely in v1, or wire `stop` as clear-only (no set signal) so at least the UI stays consistent when they switch agents?
4. **Balloon frequency.** Should we throttle balloons for the same session (e.g., one per 30 s) to avoid spam if the CLI emits many `idle_prompt` events?
5. **Codex hooks vs. legacy `notify`.** Install both (hooks preferred, legacy as safety net) or just hooks? Installing both risks double-fire on newer Codex versions that honor both paths.

---

## 8. Implementation order

1. `AgentCliSession` + `SessionManager` attention state and topic. (No UI yet, testable.)
2. `AgentCliNotifyHandler` + `SessionAttentionService` + `plugin.xml` wiring. Verify with `curl` by hand.
3. PTY env injection in `AgentCliPanel`.
4. Renderer change in `SessionSidebarPanel` (red dot).
5. Balloon via `NotificationGroupManager`.
6. `notify.sh` + `HookInstaller` (Claude + Gemini + Codex) + settings-page button.
7. Codex legacy `notify` fallback (older versions).
8. Focus/typing auto-clear.
9. Cursor (v1.1).

Each step is independently mergeable.
