<script setup lang="ts">
import { formatDate, formatSize } from '../lib/display.ts'
import type { KnowledgeDocument } from '../lib/types.ts'

defineProps<{
  documents: KnowledgeDocument[]
  loading: boolean
  loaded: boolean
  error: string
  pollActive: boolean
  selectedId?: string
  deletingId: string | null
  deleteErrors: Record<string, string>
}>()
const emit = defineEmits<{ refresh: []; select: [doc: KnowledgeDocument]; delete: [doc: KnowledgeDocument] }>()
const statusText = { READY: '就绪', PROCESSING: '处理中', FAILED: '失败' }
</script>

<template>
  <section class="panel documents-panel" aria-labelledby="documents-heading">
    <div class="panel-heading">
      <div class="heading-with-count">
        <h2 id="documents-heading" tabindex="-1">文档列表</h2>
        <span class="count" :aria-label="`${documents.length} 份文档`">{{ documents.length }}</span>
      </div>
      <button class="button button-subtle" type="button" :disabled="loading" @click="emit('refresh')">
        <span :class="{ spinner: loading }" aria-hidden="true">{{ loading ? '' : '↻' }}</span>
        {{ loading ? '更新中…' : '刷新列表' }}
      </button>
    </div>
    <p class="panel-caption">
      按导入时间倒序
      <span v-if="pollActive" class="poll-label"><span class="status-dot" aria-hidden="true"></span>每 1.5 秒更新处理状态</span>
    </p>
    <div v-if="error" class="error-box list-error" role="alert">
      <strong>文档列表更新失败</strong>
      <p>{{ error }}</p>
      <p v-if="documents.length" class="field-hint">下方保留上次已读取的记录，状态可能尚未更新。</p>
      <button class="button button-secondary" type="button" :disabled="loading" @click="emit('refresh')">重试加载列表</button>
    </div>
    <div v-if="loading && !loaded && !documents.length" class="empty-state" role="status">
      <span class="spinner large-spinner" aria-hidden="true"></span>
      <h3>正在读取文档</h3>
      <p>请稍候，正在连接知识库。</p>
    </div>
    <div v-else-if="!error && !loading && !documents.length" class="empty-state">
      <span class="empty-icon" aria-hidden="true">＋</span>
      <h3>还没有文档</h3>
      <p>从上方上传第一份资料，处理完成后即可查看分块。</p>
    </div>
    <ul v-if="documents.length" class="document-list" aria-label="已导入的文档">
      <li v-for="doc in documents" :key="doc.id" class="document-card" :class="{ 'is-selected': doc.id === selectedId }">
        <div class="document-topline">
          <span class="format-label">{{ doc.format.toUpperCase() }}</span>
          <span class="status-badge" :class="`status-${doc.status.toLowerCase()}`"><span class="status-dot" aria-hidden="true"></span>{{ statusText[doc.status] }}</span>
        </div>
        <h3 class="file-name">{{ doc.fileName }}</h3>
        <dl class="document-meta">
          <div><dt class="sr-only">文件大小</dt><dd>{{ formatSize(doc.sizeBytes) }}</dd></div>
          <div><dt class="sr-only">分块数量</dt><dd>{{ doc.chunkCount }} 块</dd></div>
          <div class="document-time"><dt class="sr-only">导入时间</dt><dd><time :datetime="doc.createdAt">{{ formatDate(doc.createdAt) }}</time></dd></div>
        </dl>
        <p v-if="doc.status === 'PROCESSING'" :id="`processing-${doc.id}`" class="processing-hint">正在提取文本并建立索引，完成前无法查看分块或删除。</p>
        <p v-else-if="doc.status === 'FAILED'" :id="`failed-${doc.id}`" class="document-failure"><strong>失败原因：</strong>{{ doc.errorMessage || '服务端未提供具体原因，请检查后端服务。' }} 该文档不可查看分块。</p>
        <p v-if="deleteErrors[doc.id]" class="document-failure" role="alert"><strong>删除未完成，记录已保留：</strong>{{ deleteErrors[doc.id] }} 可再次删除。</p>
        <div class="document-actions">
          <button
            class="button button-secondary view-button" :class="{ 'button-selected': doc.id === selectedId }" type="button"
            :disabled="doc.status !== 'READY' || deletingId === doc.id" :aria-pressed="doc.id === selectedId"
            :aria-label="`查看 ${doc.fileName} 的分块`" :aria-describedby="doc.status === 'PROCESSING' ? `processing-${doc.id}` : doc.status === 'FAILED' ? `failed-${doc.id}` : undefined"
            @click="emit('select', doc)"
          >{{ doc.id === selectedId ? '正在查看' : '查看分块' }}<span aria-hidden="true">↗</span></button>
          <button
            class="button button-danger-text" type="button" :disabled="doc.status === 'PROCESSING' || deletingId !== null"
            :aria-label="`删除 ${doc.fileName}`" :aria-describedby="doc.status === 'PROCESSING' ? `processing-${doc.id}` : undefined"
            @click="emit('delete', doc)"
          >{{ deletingId === doc.id ? '删除中…' : deleteErrors[doc.id] ? '再次删除' : '删除' }}</button>
        </div>
      </li>
    </ul>
  </section>
</template>