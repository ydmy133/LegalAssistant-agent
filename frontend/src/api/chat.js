import request from './request'
import { parseSseBuffer, dedupeThoughtSteps } from '../utils/formatMessage'

export function sendMessage(data) {
  return request.post('/chat/send', data)
}

const TIMING_PREFIX = '[TIMING]'
const EVENT_PREFIX = '[EVENT]'

/**
 * 流式发送消息（SSE）。
 * onChunk / onTiming / onEvent 同上
 * signal: AbortSignal，用于用户暂停生成
 * 完成后 resolve { content, timing, thoughtSteps, aborted }
 */
export async function sendMessageStream(data, { onChunk, onTiming, onEvent, signal } = {}) {
  const token = localStorage.getItem('token')
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
    signal,
  })

  if (res.status === 401) {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('userId')
    window.location.href = '/login'
    throw new Error('登录已过期')
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }

  if (!res.body) {
    throw new Error('浏览器不支持流式响应')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let full = ''
  let timing = null
  const thoughtSteps = []

  const consumeEvents = (events) => {
    for (const payload of events) {
      if (payload === '[DONE]') {
        return true
      }
      if (payload.startsWith(TIMING_PREFIX)) {
        try {
          timing = JSON.parse(payload.slice(TIMING_PREFIX.length))
          onTiming?.(timing)
        } catch (e) {
          console.warn('Failed to parse timing payload', e)
        }
        continue
      }
      if (payload.startsWith(EVENT_PREFIX)) {
        try {
          const event = JSON.parse(payload.slice(EVENT_PREFIX.length))
          const deduped = dedupeThoughtSteps([...thoughtSteps, event])
          thoughtSteps.length = 0
          thoughtSteps.push(...deduped)
          onEvent?.(event, thoughtSteps)
        } catch (e) {
          console.warn('Failed to parse thought event', e)
        }
        continue
      }
      full += payload
      onChunk?.(payload, full)
    }
    return false
  }

  try {
    while (true) {
      if (signal?.aborted) {
        try {
          await reader.cancel()
        } catch {
          // ignore
        }
        return { content: full, timing, thoughtSteps, aborted: true }
      }
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const parsed = parseSseBuffer(buffer)
      buffer = parsed.remaining
      if (consumeEvents(parsed.events)) {
        return { content: full, timing, thoughtSteps, aborted: false }
      }
    }
  } catch (err) {
    if (err?.name === 'AbortError' || signal?.aborted) {
      try {
        await reader.cancel()
      } catch {
        // ignore
      }
      return { content: full, timing, thoughtSteps, aborted: true }
    }
    throw err
  }

  if (buffer.trim()) {
    const parsed = parseSseBuffer(buffer + '\n\n')
    if (consumeEvents(parsed.events)) {
      return { content: full, timing, thoughtSteps, aborted: false }
    }
  }

  return { content: full, timing, thoughtSteps, aborted: false }
}

export function getSessions(page = 1, size = 20) {
  return request.get('/chat/sessions', { params: { page, size } })
}

export function getMessages(sessionId, page = 1, size = 50) {
  return request.get(`/chat/${sessionId}/messages`, { params: { page, size } })
}

export function deleteSession(sessionId) {
  return request.delete(`/chat/${sessionId}`)
}

export function newSession() {
  return request.post('/chat/new-session')
}
