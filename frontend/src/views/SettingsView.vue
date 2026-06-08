<template>
  <div class="settings-view">
    <div class="page-header">
      <h2>模型设置</h2>
      <el-button type="primary" :icon="Plus" @click="openAddDialog">添加模型</el-button>
    </div>

    <div class="model-cards">
      <el-card v-for="model in models" :key="model.id" class="model-card" shadow="hover">
        <div class="card-header">
          <span class="provider">{{ model.providerName }}</span>
          <el-tag v-if="model.isDefault === 1" size="small" type="success">默认</el-tag>
        </div>
        <div class="card-body">
          <div><strong>模型:</strong> {{ model.modelName }}</div>
          <div><strong>API Key:</strong> {{ maskKey(model.apiKey) }}</div>
          <div v-if="model.baseUrl"><strong>端点:</strong> {{ model.baseUrl }}</div>
        </div>
        <div class="card-actions">
          <el-button text size="small" @click="openEditDialog(model)">编辑</el-button>
          <el-button text size="small" type="danger" @click="handleDelete(model.id)">删除</el-button>
        </div>
      </el-card>
    </div>

    <el-empty v-if="models.length === 0 && !loading" description="暂无模型配置，请添加模型" />

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editingModel ? '编辑模型' : '添加模型'" width="480px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="提供商" prop="providerName">
          <el-select v-model="form.providerName" placeholder="选择提供商" @change="onProviderChange">
            <el-option
              v-for="p in providers"
              :key="p.name"
              :label="p.name"
              :value="p.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="form.modelName" placeholder="如 gpt-4o-mini, deepseek-chat" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="form.apiKey" type="password" placeholder="输入API Key" show-password />
        </el-form-item>
        <el-form-item label="API 端点">
          <el-input v-model="form.baseUrl" placeholder="留空使用默认端点" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">
          {{ editingModel ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getModelConfigs, createModelConfig, updateModelConfig, deleteModelConfig } from '../api/modelConfig'

const providers = [
  { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1' },
  { name: 'Zhipu', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { name: 'Moonshot', baseUrl: 'https://api.moonshot.cn/v1' },
  { name: 'Tongyi', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { name: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1' },
  { name: '自定义', baseUrl: '' },
]

const models = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingModel = ref(null)
const saving = ref(false)
const formRef = ref(null)

const form = reactive({
  providerName: '',
  modelName: '',
  apiKey: '',
  baseUrl: '',
  isDefault: 0,
})

const rules = {
  providerName: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  apiKey: [{ required: true, message: '请输入API Key', trigger: 'blur' }],
}

const loadModels = async () => {
  loading.value = true
  try {
    const res = await getModelConfigs()
    models.value = res.data || []
  } finally {
    loading.value = false
  }
}

const openAddDialog = () => {
  editingModel.value = null
  form.providerName = ''
  form.modelName = ''
  form.apiKey = ''
  form.baseUrl = ''
  form.isDefault = 0
  dialogVisible.value = true
}

const openEditDialog = (model) => {
  editingModel.value = model
  form.providerName = model.providerName
  form.modelName = model.modelName
  form.apiKey = model.apiKey
  form.baseUrl = model.baseUrl || ''
  form.isDefault = model.isDefault
  dialogVisible.value = true
}

const onProviderChange = (name) => {
  const p = providers.find(p => p.name === name)
  if (p && p.baseUrl) {
    form.baseUrl = p.baseUrl
  }
  if (name === 'DeepSeek' && !form.modelName) {
    form.modelName = 'deepseek-chat'
  } else if (name === 'OpenAI' && !form.modelName) {
    form.modelName = 'gpt-4o-mini'
  } else if (name === 'Zhipu' && !form.modelName) {
    form.modelName = 'glm-4-flash'
  } else if (name === 'Moonshot' && !form.modelName) {
    form.modelName = 'moonshot-v1-8k'
  } else if (name === 'Tongyi' && !form.modelName) {
    form.modelName = 'qwen-plus'
  }
}

const handleSave = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingModel.value) {
      await updateModelConfig(editingModel.value.id, { ...form })
      ElMessage.success('更新成功')
    } else {
      await createModelConfig({ ...form })
      ElMessage.success('添加成功')
    }
    dialogVisible.value = false
    loadModels()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除此模型配置？', '提示', { type: 'warning' })
    await deleteModelConfig(id)
    ElMessage.success('删除成功')
    loadModels()
  } catch {}
}

const maskKey = (key) => {
  if (!key) return ''
  if (key.length <= 8) return '****'
  return key.substring(0, 4) + '****' + key.substring(key.length - 4)
}

onMounted(loadModels)
</script>

<style scoped>
.settings-view {
  height: 100%;
  overflow-y: auto;
  padding: 20px;
  background: #f5f5f5;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  font-size: 20px;
  color: #303133;
}
.model-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.model-card {
  border-radius: 8px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.provider {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.card-body {
  font-size: 14px;
  color: #606266;
  line-height: 2;
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}
</style>
