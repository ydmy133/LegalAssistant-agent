<template>
  <div class="chat-view">
    <!-- Model selector -->
    <div class="model-bar">
      <el-select v-model="selectedModelId" placeholder="请选择模型" size="small" style="width: 260px">
        <el-option
          v-for="m in models"
          :key="m.id"
          :label="`${m.providerName} - ${m.modelName}`"
          :value="m.id"
        />
      </el-select>
      <span class="model-hint" v-if="models.length === 0">
        请先在<router-link to="/settings">设置</router-link>中配置模型
      </span>
    </div>

    <!-- Messages -->
    <div class="messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="welcome">
        <h2>法律助手在线</h2>
        <p>请输入您的问题，我将为您检索法律知识和判例</p>
      </div>
      <div
        v-for="(msg, idx) in messages"
        :key="idx"
        class="message"
        :class="msg.role"
      >
        <div class="message-avatar">
          <el-avatar v-if="msg.role === 'user'" :icon="UserFilled" :size="36" />
          <el-avatar v-else :size="36" style="background: #409eff">AI</el-avatar>
        </div>
        <div class="message-content" v-html="formatContent(msg.content)"></div>
      </div>
      <div v-if="streaming" class="message assistant">
        <div class="message-avatar">
          <el-avatar :size="36" style="background: #409eff">AI</el-avatar>
        </div>
        <div class="message-content" v-html="formatContent(streamContent)"></div>
      </div>
    </div>

    <!-- Input area -->
    <div class="input-area">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        placeholder="输入法律问题..."
        @keydown.enter.exact="handleSend"
        resize="none"
      />
      <div class="input-actions">
        <el-button @click="uploadVisible = true" :icon="Upload">
          上传文档
        </el-button>
        <el-button type="primary" @click="handleSend" :loading="sending" :disabled="!input.trim()">
          发送
        </el-button>
      </div>
    </div>

    <!-- Upload dialog -->
    <el-dialog v-model="uploadVisible" title="上传法律文档" width="420px">
      <el-upload
        drag
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="1"
        accept=".pdf,.docx,.doc,.txt,.md"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处 或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 PDF/DOCX/DOC/TXT/MD 文件</div>
        </template>
      </el-upload>
      <div style="margin-top: 12px">{{ uploadFile?.name }}</div>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" @click="handleUpload" :loading="uploading" :disabled="!uploadFile">
          确认上传
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled, Upload, UploadFilled } from '@element-plus/icons-vue'
import { sendMessage, getMessages, newSession } from '../api/chat'
import { getModelConfigs } from '../api/modelConfig'
import { uploadDocument } from '../api/document'

const route = useRoute()
const router = useRouter()
const props = defineProps(['sessions', 'activeSession'])
const emit = defineEmits(['sessionChange'])

const models = ref([])
const selectedModelId = ref(null)
const messages = ref([])
const input = ref('')
const sending = ref(false)
const streaming = ref(false)
const streamContent = ref('')
const messagesRef = ref(null)

// Upload
const uploadVisible = ref(false)
const uploadFile = ref(null)
const uploading = ref(false)

const loadModels = async () => {
  try {
    const res = await getModelConfigs()
    models.value = res.data || []
    if (models.value.length > 0) {
      const def = models.value.find(m => m.isDefault === 1)
      selectedModelId.value = def ? def.id : models.value[0].id
    }
  } catch {}
}

const loadMessages = async (sessionId) => {
  if (!sessionId) return
  try {
    const res = await getMessages(sessionId)
    messages.value = (res.data.records || []).map(m => ({
      role: m.role,
      content: m.content,
    }))
    scrollBottom()
  } catch {}
}

const handleSend = async () => {
  const content = input.value.trim()
  if (!content || sending.value) return

  let sessionId = props.activeSession || route.params.sessionId
  if (!sessionId) {
    const res = await newSession()
    sessionId = res.data.sessionId
    router.push(`/chat/${sessionId}`)
  }

  messages.value.push({ role: 'user', content })
  input.value = ''
  sending.value = true
  await nextTick()
  scrollBottom()

  try {
    const res = await sendMessage({
      sessionId,
      content,
      modelConfigId: selectedModelId.value,
    })
    messages.value.push({ role: 'assistant', content: res.data.response })
  } catch {
    messages.value.push({ role: 'assistant', content: '抱歉，请求失败，请稍后重试。' })
  } finally {
    sending.value = false
    await nextTick()
    scrollBottom()
    emit('sessionChange')
  }
}

const handleFileChange = (file) => {
  uploadFile.value = file.raw
}

const handleUpload = async () => {
  if (!uploadFile.value) return
  uploading.value = true
  try {
    await uploadDocument(uploadFile.value)
    ElMessage.success('文档上传成功，已加入知识库')
    uploadVisible.value = false
    uploadFile.value = null
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const formatContent = (text) => {
  if (!text) return ''
  return text.replace(/\n/g, '<br>')
}

const scrollBottom = () => {
  nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(() => route.params.sessionId, (newId) => {
  if (newId) {
    loadMessages(newId)
  } else {
    messages.value = []
  }
})

onMounted(() => {
  loadModels()
  if (route.params.sessionId) {
    loadMessages(route.params.sessionId)
  }
})
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f5f5;
}
.model-bar {
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  gap: 12px;
}
.model-hint {
  font-size: 13px;
  color: #909399;
}
.model-hint a {
  color: #409eff;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}
.welcome {
  text-align: center;
  margin-top: 120px;
  color: #909399;
}
.welcome h2 {
  font-size: 24px;
  color: #303133;
  margin-bottom: 8px;
}
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  max-width: 80%;
}
.message.user {
  margin-left: auto;
  flex-direction: row-reverse;
}
.message.assistant {
  margin-right: auto;
}
.message-content {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
}
.message.user .message-content {
  background: #409eff;
  color: #fff;
}
.message.assistant .message-content {
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.input-area {
  padding: 16px 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}
.input-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
</style>
