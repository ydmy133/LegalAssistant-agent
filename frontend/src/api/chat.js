import request from './request'

export function sendMessage(data) {
  return request.post('/chat/send', data)
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
