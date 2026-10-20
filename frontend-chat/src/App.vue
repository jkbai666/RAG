<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { createChatSession, documentsUrl, pageLabel, QUESTION_LIMIT, statusBlockReason, unicodeLength, validateQuestion } from './chat-session.ts'

const session = createChatSession()
const { state } = session
const documentsLink = documentsUrl(import.meta.env.VITE_DOCUMENTS_URL)
const textarea = ref<HTMLTextAreaElement | null>(null)
const latestMessage = ref<HTMLElement | null>(null)
const composing = ref(false)
const characterCount = computed(() => unicodeLength(state.draft))
const validation = computed(() => characterCount.value > QUESTION_LIMIT ? validateQuestion(state.draft) : '')
const statusHint = computed(() => statusBlockReason(state))
const disabled = computed(() => state.sending || !!statusHint.value || !!validateQuestion(state.draft))
const lastMessage = computed(() => state.messages.at(-1))
const retrying = computed(() => lastMessage.value?.phase === 'failed')
const liveMessage = computed(() => {
  if (state.sending) return '正在检索并生成回答，请稍候。'
  if (lastMessage.value?.phase === 'failed') return `发送失败：${lastMessage.value.error} 输入已保留，可以修改后重新发送。`
  if (lastMessage.value?.phase === 'answered') return lastMessage.value.response?.grounded
    ? '回答已生成，可以展开查看实际引用的来源原文。'
    : '未找到足够依据。回答已显示，这不是服务错误。'
  return ''
})

async function submit(): Promise<void> {
  if (disabled.value) return
  const task = session.send()
  await nextTick()
  latestMessage.value?.scrollIntoView({ block: 'nearest' })
  const success = await task
  if (success) {
    await nextTick()
    textarea.value?.focus({ preventScroll: true })
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || composing.value || event.keyCode === 229) return
  event.preventDefault()
  void submit()
}

function refreshOnFocus(): void {
  void session.refreshStatus()
}

onMounted(() => {
  void session.initialize()
  void session.refreshStatus()
  window.addEventListener('focus', refreshOnFocus)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshOnFocus)
  session.dispose()
})
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#question">跳至问题输入</a>
    <header class="app-header">
      <div class="brand">
        <span class="brand-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4 5.5h6a3 3 0 0 1 2 1 3 3 0 0 1 2-1h6V19h-6a3 3 0 0 0-2 1 3 3 0 0 0-2-1H4z"/><path d="M12 6.5V20M7 9h2M7 12h2M15 9h2M15 12h2"/></svg>
        </span>
        <span>知识库问答<span class="brand-caption">让回答回到文档</span></span>
      </div>
      <button class="text-button new-conversation" data-testid="new-conversation" type="button" :disabled="state.sending || state.sessionLoading" @click="session.newConversation">新对话</button>
      <a class="documents-link" :href="documentsLink" target="_blank" rel="noopener noreferrer" aria-label="打开文档管理（新标签页）">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H5v18h14V8zM14 3v5h5M8 12h8M8 16h5"/></svg>
        <span>文档管理</span>
        <svg class="external-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5H5v14h14v-4M13 5h6v6M19 5l-9 9"/></svg>
      </a>
    </header>

    <main id="main-content" class="main-content">
      <section class="knowledge-status" aria-label="知识库状态" :aria-busy="state.statusLoading">
        <div class="status-copy" aria-live="polite" aria-atomic="true">
          <span class="status-dot" :class="{ ready: state.status && !state.statusLoading && !state.statusError && state.status.readyDocuments > 0 && state.status.modelConfigured }" aria-hidden="true"></span>
          <div>
            <p v-if="state.statusLoading" class="status-title">正在获取知识库状态…</p>
            <p v-else-if="state.statusError" class="status-title">知识库连接异常</p>
            <p v-else-if="state.status" class="status-title">
              <strong>{{ state.status.readyDocuments }}</strong> 份文档可用于问答
              <span class="status-total"> / 共 {{ state.status.totalDocuments }} 份</span>
            </p>
            <p v-else class="status-title">等待连接知识库</p>
            <p class="status-description">{{ state.statusLoading ? '请稍候，确认状态后即可提问。' : '切回本页时会重新检查文档与模型状态。' }}</p>
          </div>
        </div>
        <span v-if="state.status && !state.statusLoading && !state.statusError" class="status-tag" :class="{ warning: !state.status.modelConfigured }">
          {{ state.status.modelConfigured ? '模型已配置' : '模型未配置' }}
        </span>
      </section>

      <div class="status-notices" aria-live="polite" aria-atomic="true">
        <section v-if="state.sessionLoading" class="notice" data-testid="session-loading"><p>正在恢复或创建会话…</p></section>
        <section v-if="state.sessionError" class="notice notice-error" data-testid="session-error" aria-label="会话恢复失败">
          <div><h2>会话暂不可用</h2><p>{{ state.sessionError }}</p><p>已保留当前会话标识，不会自动创建新会话。</p></div>
          <button class="text-button" data-testid="restore-conversation" type="button" :disabled="state.sessionLoading || state.sending" @click="session.initialize(true)">重试恢复会话</button>
        </section>
        <section v-if="state.statusError" class="notice notice-error" aria-label="状态获取失败">
          <div><h2>暂时无法确认知识库状态</h2><p>{{ state.statusError }}</p></div>
          <button class="text-button" type="button" :disabled="state.statusLoading" @click="session.refreshStatus">重试获取状态</button>
        </section>
        <section v-if="state.status && !state.statusError && !state.status.modelConfigured" class="notice notice-warning" aria-label="模型配置提示">
          <div><h2>模型尚未配置，暂不能发送问题</h2><p>请在后端配置生成模型和向量模型。配置完成后，切回本页即可重新检查。</p></div>
        </section>
        <section v-if="state.status && !state.statusError && state.status.readyDocuments === 0" class="notice notice-empty" aria-label="空知识库引导">
          <div><h2>{{ state.status.totalDocuments === 0 ? '请先导入一份文档' : '还没有处理完成的文档' }}</h2><p>{{ state.status.totalDocuments === 0 ? '在文档管理中导入 TXT、MD 或文本型 PDF，处理完成后回来提问。' : '请在文档管理中查看处理状态，等待文档就绪；处理失败时请检查原因。' }}</p></div>
          <a class="text-button" :href="documentsLink" target="_blank" rel="noopener noreferrer" aria-label="前往导入文档（新标签页）">前往文档管理 <span aria-hidden="true">↗</span></a>
        </section>
      </div>

      <section v-if="state.messages.length === 0" class="welcome" aria-labelledby="welcome-title">
        <div class="welcome-icon" aria-hidden="true"><svg viewBox="0 0 32 32"><path d="M9 8h14v13H13l-5 4V9a1 1 0 0 1 1-1zM12 13h8M12 17h5"/><path d="M24 3v5M21.5 5.5h5"/></svg></div>
        <p class="eyebrow">你的文档 · 你的知识库</p>
        <h1 id="welcome-title">从文档出发，<br class="mobile-break" />找到有依据的回答。</h1>
        <p class="welcome-description">用自然语言提问，回答会附上实际引用的来源。<br />展开原文，了解每一个答案的依据。</p>
        <div class="welcome-note"><span aria-hidden="true">↳</span> 当前会话会参考最近 5 轮问答，预算不足时会减少。</div>
      </section>

      <section v-else class="conversation" aria-labelledby="conversation-title" :aria-busy="state.sending">
        <div class="conversation-heading"><h1 id="conversation-title">当前会话</h1><span>最多参考最近 5 轮问答</span></div>
        <article v-for="(message, index) in state.messages" :key="message.id" class="exchange" :aria-label="`第 ${index + 1} 轮问答`">
          <div class="question-message"><p class="message-label">你的问题</p><p class="question-text">{{ message.question }}</p></div>
          <div class="answer-message">
            <div class="assistant-label"><span class="assistant-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="m12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4z"/></svg></span><span>知识库回答</span></div>
            <div v-if="message.phase === 'pending'" class="pending-answer"><span class="spinner" aria-hidden="true"></span><div><p>正在检索并生成回答</p><p class="subtle">请稍候，生成完成后会一次性显示回答与来源。</p></div></div>
            <div v-else-if="message.phase === 'failed'" class="answer-error"><h2>这次未能获取回答</h2><p>{{ message.error }}</p><p v-if="message === lastMessage" class="error-guidance">输入已保留，可以修改后点击下方「重新发送」。</p><p v-else class="error-guidance">本次请求失败，未生成回答。</p></div>
            <template v-else-if="message.response">
              <span class="answer-badge" :class="{ ungrounded: !message.response.grounded }">{{ message.response.grounded ? '有文档依据' : '未找到足够依据' }}</span>
              <p class="answer-text">{{ message.response.answer }}</p>
              <p v-if="!message.response.grounded" class="ungrounded-note">当前知识库没有足够依据回答这个问题。这不是服务错误；可以补充相关文档，或调整问题后再提问。</p>
              <details v-if="message.response.sources.length" class="sources">
                <summary><span class="sources-label"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2"/></svg>实际引用来源 <span class="source-count">{{ message.response.sources.length }}</span></span><span class="details-action">展开原文 <span class="chevron" aria-hidden="true">⌄</span></span></summary>
                <p class="sources-note">以下为该轮回答生成时的历史来源快照，可能已被删除或更新；不代表本次提问的有效依据。</p>
                <details v-for="(source, sourceIndex) in message.response.sources" :key="`${source.chunkId}-${sourceIndex}`" class="source-item">
                  <summary><span class="source-number" aria-hidden="true">{{ sourceIndex + 1 }}</span><span class="source-meta"><span class="source-filename">{{ source.fileName }}</span><span class="source-location">分块 {{ source.chunkIndex }}<template v-if="pageLabel(source.pageStart, source.pageEnd)"> · {{ pageLabel(source.pageStart, source.pageEnd) }}</template></span></span><span class="chevron" aria-hidden="true">⌄</span></summary>
                  <p class="source-text">{{ source.text }}</p>
                </details>
              </details>
            </template>
          </div>
        </article>
        <div ref="latestMessage" class="message-end" aria-hidden="true"></div>
      </section>
    </main>

    <div class="composer-area">
      <div class="composer-inner">
        <p class="context-note"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8v5M12 16h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z"/></svg>会话会持久保存，刷新后可恢复；追问最多参考最近 5 轮完整问答，预算不足时会减少。新对话不携带旧记忆。</p>
        <form class="composer" :class="{ invalid: !!validation, busy: state.sending }" @submit.prevent="submit">
          <label for="question">向知识库提问</label>
          <textarea id="question" ref="textarea" v-model="state.draft" name="question" rows="3" placeholder="输入你想从文档中了解的问题…" :readonly="state.sending" :aria-invalid="!!validation" :aria-describedby="`question-hint question-count${validation ? ' question-validation' : ''}`" @keydown="onKeydown" @compositionstart="composing = true" @compositionend="composing = false"></textarea>
          <div class="composer-toolbar"><span id="question-count" class="character-count" :class="{ exceeded: !!validation }">{{ characterCount }} / {{ QUESTION_LIMIT }} 字符</span><button class="send-button" type="submit" :disabled="disabled"><span v-if="state.sending" class="spinner" aria-hidden="true"></span><span>{{ state.sending ? '正在生成' : retrying ? '重新发送' : '发送问题' }}</span><svg v-if="!state.sending" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6"/></svg></button></div>
        </form>
        <p v-if="validation" id="question-validation" class="validation-error" role="alert">{{ validation }}</p>
        <div id="question-hint" class="composer-hints"><span>{{ state.sending ? '正在等待回答，请勿重复发送。' : statusHint || 'Enter 发送 · Shift + Enter 换行' }}</span><span v-if="!statusHint && !state.sending" class="verification-note">回答仅供参考，请结合来源核对。</span></div>
      </div>
    </div>
    <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ liveMessage }}</div>
  </div>
</template>
