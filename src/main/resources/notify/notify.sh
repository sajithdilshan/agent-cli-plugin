#!/usr/bin/env bash
#
# Agent CLI Plugin — notification bridge.
# Invoked by per-agent hook scripts (Claude Code, Gemini CLI, Codex CLI).
#
# Usage:  notify.sh <event> <agent>
#   event: "set" | "clear"
#   agent: "claude" | "gemini" | "codex" | "cursor"
#
# Reads the hook's JSON payload from stdin, merges in plugin-injected
# session identity, and POSTs to the plugin's HTTP endpoint. Best-effort:
# failures are swallowed so they never block the CLI session.

set -u

EVENT="${1:-set}"
AGENT="${2:-${AGENT_CLI_PLUGIN_AGENT:-unknown}}"
URL="${AGENT_CLI_PLUGIN_NOTIFY_URL:-http://127.0.0.1:63342/agent-cli-plugin/notify}"
PLUGIN_ID="${AGENT_CLI_PLUGIN_SESSION_ID:-}"

PAYLOAD=""
if [ ! -t 0 ]; then
  PAYLOAD="$(cat || true)"
fi

escape_json() {
  # Minimal JSON string escaping. Messages are one-liner balloons; collapse
  # newlines/tabs to spaces rather than emitting \n escapes from the shell.
  printf '%s' "$1" \
    | tr '\n\r\t' '   ' \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# Try to pull session_id and message from the agent payload (best-effort, no jq dependency).
extract() {
  local key="$1"
  printf '%s' "$PAYLOAD" | sed -n 's/.*"'"$key"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

AGENT_SESSION_ID="$(extract session_id)"
MESSAGE="$(extract message)"
NOTIFICATION_TYPE="$(extract notification_type)"

if [ -z "$MESSAGE" ] && [ -n "$NOTIFICATION_TYPE" ]; then
  MESSAGE="$NOTIFICATION_TYPE"
fi

# If we have no session identity at all, the agent is running outside the
# plugin (plain terminal) — there is no IDE to notify, so skip the HTTP call.
if [ -z "$PLUGIN_ID" ] && [ -z "$AGENT_SESSION_ID" ]; then
  exit 0
fi

BODY="{\"event\":\"$(escape_json "$EVENT")\",\"agent\":\"$(escape_json "$AGENT")\""
if [ -n "$PLUGIN_ID" ]; then
  BODY="$BODY,\"plugin_session_id\":\"$(escape_json "$PLUGIN_ID")\""
fi
if [ -n "$AGENT_SESSION_ID" ]; then
  BODY="$BODY,\"session_id\":\"$(escape_json "$AGENT_SESSION_ID")\""
fi
if [ -n "$MESSAGE" ]; then
  BODY="$BODY,\"message\":\"$(escape_json "$MESSAGE")\""
fi
BODY="$BODY}"

# Fire-and-forget: detach curl so a hung/unreachable IDE can never block the
# agent session. Short connect/overall timeouts + swallowed errors keep it safe.
(
  curl -sS --max-time 2 --connect-timeout 1 -X POST \
    -H 'Content-Type: application/json' \
    -d "$BODY" \
    "$URL" >/dev/null 2>&1 || true
) &
disown 2>/dev/null || true

exit 0
