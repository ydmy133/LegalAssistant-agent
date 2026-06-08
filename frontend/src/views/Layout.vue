<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="sidebar-header">
        <h3>法律助手</h3>
      </div>
      <el-button type="primary" class="new-chat-btn" @click="handleNewChat">
        <el-icon><Plus /></el-icon> 新对话
      </el-button>
      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.sessionId"
          class="session-item"
          :class="{ active: activeSession === session.sessionId }"
          @click="switchSession(session.sessionId)"
        >
          <div class="session-title">{{ session.title }}</div>
          <div class="session-time">{{ formatTime(session.updateTime || session.createTime) }}</div>
          <el-button
            class="delete-btn"
            text
            size="small"
            @click.stop="handleDelete(session.sessionId)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无对话" :image-size="48" />
      </div>
      <div class="sidebar-footer">
        <router-link to="/documents" class="footer-link">
          <el-icon><Folder /></el-icon> 文档管理
        </router-link>
        <router-link to="/settings" class="footer-link">
          <el-icon><Setting /></el-icon> 设置
        </router-link>
      </div>
    </aside>
    <main class="main-content">
      <div class="top-bar">
        <span class="username">{{ userStore.username }}</span>
        <el-button text @click="handleLogout">退出登录</el-button>
      </div>
      <router-view @session-change="loadSessions" :sessions="sessions" :active-session="activeSession" />
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'
import { getSessions, deleteSession, newSession } from '../api/chat'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const sessions = ref([])
const activeSession = ref('')

const loadSessions = async () => {
  try {
    const res = await getSessions(1, 100)
    sessions.value = res.data.records || []
  } catch {}
}

const switchSession = (sessionId) => {
  activeSession.value = sessionId
  router.push(`/chat/${sessionId}`)
}

const handleNewChat = async () => {
  const res = await newSession()
  activeSession.value = res.data.sessionId
  router.push(`/chat/${res.data.sessionId}`)
}

const handleDelete = async (sessionId) => {
  try {
    await ElMessageBox.confirm('确定删除此对话？', '提示', { type: 'warning' })
    await deleteSession(sessionId)
    if (activeSession.value === sessionId) {
      activeSession.value = ''
      router.push('/chat')
    }
    await loadSessions()
  } catch {}
}

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}

const formatTime = (time) => {
  if (!time) return ''
  const d = new Date(time)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

onMounted(() => {
  loadSessions()
  if (route.params.sessionId) {
    activeSession.value = route.params.sessionId
  }
})
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
}
.sidebar {
  width: 260px;
  background: #2c2c2c;
  color: #fff;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid #3c3c3c;
}
.sidebar-header h3 {
  font-size: 18px;
  font-weight: 600;
}
.new-chat-btn {
  margin: 12px;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}
.session-item {
  padding: 12px;
  margin: 2px 0;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  transition: background 0.2s;
}
.session-item:hover {
  background: #3c3c3c;
}
.session-item.active {
  background: #409eff;
}
.session-title {
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding-right: 30px;
}
.session-time {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
.session-item.active .session-time {
  color: rgba(255, 255, 255, 0.7);
}
.delete-btn {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  color: #999;
  opacity: 0;
}
.session-item:hover .delete-btn {
  opacity: 1;
}
.session-item.active .delete-btn {
  color: rgba(255, 255, 255, 0.7);
}
.sidebar-footer {
  padding: 12px;
  border-top: 1px solid #3c3c3c;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.footer-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  color: #ccc;
  text-decoration: none;
  border-radius: 6px;
  font-size: 14px;
  transition: background 0.2s;
}
.footer-link:hover {
  background: #3c3c3c;
  color: #fff;
}
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.top-bar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  gap: 12px;
}
.username {
  font-size: 14px;
  color: #606266;
}
</style>
