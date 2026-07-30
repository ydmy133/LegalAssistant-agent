/**
 * 将助手消息转为安全 HTML（换行、加粗、Markdown 链接→引用芯片）
 */
export function formatMessage(text) {
  if (!text) return ''

  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 先处理加粗，再把 [text](url) 换成引用芯片（url 已在 escape 后仍是纯文本）
  const withBold = escaped.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  const withLinks = withBold.replace(
    /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
    (_, label, url) => renderCiteChip(label, url)
  )
  // 正文中残留的裸 URL 也渲染为芯片（跳过已在属性中的 URL）
  const withBare = withLinks.replace(
    /(?<!["'=/])https?:\/\/[^\s<>"')\]]+/g,
    (url) => {
      let label = url
      try {
        label = new URL(url).hostname.replace(/^www\./, '')
      } catch {
        /* keep url */
      }
      return renderCiteChip(label, url)
    }
  )

  return withBare.replace(/\n/g, '<br>')
}

function renderCiteChip(label, url) {
  const safeUrl = escapeAttr(url)
  let host = ''
  try {
    host = new URL(url).hostname.replace(/^www\./, '')
  } catch {
    host = ''
  }
  const shortLabel = label.length > 28 ? `${label.slice(0, 28)}…` : label
  const hostHtml = host
    ? `<span class="cite-host">${escapeHtml(host)}</span>`
    : ''
  return `<a class="cite-chip" href="${safeUrl}" target="_blank" rel="noopener noreferrer" title="${safeUrl}"><span class="cite-label">${escapeHtml(shortLabel)}</span>${hostHtml}</a>`
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/'/g, '&#39;')
}

/**
 * 从 metadata 提取去重后的来源（优先 metadata.sources，否则从 thoughtSteps 汇总）
 */
export function extractSources(metaOrRaw) {
  if (!metaOrRaw) return []
  try {
    const meta = typeof metaOrRaw === 'string' ? JSON.parse(metaOrRaw) : metaOrRaw
    if (Array.isArray(meta?.sources) && meta.sources.length) {
      return meta.sources
    }
    return collectSourcesFromThought(meta?.thoughtSteps)
  } catch {
    return []
  }
}

export function collectSourcesFromThought(steps) {
  if (!Array.isArray(steps)) return []
  const out = []
  const seen = new Set()
  for (const step of steps) {
    if (!step || step.type !== 'tool' || !Array.isArray(step.sources)) continue
    for (const s of step.sources) {
      if (!s) continue
      const key = s.url ? `u:${s.url}` : `f:${s.fileName || s.title || ''}`
      if (!key || key.endsWith(':') || seen.has(key)) continue
      seen.add(key)
      out.push(s)
    }
  }
  return out
}

export function webSourcesOnly(sources) {
  return (sources || []).filter((s) => s && s.kind === 'web' && s.url)
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
 * 合并连续相同步骤；工具按 callId 合并 running→done；status 按 name 合并。
 */
export function dedupeThoughtSteps(steps) {
  if (!Array.isArray(steps) || steps.length === 0) return []
  const out = []
  for (const step of steps) {
    if (!step) continue
    const upsertIdx = findUpsertIndex(out, step)
    if (upsertIdx >= 0) {
      const merged = { ...out[upsertIdx], ...step }
      if (Array.isArray(step.sources)) merged.sources = step.sources
      else if (Array.isArray(out[upsertIdx].sources)) merged.sources = out[upsertIdx].sources
      out[upsertIdx] = merged
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
  if (type === 'tool' && step.callId) {
    for (let i = steps.length - 1; i >= 0; i--) {
      const prev = steps[i]
      if ((prev.type || '') === 'tool' && prev.callId === step.callId) {
        return i
      }
    }
    return -1
  }
  const name = step.name
  if (!name || type !== 'status') return -1
  for (let i = steps.length - 1; i >= 0; i--) {
    const prev = steps[i]
    if ((prev.type || '') === type && prev.name === name) {
      return i
    }
  }
  return -1
}

function thoughtStepKey(step) {
  return [step.type || '', step.label || '', step.query || '', step.name || '', step.callId || ''].join('\u0001')
}

const TOOL_VERB = {
  searchLegalKnowledge: 'Searched',
  searchWeb: 'Searched web',
  searchCases: 'Searched cases',
  getCaseDetail: 'Read case',
  getConversationHistory: 'Recalled',
}

const TOOL_LINE_VERB = {
  searchLegalKnowledge: 'Searched',
  searchWeb: 'Searched',
  searchCases: 'Searched',
  getCaseDetail: 'Read',
  getConversationHistory: 'Recalled',
}

const STATUS_VERB = {
  analyze: 'Thinking',
  context: 'Recalled context',
  skip_retrieve: 'Skipped retrieval',
  generate: 'Composing',
}

/**
 * Thought 面板标题：流式中 / 完成后 verb-group（类 Cursor）
 */
export function thoughtSummaryLabel(steps, { streaming = false, totalMs = null } = {}) {
  if (!steps || steps.length === 0) {
    return streaming ? 'Thinking…' : 'Thought briefly'
  }

  if (streaming) {
    const last = steps[steps.length - 1]
    if (last?.type === 'tool') {
      const verb = last.status === 'running'
        ? `Running ${TOOL_LINE_VERB[last.name] || 'tool'}`
        : (TOOL_LINE_VERB[last.name] || 'Ran')
      const target = thoughtStepTarget(last)
      return target ? `${verb} ${target}…` : `${verb}…`
    }
    return `${thoughtStepVerb(last)}…`
  }

  const tools = steps.filter((s) => s.type === 'tool')
  const durationPart = totalMs != null && totalMs > 0 ? ` for ${formatDuration(totalMs)}` : ''

  if (tools.length === 0) {
    return `Thought${durationPart || ' briefly'}`
  }

  const groups = {}
  for (const t of tools) {
    const verb = TOOL_VERB[t.name] || 'Ran'
    groups[verb] = (groups[verb] || 0) + (t.repeat || 1)
  }
  const parts = Object.entries(groups).map(([verb, n]) =>
    n === 1 ? `${verb} 1` : `${verb} ${n}`
  )
  return `Thought${durationPart} · ${parts.join(', ')}`
}

/** Cursor 风格：行首动词（灰） */
export function thoughtStepVerb(step) {
  if (!step) return 'Step'
  if (step.type === 'tool') {
    const base = TOOL_LINE_VERB[step.name] || 'Ran'
    if (step.status === 'running') return `Running ${base.toLowerCase()}`
    return base
  }
  return STATUS_VERB[step.name] || step.label || 'Thought'
}

/** Cursor 风格：动词后的目标（文件名 / 查询 / 摘要） */
export function thoughtStepTarget(step) {
  if (!step) return ''
  if (step.type === 'tool') {
    const sources = Array.isArray(step.sources) ? step.sources : []
    if (sources.length) {
      const first = sources[0]
      const name = first.fileName || first.title || first.url || ''
      const short = truncateText(name, 48)
      if (sources.length === 1) return short
      return `${truncateText(name, 36)}, ${sources.length - 1} more`
    }
    const q = (step.query || '').trim()
    if (q) return truncateText(q, 56)
    return truncateText(step.detail || step.label || '', 48)
  }
  return step.detail || ''
}

function truncateText(s, max) {
  const t = String(s || '').replace(/\s+/g, ' ').trim()
  if (t.length <= max) return t
  return `${t.slice(0, max - 1)}…`
}

/** @deprecated 兼容旧调用；优先用 thoughtStepVerb + thoughtStepTarget */
export function thoughtStepTitle(step) {
  if (!step) return '步骤'
  const verb = thoughtStepVerb(step)
  const target = thoughtStepTarget(step)
  return target ? `${verb} ${target}` : verb
}

export function thoughtStepDuration(step) {
  if (step?.durationMs == null) return ''
  return formatDuration(step.durationMs)
}

