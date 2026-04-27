# Agent CLI Plugin — notification bridge (Windows).
# Invoked by per-agent hook scripts (Claude Code, Gemini CLI, Codex CLI).
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File notify.ps1 <event> <agent>
#   event: "set" | "clear"
#   agent: "claude" | "gemini" | "codex" | "cursor"
#
# Reads the hook's JSON payload from stdin, merges in plugin-injected session
# identity, and POSTs to the plugin's HTTP endpoint. Best-effort: errors are
# swallowed so they never block the CLI session.

param(
    [string]$Event = 'set',
    [string]$Agent = $env:AGENT_CLI_PLUGIN_AGENT
)

$ErrorActionPreference = 'SilentlyContinue'

if (-not $Agent) { $Agent = 'unknown' }

$url = $env:AGENT_CLI_PLUGIN_NOTIFY_URL
if (-not $url) { $url = 'http://127.0.0.1:63342/agent-cli-plugin/notify' }

$pluginId = $env:AGENT_CLI_PLUGIN_SESSION_ID

$payload = ''
if (-not [Console]::IsInputRedirected.Equals($false)) {
    try { $payload = [Console]::In.ReadToEnd() } catch { $payload = '' }
}

function Get-JsonField($text, $key) {
    if (-not $text) { return '' }
    $pattern = '"' + [regex]::Escape($key) + '"\s*:\s*"([^"]*)"'
    $match = [regex]::Match($text, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

$agentSessionId   = Get-JsonField $payload 'session_id'
$message          = Get-JsonField $payload 'message'
$notificationType = Get-JsonField $payload 'notification_type'
if (-not $message -and $notificationType) { $message = $notificationType }

# If we have no session identity at all, the agent is running outside the
# plugin (plain terminal) — there is no IDE to notify, so skip the HTTP call.
if (-not $pluginId -and -not $agentSessionId) { exit 0 }

$body = @{
    event = $Event
    agent = $Agent
}
if ($pluginId)        { $body.plugin_session_id = $pluginId }
if ($agentSessionId)  { $body.session_id        = $agentSessionId }
if ($message)         { $body.message           = $message }

$json = $body | ConvertTo-Json -Compress

# Fire-and-forget: a short-lived background job so a hung/unreachable IDE
# can never block the agent session.
Start-Job -ScriptBlock {
    param($u, $b)
    try {
        Invoke-WebRequest -Uri $u -Method POST -Body $b -ContentType 'application/json' -TimeoutSec 2 -UseBasicParsing | Out-Null
    } catch { }
} -ArgumentList $url, $json | Out-Null

exit 0
