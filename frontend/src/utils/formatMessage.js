/**
 * 将助手消息转为安全 HTML（换行、加粗等轻量 Markdown）
 */
export function formatMessage(text) {
  if (!text) return ''

  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  return escaped
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}

export function formatDuration(ms) {
  if (ms == null || ms < 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(1)}s`
  const min = Math.floor(sec / 60)
  const rem = Math.round(sec % 60)
  return `${min}m ${rem}s`
}

/**
 * 将后端 timing 对象格式化为可读明细行
 */
export function formatTimingDetails(timing) {
  if (!timing || typeof timing !== 'object') return []

  const lines = []
  const total = timing.totalMs ?? timing.durationMs
  if (total != null) {
    lines.push({ label: '总耗时', value: formatDuration(total), level: 0, emphasize: true })
  }
  if (timing.model) {
    lines.push({ label: '模型', value: timing.model, level: 0 })
  }
  if (timing.tokens) {
    const { input, output } = timing.tokens
    const parts = []
    if (input != null) parts.push(`入 ${input}`)
    if (output != null) parts.push(`出 ${output}`)
    if (parts.length) {
      lines.push({ label: 'Tokens', value: parts.join(' / '), level: 0 })
    }
  }

  const derived = timing.derived || {}
  for (const [key, value] of Object.entries(derived)) {
    lines.push({ label: key, value: formatDuration(value), level: 0 })
  }

  const pushStage = (stage, level) => {
    if (!stage) return
    const detail = stage.detail ? `（${stage.detail}）` : ''
    lines.push({
      label: `${stage.name}${detail}`,
      value: formatDuration(stage.durationMs),
      level,
      offsetMs: stage.offsetMs,
    })
    if (Array.isArray(stage.children)) {
      for (const child of stage.children) {
        pushStage(child, level + 1)
      }
    }
  }

  if (Array.isArray(timing.stages)) {
    for (const stage of timing.stages) {
      pushStage(stage, 0)
    }
  }

  return lines
}

/**
 * 按 SSE 规范解析缓冲区：多行 data 用 \n 拼接，避免正文换行被误拆事件。
 * @returns {{ events: string[], remaining: string }}
 */
export function parseSseBuffer(buffer) {
  const events = []
  let remaining = buffer

  while (true) {
    const end = remaining.indexOf('\n\n')
    if (end === -1) break

    const rawEvent = remaining.slice(0, end)
    remaining = remaining.slice(end + 2)

    const dataLines = []
    for (const line of rawEvent.split('\n')) {
      if (line.startsWith('data:')) {
        const payload = line.startsWith('data: ') ? line.slice(6) : line.slice(5)
        dataLines.push(payload)
      }
    }
    if (dataLines.length > 0) {
      events.push(dataLines.join('\n'))
    }
  }

  return { events, remaining }
}

/**
 * 从消息 metadataJson 提取思考步骤（刷新后可回显）
 */
export function extractThoughtSteps(metaOrRaw) {
  if (!metaOrRaw) return []
  try {
    const meta = typeof metaOrRaw === 'string' ? JSON.parse(metaOrRaw) : metaOrRaw
    return dedupeThoughtSteps(Array.isArray(meta?.thoughtSteps) ? meta.thoughtSteps : [])
  } catch {
    return []
  }
}

/**
 * 合并连续相同步骤；同 name 的 tool/status 生命周期（running→done）合并为一行。
 */
export function dedupeThoughtSteps(steps) {
  if (!Array.isArray(steps) || steps.length === 0) return []
  const out = []
  for (const step of steps) {
    if (!step) continue
    const upsertIdx = findUpsertIndex(out, step)
    if (upsertIdx >= 0) {
      out[upsertIdx] = { ...out[upsertIdx], ...step }
      continue
    }
    const prev = out[out.length - 1]
    const key = thoughtStepKey(step)
    if (prev && thoughtStepKey(prev) === key) {
      prev.repeat = (prev.repeat || 1) + 1
      if (step.detail) prev.detail = step.detail
      if (step.status) prev.status = step.status
      if (step.durationMs != null) prev.durationMs = step.durationMs
      continue
    }
    out.push({ ...step })
  }
  return out
}

function findUpsertIndex(steps, step) {
  const type = step.type || ''
  const name = step.name
  if (!name || (type !== 'tool' && type !== 'status')) return -1
  for (let i = steps.length - 1; i >= 0; i--) {
    const prev = steps[i]
    if ((prev.type || '') === type && prev.name === name) {
      return i
    }
  }
  return -1
}

function thoughtStepKey(step) {
  return [step.type || '', step.label || '', step.query || '', step.name || ''].join('\u0001')
}

const TOOL_VERB = {
  searchLegalKnowledge: '检索',
  searchWeb: '搜索',
  searchCases: '查阅',
  getCaseDetail: '查阅',
  getConversationHistory: '查阅',
}

/**
 * Thought 面板标题：流式中 / 完成后 verb-group（类 Cursor / grok-build）
 */
export function thoughtSummaryLabel(steps, { streaming = false, totalMs = null } = {}) {
  if (!steps || steps.length === 0) {
    return streaming ? 'Thinking…' : 'Thought briefly'
  }

  if (streaming) {
    const last = steps[steps.length - 1]
    if (last?.type === 'tool') {
      const verb = last.status === 'done' ? 'Ran' : 'Running'
      return `${verb} ${last.label || last.name}…`
    }
    return last?.label ? `${last.label}…` : 'Thinking…'
  }

  const tools = steps.filter((s) => s.type === 'tool')
  const durationPart = totalMs != null && totalMs > 0 ? ` for ${formatDuration(totalMs)}` : ''

  if (tools.length === 0) {
    return `Thought${durationPart || ' briefly'}`
  }

  const groups = {}
  for (const t of tools) {
    const verb = TOOL_VERB[t.name] || '调用'
    groups[verb] = (groups[verb] || 0) + (t.repeat || 1)
  }
  const parts = Object.entries(groups).map(([verb, n]) =>
    n === 1 ? verb : `${verb} ${n} 次`
  )
  return `Thought${durationPart} · ${parts.join(', ')}`
}

/** 单步行标题：Ran 检索法律知识库 (1.3s) */
export function thoughtStepTitle(step) {
  if (!step) return '步骤'
  const label = step.label || step.name || '步骤'
  if (step.type === 'tool') {
    const prefix = step.status === 'running' ? 'Running' : 'Ran'
    return `${prefix} ${label}`
  }
  return label
}

export function thoughtStepDuration(step) {
  if (step?.durationMs == null) return ''
  return formatDuration(step.durationMs)
}
