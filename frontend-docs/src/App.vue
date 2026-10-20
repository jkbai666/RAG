<script setup lang="ts">
import ChunkPanel from './components/ChunkPanel.vue'
import DeleteDialog from './components/DeleteDialog.vue'
import DocumentList from './components/DocumentList.vue'
import UploadPanel from './components/UploadPanel.vue'
import { useDocuments } from './composables/useDocuments.ts'
import { getChatUrl } from './lib/display.ts'

const chatUrl = getChatUrl(import.meta.env.VITE_CHAT_URL)
const {
  documents, listLoading, listLoaded, listError, pollActive,
  selectedDocument, selectionNote, chunks, chunkTotal, chunkPage, chunkLoading, chunkError, totalPages,
  uploading, uploadError, uploadVersion, notice, deleteTarget, deletingId, deleteErrors,
  refreshDocuments, selectDocument, loadChunks, uploadDocument, openDelete, closeDelete, confirmDelete,
} = useDocuments()
</script>

<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <header class="site-header">
    <div class="header-inner">
      <div class="brand"><span class="brand-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M6 3h8l4 4v14H6z"/><path d="M13 3v5h5M9 12h6M9 16h6"/></svg></span><span>本地知识库<span class="brand-divider" aria-hidden="true">/</span><span class="brand-section">文档管理</span></span></div>
      <a class="chat-link" :href="chatUrl" target="_blank" rel="noopener noreferrer" aria-label="前往知识库问答，在新标签页打开">前往知识库问答<span aria-hidden="true">↗</span></a>
    </div>
  </header>
  <main id="main-content" class="main-container">
    <section class="page-intro" aria-labelledby="page-title">
      <div><p class="eyebrow">资料与索引</p><h1 id="page-title">文档管理<span class="title-dot" aria-hidden="true">.</span></h1><p class="intro-description">导入资料，查看分块，让问答有据可查。</p></div>
      <p class="local-note"><span class="status-dot" aria-hidden="true"></span>本机使用 · 单一知识库</p>
    </section>
    <UploadPanel :uploading="uploading" :error="uploadError" :reset-version="uploadVersion" @clear-error="uploadError = ''" @upload="uploadDocument" />
    <p v-if="notice" class="success-notice" role="status"><span aria-hidden="true">✓</span>{{ notice }}</p>
    <div class="workspace">
      <DocumentList
        :documents="documents" :loading="listLoading" :loaded="listLoaded" :error="listError" :poll-active="pollActive"
        :selected-id="selectedDocument?.id" :deleting-id="deletingId" :delete-errors="deleteErrors"
        @refresh="refreshDocuments" @select="selectDocument" @delete="openDelete"
      />
      <ChunkPanel
        :document="selectedDocument" :note="selectionNote" :chunks="chunks" :total="chunkTotal" :page="chunkPage"
        :total-pages="totalPages" :loading="chunkLoading" :error="chunkError" @load="loadChunks"
      />
    </div>
    <footer class="page-footer"><span>支持 TXT、Markdown 与文本型 PDF</span><span>文档处理与索引由后端完成</span></footer>
  </main>
  <DeleteDialog v-if="deleteTarget" :document="deleteTarget" :busy="deletingId !== null" :error="deleteErrors[deleteTarget.id] || ''" @close="closeDelete" @confirm="confirmDelete" />
</template>