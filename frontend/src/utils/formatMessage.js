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
