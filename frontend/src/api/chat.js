import request from './request'
import { parseSseBuffer } from '../utils/formatMessage'

export function sendMessage(data) {
  return request.post('/chat/send', data)
}

/**
 * 流式发送消息（SSE）。onChunk 每次收到增量文本时回调；完成后 resolve 完整正文。
 */
export async function sendMessageStream(data, { onChunk } = {}) {
  const token = localStorage.getItem('token')
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
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

  const consumeEvents = (events) => {
    for (const payload of events) {
      if (payload === '[DONE]') {
        return true
      }
      full += payload
      onChunk?.(payload, full)
    }
    return false
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const parsed = parseSseBuffer(buffer)
    buffer = parsed.remaining
    if (consumeEvents(parsed.events)) {
      return full
    }
  }

  // 流结束：处理剩余缓冲区（可能没有结尾空行）
  if (buffer.trim()) {
    const parsed = parseSseBuffer(buffer + '\n\n')
    if (consumeEvents(parsed.events)) {
      return full
    }
  }

  return full
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
