<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatSize, validateFile } from '../lib/display.ts'

const props = defineProps<{ uploading: boolean; error: string; resetVersion: number }>()
const emit = defineEmits<{ upload: [file: File]; clearError: [] }>()
const input = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const validationError = ref('')
const disabled = computed(() => props.uploading || !file.value || Boolean(validationError.value))

function chooseFile(event: Event) {
  const files = (event.target as HTMLInputElement).files
  file.value = files?.item(0) ?? null
  validationError.value = files && files.length > 1 ? '每次只能上传一个文件，请重新选择。' : file.value ? validateFile(file.value) : ''
  emit('clearError')
}

function submit() {
  if (props.uploading) return
  validationError.value = validateFile(file.value)
  if (validationError.value || !file.value) return
  emit('upload', file.value)
}

watch(() => props.resetVersion, () => {
  file.value = null
  validationError.value = ''
  if (input.value) input.value.value = ''
})
</script>

<template>
  <section class="upload-panel" aria-labelledby="upload-heading" :aria-busy="uploading">
    <div class="upload-intro">
      <span class="section-step" aria-hidden="true">01</span>
      <div>
        <h2 id="upload-heading">添加文档</h2>
        <p id="upload-help">单个 TXT / Markdown / PDF，不超过 20 MiB</p>
      </div>
    </div>
    <form class="upload-form" @submit.prevent="submit">
      <div class="file-field">
        <label for="document-file" class="sr-only">选择要上传的文档</label>
        <div class="file-picker">
          <input
            id="document-file" ref="input" type="file" accept=".txt,.md,.pdf"
            :disabled="uploading" :aria-invalid="Boolean(validationError || error)"
            aria-describedby="upload-help file-feedback upload-error" @change="chooseFile"
          />
          <span class="file-picker-button" aria-hidden="true">选择文件</span>
          <span class="file-picker-label" aria-hidden="true">{{ file ? '已选择 1 个文件' : '未选择文件' }}</span>
        </div>
        <p id="file-feedback" class="field-hint" aria-live="polite">
          <template v-if="uploading">正在上传，请勿重复提交；完成后会自动处理。</template>
          <template v-else-if="file">{{ file.name }} · {{ formatSize(file.size) }}</template>
          <template v-else>请先选择文件。PDF 需含可提取的文本层，不支持扫描件。</template>
        </p>
      </div>
      <button class="button button-primary upload-button" type="submit" :disabled="disabled" aria-describedby="file-feedback upload-error">
        <span v-if="uploading" class="spinner" aria-hidden="true"></span>
        <span v-else aria-hidden="true">↑</span>
        {{ uploading ? '正在上传…' : error ? '重新上传' : '上传文档' }}
      </button>
    </form>
    <div v-if="validationError || error" id="upload-error" class="inline-error upload-error" role="alert">
      {{ validationError || error }}
    </div>
  </section>
</template>