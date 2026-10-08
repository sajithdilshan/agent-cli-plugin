import { describe, expect, mock, test } from 'claude-code/testing'

import { displayPath, fileTargets, linkHref } from '../hooks/links'

const EDIT_OUTPUT = {
  structuredPatch: [{ oldStart: 10, oldLines: 7, newStart: 10, newLines: 7, lines: [' a', ' b', ' c', '-x', '+y', ' d'] }],
}

describe('fileTargets', () => {
  test('Edit points at the first changed line', async () => {
    expect(fileTargets('Edit', { file_path: '/p/A.kt' }, EDIT_OUTPUT, '/p')).toEqual([{ path: '/p/A.kt', line: 13 }])
  })

  test('Read uses its offset, Write has no line', async () => {
    expect(fileTargets('Read', { file_path: '/p/A.kt', offset: 40 }, undefined, '/p')).toEqual([{ path: '/p/A.kt', line: 40 }])
    expect(fileTargets('Write', { file_path: '/p/A.kt' }, undefined, '/p')).toEqual([{ path: '/p/A.kt' }])
  })

  test('Bash links the files its command changed, skipping deletions', async () => {
    const output = {
      stdout: '',
      bashEditDiff: {
        files: [
          { filePath: 'README.md', hunks: [{ oldStart: 151, oldLines: 4, newStart: 151, newLines: 5, lines: [' a', ' b', ' c', '-x', '+y'] }] },
          { filePath: '/p/gone.txt', hunks: [], deleted: true },
        ],
        moreFiles: 0,
      },
    }
    expect(fileTargets('Bash', { command: 'x' }, output, '/p')).toEqual([{ path: '/p/README.md', line: 154 }])
  })

  test('other tools and plain Bash are ignored', async () => {
    expect(fileTargets('Bash', { command: 'ls' }, { stdout: '' }, '/p')).toEqual([])
    expect(fileTargets('Grep', { pattern: 'x' }, undefined, '/p')).toEqual([])
  })
})

test('href and label', async () => {
  const target = { path: '/p/src/A B.kt', line: 3 }
  expect(linkHref(target)).toBe('agentcli://open?path=%2Fp%2Fsrc%2FA+B.kt&line=3')
  expect(displayPath(target, '/p')).toBe('src/A B.kt:3')
})

test('an Edit row gains a link to the file', async ($, on) => {
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine row</Text>
  })
  on('session.cwd', () => ({ value: '/p' }))
  mock.store(on)
  const ui = await $.ui.mount({
    plugin: 'agent-cli-links',
    surface: 'terminal',
    component: 'ToolUse',
    props: {
      tool_use_id: 't1',
      tool: 'Edit',
      input: { file_path: '/p/A.kt', old_string: 'x', new_string: 'y' },
      isRunning: false,
      isErrored: false,
      isInterrupted: false,
      output: EDIT_OUTPUT,
    },
  })
  const link = await ui.find({ type: 'Link' })
  expect(await ui.find({ type: 'Text', text: /engine row/ })).toBeDefined()
  expect(link?.props.href).toBe('agentcli://open?path=%2Fp%2FA.kt&line=13')
  expect(link?.children).toEqual(['A.kt:13'])
})

test('a resumed Bash row, its diff gone, links what was remembered', async ($, on) => {
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine row</Text>
  })
  on('session.cwd', () => ({ value: '/p' }))
  mock.store(on, { t2: [{ path: '/p/README.md', line: 154 }] })
  const ui = await $.ui.mount({
    plugin: 'agent-cli-links',
    surface: 'terminal',
    component: 'ToolUse',
    requestId: 't2',
    props: {
      tool_use_id: 't2',
      tool: 'Bash',
      input: { command: "printf '\\n' >> README.md" },
      isRunning: false,
      isErrored: false,
      isInterrupted: false,
      output: { stdout: '', stderr: '', interrupted: false },
    },
  })
  const link = await ui.find({ type: 'Link' })
  expect(link?.props.href).toBe('agentcli://open?path=%2Fp%2FREADME.md&line=154')
})
