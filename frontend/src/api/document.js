import request from './request'

export function uploadDocument(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function getDocuments(page = 1, size = 10) {
  return request.get('/documents', { params: { page, size } })
}

export function deleteDocument(id) {
  return request.delete(`/documents/${id}`)
}
