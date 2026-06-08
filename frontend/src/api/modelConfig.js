import request from './request'

export function getModelConfigs() {
  return request.get('/model-configs')
}

export function createModelConfig(data) {
  return request.post('/model-configs', data)
}

export function updateModelConfig(id, data) {
  return request.put(`/model-configs/${id}`, data)
}

export function deleteModelConfig(id) {
  return request.delete(`/model-configs/${id}`)
}
