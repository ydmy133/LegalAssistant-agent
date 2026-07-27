<template>
  <div class="documents-view">
    <div class="page-header">
      <h2>文档管理</h2>
      <el-upload
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="1"
        accept=".pdf,.docx,.doc,.txt,.md"
        :show-file-list="false"
      >
        <el-button type="primary" :icon="Upload">上传文档</el-button>
      </el-upload>
    </div>

    <div v-if="uploadFile" class="upload-bar">
      <span>{{ uploadFile.name }}</span>
      <el-button type="primary" size="small" @click="handleUpload" :loading="uploading">确认上传</el-button>
      <el-button size="small" @click="uploadFile = null">取消</el-button>
    </div>

    <el-table :data="documents" stripe style="width: 100%" v-loading="loading">
      <el-table-column prop="fileName" label="文件名" min-width="200">
        <template #default="{ row }">
          <span>{{ row.fileName }}</span>
          <el-tag v-if="row.isPreset === 1" size="small" type="info" style="margin-left: 8px">法律库</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fileType" label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small">{{ row.fileType.toUpperCase() }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">
          {{ formatSize(row.fileSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="分块数" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status, row)" size="small">
            {{ statusText(row.status, row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">
          {{ row.createTime }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button
            v-if="row.isPreset !== 1"
            text
            type="danger"
            size="small"
            @click="handleDelete(row.id)"
          >
            删除
          </el-button>
          <span v-else class="preset-hint">系统文档</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination" v-if="total > 0">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="loadDocuments"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { getDocuments, deleteDocument, uploadDocument } from '../api/document'

const documents = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const uploadFile = ref(null)
const uploading = ref(false)

const loadDocuments = async () => {
  loading.value = true
  try {
    const res = await getDocuments(currentPage.value, pageSize.value)
    documents.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
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
    ElMessage.success('上传成功')
    uploadFile.value = null
    loadDocuments()
  } finally {
    uploading.value = false
  }
}

const handleDelete = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除此文档？', '提示', { type: 'warning' })
    await deleteDocument(id)
    ElMessage.success('删除成功')
    loadDocuments()
  } catch {}
}

const formatSize = (bytes) => {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const statusText = (s, row) => {
  if (row?.isPreset === 1 && s === -1) return '待向量化'
  return { 0: '处理中', 1: '已处理', '-1': '失败' }[s] || '未知'
}
const statusType = (s, row) => {
  if (row?.isPreset === 1 && s === -1) return 'warning'
  return { 0: 'warning', 1: 'success', '-1': 'danger' }[s] || 'info'
}

onMounted(loadDocuments)
</script>

<style scoped>
.documents-view {
  height: 100%;
  overflow-y: auto;
  padding: 20px;
  background: #f5f5f5;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  font-size: 20px;
  color: #303133;
}
.upload-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #ecf5ff;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 14px;
}
.pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
.preset-hint {
  font-size: 12px;
  color: #909399;
}
</style>
