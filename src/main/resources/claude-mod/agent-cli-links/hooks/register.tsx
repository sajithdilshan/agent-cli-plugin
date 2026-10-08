import type { EngineInterface, Register } from 'claude-code'

import { displayPath, fileTargets, linkHref } from './links'
import type { FileTarget } from './links'

const TRACKED_TOOLS = ['Bash', 'Edit', 'MultiEdit', 'Write', 'Read', 'NotebookEdit']
const MAX_REMEMBERED = 1000

// A resumed transcript does not carry every field of a live result (Bash's
// working-tree diff is one), so targets are remembered per tool_use_id.
const remember = async ($: EngineInterface, id: string, targets: FileTarget[]) => {
  await $.store.set(id, targets)
  const keys = await $.store.keys()
  for (const key of keys.slice(0, Math.max(keys.length - MAX_REMEMBERED, 0))) {
    await $.store.delete(key)
  }
}

const remembered = async ($: EngineInterface, id: string): Promise<FileTarget[]> => {
  const value = await $.store.get(id)
  return Array.isArray(value) ? (value as FileTarget[]) : []
}

export const register: Register = on => {
  on('tool.call', async ($, e, next) => {
    const ran = await next(e)
    if (TRACKED_TOOLS.includes(e.tool) && ran.deny === undefined && ran.isError !== true) {
      // Remembering is best effort: it must never fail the tool call itself.
      try {
        const targets = fileTargets(e.tool, e, ran.result, await $.session.cwd())
        if (targets.length > 0) await remember($, e.tool_use_id, targets)
      } catch {}
    }
    return ran
  }).catch(($, e, next) => next(e))

  // Keeps the engine's own tool row and adds a clickable path per touched file under it.
  on('ui.render', { component: 'ToolUse' }, async ($, e, next) => {
    if (e.props.isRunning || e.props.isErrored || e.props.isInterrupted) {
      return next(e)
    }

    const cwd = await $.session.cwd()
    const live = fileTargets(e.props.tool, e.props.input, e.props.output, cwd)
    const targets = live.length > 0 ? live : await remembered($, e.props.tool_use_id)
    if (targets.length === 0) {
      return next(e)
    }

    const row = await next(e)
    const { Box, Text, Link } = $.ui.resolve(e)

    return (
      <Box flexDirection="column">
        {row}
        {targets.map(target => (
          <Text dimColor>
            {'  ⎿  '}
            <Link href={linkHref(target)}>{displayPath(target, cwd)}</Link>
          </Text>
        ))}
      </Box>
    )
  })
}
