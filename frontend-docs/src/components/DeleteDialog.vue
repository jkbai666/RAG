<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { KnowledgeDocument } from '../lib/types.ts'

const props = defineProps<{ document: KnowledgeDocument; busy: boolean; error: string }>()
const emit = defineEmits<{ close: []; confirm: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
let opener: HTMLElement | null = null

onMounted(() => {
  opener = window.document.activeElement instanceof HTMLElement ? window.document.activeElement : null
  dialog.value?.showModal()
})
onBeforeUnmount(() => {
  dialog.value?.close()
  queueMicrotask(() => {
    if (opener?.isConnected && !opener.hasAttribute('disabled')) opener.focus()
    else window.document.querySelector<HTMLElement>('#documents-heading')?.focus()
  })
})

function cancel(event?: Event) {
  event?.preventDefault()
  if (!props.busy) emit('close')
}
</script>

<template>
  <dialog ref="dialog" class="delete-dialog" aria-labelledby="delete-title" aria-describedby="delete-description delete-file" :aria-busy="busy" @cancel="cancel">
    <div class="dialog-icon" aria-hidden="true">!</div>
    <h2 id="delete-title">删除这份文档？</h2>
    <p id="delete-file" class="dialog-filename">{{ document.fileName }}</p>
    <p id="delete-description">删除后，将同时移除文档记录、全部分块和检索数据。此操作无法撤销。</p>
    <div v-if="error" class="error-box" role="alert"><strong>删除未完成，记录已保留</strong><p>{{ error }}</p><p>可重试删除，或关闭后稍后再试。</p></div>
    <p v-if="document.status === 'PROCESSING'" class="document-failure" role="alert">文档正在处理中，暂时不能删除。</p>
    <p v-if="busy" class="field-hint" role="status">正在删除文档及索引，请等待结果。完成前暂时无法关闭。</p>
    <div class="dialog-actions">
      <button class="button button-secondary" type="button" autofocus :disabled="busy" @click="cancel()">{{ error ? '暂不重试' : '取消' }}</button>
      <button class="button button-danger" type="button" :disabled="busy || document.status === 'PROCESSING'" @click="emit('confirm')"><span v-if="busy" class="spinner" aria-hidden="true"></span>{{ busy ? '正在删除…' : error ? '重试删除' : '确认删除' }}</button>
    </div>
  </dialog>
</template>