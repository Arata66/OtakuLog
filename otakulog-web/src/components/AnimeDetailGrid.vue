<script setup lang="ts">
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const store = useAnimeStore()
const { t } = useI18n()

const emit = defineEmits<{
  edit: [id: number]
  detail: [id: number]
}>()

function scoreClass(s: number) {
  return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'
}

function pct(a: { currentEpisode: number; totalEpisodes: number }) {
  return a.totalEpisodes > 0 ? Math.round((a.currentEpisode / a.totalEpisodes) * 100) : 0
}

function renderRemark(text: string) {
  if (!text) return ''
  const raw = marked.parse(text, { breaks: true, gfm: true }) as string
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
}

function renderTags(tags: string) {
  if (!tags) return []
  return tags.split(',').map((t) => t.trim()).filter(Boolean)
}
</script>

<template>
  <div class="detail-grid">
    <div v-for="a in store.list" :key="a.id" class="dt-card">
      <div class="dt-cover-wrap">
        <img
          v-if="a.coverUrl"
          :src="a.coverUrl"
          loading="lazy"
          class="dt-cover"
          @error="($event.target as HTMLImageElement).outerHTML='<div class=dt-cover-empty>' + a.name.charAt(0) + '</div>'"
        />
        <div v-else class="dt-cover-empty">{{ a.name.charAt(0) }}</div>
        <span class="dt-status-badge" :class="a.status">{{ t(`status.${a.status}` as any) }}</span>
      </div>
      <div class="dt-body">
        <div class="dt-name clickable-name" @click="emit('detail', a.id)">{{ a.name }}</div>
        <div class="dt-meta">
          <span class="dt-tag dt-season">{{ a.season }}</span>
          <span class="dt-tag dt-score" :class="scoreClass(a.score)">{{ a.score }}</span>
          <span v-for="tag in renderTags(a.tags)" :key="tag" class="tag-pill">{{ tag }}</span>
        </div>
        <div class="dt-progress-wrap">
          <div class="dt-progress-label">
            <span>{{ a.currentEpisode }} / {{ a.totalEpisodes }} ep</span>
            <span>{{ pct(a) }}%</span>
          </div>
          <div class="dt-progress">
            <div class="dt-progress-bar" :class="a.status" :style="{ width: pct(a) + '%' }" />
          </div>
        </div>
        <div v-if="a.remark" class="dt-remark" v-html="renderRemark(a.remark)" />
        <div v-else class="dt-remark" style="visibility: hidden">-</div>
        <div class="dt-actions">
          <button class="dt-btn" @click="emit('edit', a.id)">{{ t('detail.edit') }}</button>
          <button class="dt-btn ep" @click="store.prevEp(a.id)">-</button>
          <button class="dt-btn ep" @click="store.nextEp(a.id)">+</button>
          <button class="dt-btn del" @click="store.deleteAnime(a.id)">{{ t('detail.delete') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
