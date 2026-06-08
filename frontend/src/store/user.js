import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const userId = ref(localStorage.getItem('userId') || '')

  function setToken(val) {
    token.value = val
    localStorage.setItem('token', val)
  }

  function setUsername(val) {
    username.value = val
    localStorage.setItem('username', val)
  }

  function setUserId(val) {
    userId.value = val
    localStorage.setItem('userId', val)
  }

  function logout() {
    token.value = ''
    username.value = ''
    userId.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('userId')
  }

  return { token, username, userId, setToken, setUsername, setUserId, logout }
})
