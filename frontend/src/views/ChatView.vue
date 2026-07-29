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
        <div class="message-body">
          <div
            v-if="msg.role === 'assistant' && msg.thoughtSteps?.length"
            class="thought-panel"
          >
            <button
              type="button"
              class="thought-toggle"
              @click="msg.showThought = !msg.showThought"
            >
              <span class="thought-chevron">{{ msg.showThought ? '▾' : '▸' }}</span>
              <span>{{ thoughtLabel(msg.thoughtSteps, false, msg.timing?.totalMs ?? msg.durationMs) }}</span>
            </button>
            <ol v-if="msg.showThought" class="thought-steps">
              <li v-for="(step, si) in msg.thoughtSteps" :key="si" :class="[step.type, step.status]">
                <div class="thought-step-label">
                  <span class="thought-step-verb">{{ thoughtStepTitle(step) }}</span>
                  <span v-if="step.repeat > 1" class="thought-repeat">×{{ step.repeat }}</span>
                  <span v-if="thoughtStepDuration(step)" class="thought-step-ms">{{ thoughtStepDuration(step) }}</span>
                </div>
                <div v-if="step.query" class="thought-step-query">{{ step.type === 'tool' ? '' : '查询：' }}{{ step.query }}</div>
                <div v-if="step.detail" class="thought-step-detail">{{ step.detail }}</div>
              </li>
            </ol>
          </div>
          <div class="message-content" v-html="formatContent(msg.content)"></div>
          <div v-if="msg.role === 'assistant' && (msg.durationMs || msg.timing)" class="message-meta">
            <div class="timing-summary">
              耗时 {{ formatDuration(msg.timing?.totalMs ?? msg.durationMs) }}
              <button
                v-if="msg.timing"
                type="button"
                class="timing-toggle"
                @click="msg.showTiming = !msg.showTiming"
              >
                {{ msg.showTiming ? '收起明细' : '查看明细' }}
              </button>
            </div>
            <ul v-if="msg.showTiming && msg.timing" class="timing-details">
              <li
                v-for="(line, i) in formatTimingDetails(msg.timing)"
                :key="i"
                :class="{ emphasize: line.emphasize }"
                :style="{ paddingLeft: (line.level || 0) * 12 + 'px' }"
              >
                <span class="timing-label">{{ line.label }}</span>
                <span class="timing-value">{{ line.value }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
      <div v-if="streaming" class="message assistant">
        <div class="message-avatar">
          <el-avatar :size="36" style="background: #409eff">AI</el-avatar>
        </div>
        <div class="message-body">
          <div class="thought-panel live">
            <button
              type="button"
              class="thought-toggle"
              @click="showLiveThought = !showLiveThought"
            >
              <span class="thought-chevron">{{ showLiveThought ? '▾' : '▸' }}</span>
              <span class="thought-live-label">{{ thoughtLabel(streamThoughtSteps, true, elapsedMs) }}</span>
            </button>
            <ol v-if="showLiveThought" class="thought-steps">
              <li v-for="(step, si) in streamThoughtSteps" :key="si" :class="[step.type, step.status]">
                <div class="thought-step-label">
                  <span class="thought-step-verb">{{ thoughtStepTitle(step) }}</span>
                  <span v-if="step.repeat > 1" class="thought-repeat">×{{ step.repeat }}</span>
                  <span v-if="thoughtStepDuration(step)" class="thought-step-ms">{{ thoughtStepDuration(step) }}</span>
                </div>
                <div v-if="step.query" class="thought-step-query">{{ step.query }}</div>
                <div v-if="step.detail" class="thought-step-detail">{{ step.detail }}</div>
              </li>
              <li v-if="!streamThoughtSteps.length" class="status">
                <div class="thought-step-label">分析问题</div>
                <div class="thought-step-detail">正在理解问题…</div>
              </li>
            </ol>
          </div>
          <div
            v-if="streamContent"
            class="message-content"
            v-html="formatContent(streamContent)"
          ></div>
          <div class="message-meta streaming-meta">
            <span>{{ formatDuration(elapsedMs) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Input area -->
    <div class="input-area">
      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          placeholder="输入法律问题..."
          @keydown.enter.exact.prevent="handleSend"
          resize="none"
          :disabled="sending"
        />
        <div class="composer-actions">
          <el-button class="upload-btn" text @click="uploadVisible = true" :icon="Upload" title="上传文档">
            上传
          </el-button>
          <button
            v-if="!sending"
            type="button"
            class="icon-action-btn send-btn"
            :disabled="!input.trim()"
            title="发送"
            aria-label="发送"
            @click="handleSend"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path
                d="M12 19V5M12 5l-5.5 5.5M12 5l5.5 5.5"
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
          <button
            v-else
            type="button"
            class="icon-action-btn stop-btn"
            title="暂停生成"
            aria-label="暂停生成"
            @click="handleStop"
          >
            <span class="stop-square" aria-hidden="true"></span>
          </button>
        </div>
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
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled, Upload, UploadFilled } from '@element-plus/icons-vue'
import { sendMessageStream, getMessages, newSession } from '../api/chat'
import { getModelConfigs } from '../api/modelConfig'
import { uploadDocument } from '../api/document'
import { formatMessage, formatDuration, formatTimingDetails, thoughtSummaryLabel, extractThoughtSteps, thoughtStepTitle, thoughtStepDuration } from '../utils/formatMessage'

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
const streamThoughtSteps = ref([])
const showLiveThought = ref(true)
const elapsedMs = ref(0)
const messagesRef = ref(null)

let streamTimer = null
let streamStartAt = 0
let activeAbortController = null

const handleStop = () => {
  if (activeAbortController) {
    activeAbortController.abort()
  }
}

const startElapsedTimer = () => {
  streamStartAt = Date.now()
  elapsedMs.value = 0
  stopElapsedTimer()
  streamTimer = window.setInterval(() => {
    elapsedMs.value = Date.now() - streamStartAt
  }, 100)
}

const stopElapsedTimer = () => {
  if (streamTimer != null) {
    clearInterval(streamTimer)
    streamTimer = null
  }
}

const finishElapsed = () => {
  const duration = Date.now() - streamStartAt
  stopElapsedTimer()
  elapsedMs.value = duration
  return duration
}

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
    messages.value = (res.data.records || []).map(m => {
      const rawMeta = m?.metadataJson ?? m?.metadata_json
      const timing = extractStoredTiming(m)
      const thoughtSteps = extractThoughtSteps(rawMeta)
      return {
        id: m.id,
        role: m.role,
        content: m.content,
        timing,
        thoughtSteps,
        durationMs: timing?.totalMs ?? null,
        showTiming: false,
        showThought: false,
      }
    })
    scrollBottom()
  } catch {}
}

/** 从消息 metadataJson 恢复耗时（刷新/切会话后仍可显示） */
const extractStoredTiming = (m) => {
  const raw = m?.metadataJson ?? m?.metadata_json
  if (!raw) return null
  try {
    const meta = typeof raw === 'string' ? JSON.parse(raw) : raw
    return meta?.timing || null
  } catch {
    return null
  }
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
  streaming.value = true
  streamContent.value = ''
  streamThoughtSteps.value = []
  showLiveThought.value = true
  startElapsedTimer()
  await nextTick()
  scrollBottom()

  const abortController = new AbortController()
  activeAbortController = abortController

  let durationMs = 0
  let timing = null
  let thoughtSteps = []
  let aborted = false
  try {
    const result = await sendMessageStream(
      {
        sessionId,
        content,
        modelConfigId: selectedModelId.value,
      },
      {
        signal: abortController.signal,
        onChunk: (_chunk, accumulated) => {
          streamContent.value = accumulated
          scrollBottom()
        },
        onTiming: (t) => {
          timing = t
        },
        onEvent: (_event, steps) => {
          streamThoughtSteps.value = [...steps]
          scrollBottom()
        },
      }
    )
    const full = typeof result === 'string' ? result : result?.content
    timing = timing || (typeof result === 'object' ? result?.timing : null)
    thoughtSteps = (typeof result === 'object' ? result?.thoughtSteps : null) || streamThoughtSteps.value || []
    aborted = !!(typeof result === 'object' && result?.aborted)
    const clientMs = finishElapsed()
    durationMs = timing?.totalMs ?? clientMs
    const answer = full || streamContent.value
    messages.value.push({
      role: 'assistant',
      content: answer
        ? (aborted ? `${answer}\n\n（已暂停生成）` : answer)
        : (aborted ? '（已暂停生成）' : '（无回复）'),
      durationMs,
      timing,
      thoughtSteps,
      showTiming: false,
      showThought: false,
    })
  } catch (err) {
    durationMs = finishElapsed()
    thoughtSteps = streamThoughtSteps.value || []
    aborted = abortController.signal.aborted || err?.name === 'AbortError'
    if (streamContent.value) {
      messages.value.push({
        role: 'assistant',
        content: aborted ? `${streamContent.value}\n\n（已暂停生成）` : streamContent.value,
        durationMs,
        timing,
        thoughtSteps,
        showTiming: !!timing,
        showThought: false,
      })
    } else if (aborted) {
      messages.value.push({
        role: 'assistant',
        content: '（已暂停生成）',
        durationMs,
        timing,
        thoughtSteps,
        showTiming: !!timing,
        showThought: thoughtSteps.length > 0,
      })
    } else {
      messages.value.push({
        role: 'assistant',
        content: '抱歉，请求失败，请稍后重试。',
        durationMs,
        timing,
        thoughtSteps,
        showTiming: !!timing,
        showThought: thoughtSteps.length > 0,
      })
    }
  } finally {
    if (activeAbortController === abortController) {
      activeAbortController = null
    }
    stopElapsedTimer()
    streaming.value = false
    streamContent.value = ''
    streamThoughtSteps.value = []
    elapsedMs.value = 0
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

const formatContent = formatMessage

const thoughtLabel = (steps, streaming, totalMs) =>
  thoughtSummaryLabel(steps, { streaming: !!streaming, totalMs: totalMs ?? null })

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

onUnmounted(() => {
  stopElapsedTimer()
  if (activeAbortController) {
    activeAbortController.abort()
    activeAbortController = null
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
.message-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 100%;
}
.message-meta {
  font-size: 12px;
  color: #909399;
  padding: 0 4px;
  line-height: 1.4;
}
.timing-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.timing-toggle {
  border: none;
  background: transparent;
  color: #409eff;
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}
.timing-toggle:hover {
  text-decoration: underline;
}
.timing-details {
  list-style: none;
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  max-width: 520px;
}
.timing-details li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: #606266;
}
.timing-details li.emphasize {
  color: #303133;
  font-weight: 600;
}
.timing-label {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.timing-value {
  flex-shrink: 0;
  color: #409eff;
}
.message.user .message-meta {
  text-align: right;
}
.streaming-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.thought-panel {
  margin-bottom: 6px;
  max-width: 560px;
}
.thought-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  font-size: 13px;
  padding: 2px 0;
  line-height: 1.4;
}
.thought-toggle:hover {
  color: #374151;
}
.thought-chevron {
  font-size: 11px;
  width: 12px;
  display: inline-block;
}
.thought-live-label {
  color: #6b7280;
  font-style: italic;
}
.thought-panel.live .thought-live-label {
  animation: thoughtPulse 1.4s ease-in-out infinite;
}
@keyframes thoughtPulse {
  0%, 100% { opacity: 0.65; }
  50% { opacity: 1; }
}
.thought-steps {
  list-style: none;
  margin: 6px 0 8px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.thought-steps li {
  padding-left: 10px;
  border-left: 2px solid #d1d5db;
}
.thought-steps li.tool {
  border-left-color: #3b82f6;
}
.thought-steps li.status {
  border-left-color: #9ca3af;
}
.thought-step-label {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.thought-step-verb {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12.5px;
  font-weight: 600;
}
.thought-step-ms {
  margin-left: auto;
  font-size: 11px;
  font-weight: 500;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
}
.thought-steps li.running .thought-step-verb {
  color: #2563eb;
}
.thought-steps li.done .thought-step-verb {
  color: #374151;
}
.thought-repeat {
  font-size: 11px;
  font-weight: 500;
  color: #9ca3af;
}
.thought-step-query {
  margin-top: 2px;
  font-size: 12px;
  color: #2563eb;
  word-break: break-word;
}
.thought-step-detail {
  margin-top: 2px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
  word-break: break-word;
  white-space: pre-wrap;
}
.message-content {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.message-content :deep(strong) {
  font-weight: 600;
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
.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.composer :deep(.el-textarea__inner) {
  border-radius: 12px;
  padding-right: 12px;
  box-shadow: none;
}
.composer-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
}
.upload-btn {
  color: #606266;
}
.icon-action-btn {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: transform 0.12s ease, opacity 0.12s ease, background 0.12s ease;
}
.icon-action-btn:active:not(:disabled) {
  transform: scale(0.96);
}
.send-btn {
  background: #111;
  color: #fff;
}
.send-btn:hover:not(:disabled) {
  background: #000;
}
.send-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.stop-btn {
  background: #111;
  color: #fff;
}
.stop-btn:hover {
  background: #000;
}
.stop-square {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: #fff;
  display: block;
}
</style>
