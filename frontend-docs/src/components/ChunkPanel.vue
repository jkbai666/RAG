<script setup lang="ts">
import { characterCount, pageLabel, PAGE_SIZE } from '../lib/display.ts'
import type { DocumentChunk, KnowledgeDocument } from '../lib/types.ts'

defineProps<{
  document: KnowledgeDocument | null
  note: string
  chunks: DocumentChunk[]
  total: number
  page: number
  totalPages: number
  loading: boolean
  error: string
}>()
const emit = defineEmits<{ load: [page: number] }>()
</script>

<template>
  <section class="panel chunks-panel" aria-labelledby="chunks-heading" :aria-busy="loading">
    <div class="panel-heading chunk-heading">
      <div><p class="eyebrow">实际索引内容</p><h2 id="chunks-heading">分块详情</h2></div>
      <span class="quiet-label">每页 {{ PAGE_SIZE }} 块</span>
    </div>
    <div v-if="!document" class="empty-state chunk-placeholder">
      <span class="empty-document" aria-hidden="true"><svg viewBox="0 0 48 56" fill="none"><path d="M10 3h20l11 11v37H10z"/><path d="M29 3v13h12M17 26h17M17 33h17M17 40h10"/></svg></span>
      <h3>选择一份就绪文档</h3>
      <p>{{ note || '在文档列表中点击“查看分块”，核对实际参与检索的全文。' }}</p>
      <span class="subtle-note">按原文顺序展示 · 不截断正文</span>
    </div>
    <template v-else>
      <div class="selected-summary">
        <h3 class="file-name">{{ document.fileName }}</h3>
        <p>共 {{ total }} 块 <span aria-hidden="true">/</span> 仅展示后端保存的实际分块，不重新切分</p>
      </div>
      <div v-if="loading" class="empty-state" role="status"><span class="spinner large-spinner" aria-hidden="true"></span><h3>正在读取第 {{ page }} 页</h3><p>正在获取分块全文。</p></div>
      <div v-else-if="error" class="error-box chunk-error" role="alert">
        <strong>分块加载失败</strong><p>{{ error }}</p>
        <button class="button button-secondary" type="button" @click="emit('load', page)">重试加载分块</button>
      </div>
      <div v-else-if="!chunks.length" class="empty-state">
        <span class="empty-icon" aria-hidden="true">—</span>
        <h3>{{ total ? '这一页没有分块' : '这份文档暂无分块' }}</h3>
        <p>服务端未返回分块正文。可重新加载，或检查文档处理结果。</p>
        <button class="button button-secondary" type="button" @click="emit('load', page)">重新加载分块</button>
      </div>
      <ol v-else class="chunk-list" aria-label="分块全文" :start="chunks[0]?.index">
        <li v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
          <div class="chunk-card-heading">
            <h3><span class="chunk-hash" aria-hidden="true">#</span> 分块 {{ chunk.index }}</h3>
            <p>{{ characterCount(chunk.text) }} 字符 <span aria-hidden="true">·</span> {{ pageLabel(chunk) }}</p>
          </div>
          <pre class="chunk-text">{{ chunk.text }}</pre>
        </li>
      </ol>
      <nav class="pagination" aria-label="分块分页">
        <p role="status">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 块</p>
        <div class="pagination-buttons">
          <button class="button button-secondary" type="button" :disabled="loading || page <= 1" :aria-label="page <= 1 ? '上一页，当前已是第一页' : '上一页'" @click="emit('load', page - 1)">← 上一页</button>
          <button class="button button-secondary" type="button" :disabled="loading || page >= totalPages" :aria-label="page >= totalPages ? '下一页，当前已是最后一页' : '下一页'" @click="emit('load', page + 1)">下一页 →</button>
        </div>
      </nav>
    </template>
  </section>
</template>