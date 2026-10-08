// Pure helpers: which tool calls touch a file, where, and the link that opens it.

export const SCHEME = 'agentcli'

type Hunk = { newStart: number; lines: string[] }

export type FileTarget = { path: string; line?: number }

const asRecord = (v: unknown): Record<string, unknown> | undefined =>
  typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : undefined

// First changed line of the first hunk: newStart counts leading context lines.
const firstChangedLine = (patch: unknown): number | undefined => {
  if (!Array.isArray(patch) || patch.length === 0) return undefined
  const hunk = patch[0] as Hunk
  if (typeof hunk?.newStart !== 'number' || !Array.isArray(hunk.lines)) return undefined
  const offset = hunk.lines.findIndex(l => l.startsWith('+') || l.startsWith('-'))
  return hunk.newStart + Math.max(offset, 0)
}

// Files a Bash command changed in the working tree. bashEditDiff is marked
// @internal in the engine's types, so read it defensively.
const bashTargets = (output: unknown, cwd: string): FileTarget[] => {
  const files = asRecord(asRecord(output)?.bashEditDiff)?.files
  if (!Array.isArray(files)) return []
  return files.flatMap(f => {
    const file = asRecord(f)
    const path = file?.filePath
    if (typeof path !== 'string' || path === '' || file?.deleted === true) return []
    const absolute = path.startsWith('/') ? path : `${cwd.replace(/\/$/, '')}/${path}`
    return [{ path: absolute, line: firstChangedLine(file?.hunks) }]
  })
}

export const fileTargets = (tool: string, input: unknown, output: unknown, cwd: string): FileTarget[] => {
  if (tool === 'Bash') return bashTargets(output, cwd)

  const args = asRecord(input)
  const path = args?.file_path ?? args?.notebook_path
  if (args === undefined || typeof path !== 'string' || path === '') return []

  switch (tool) {
    case 'Edit':
    case 'MultiEdit':
      return [{ path, line: firstChangedLine(asRecord(output)?.structuredPatch) }]
    case 'Read':
      return [{ path, line: typeof args.offset === 'number' ? args.offset : undefined }]
    case 'Write':
    case 'NotebookEdit':
      return [{ path }]
    default:
      return []
  }
}

export const linkHref = ({ path, line }: FileTarget): string => {
  const query = new URLSearchParams({ path })
  if (line !== undefined) query.set('line', String(line))
  return `${SCHEME}://open?${query.toString()}`
}

export const displayPath = ({ path, line }: FileTarget, cwd: string): string => {
  const base = cwd.endsWith('/') ? cwd : `${cwd}/`
  const shown = path.startsWith(base) ? path.slice(base.length) : path
  return line === undefined ? shown : `${shown}:${line}`
}
