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
