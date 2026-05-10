<script setup lang="ts">
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'

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
</script>

<template>
  <div class="gallery-grid">
    <div v-for="a in store.list" :key="a.id" class="g-card">
      <img
        v-if="a.coverUrl"
        :src="a.coverUrl"
        loading="lazy"
        class="g-cover"
        @error="($event.target as HTMLImageElement).outerHTML='<div class=g-cover-empty>' + a.name.charAt(0) + '</div>'"
      />
      <div v-else class="g-cover-empty">{{ a.name.charAt(0) }}</div>
      <div class="g-body">
        <div class="g-name clickable-name" @click="emit('detail', a.id)">{{ a.name }}</div>
        <div class="g-meta">
          <span class="g-tag g-season">{{ a.season }}</span>
          <span class="g-tag g-score" :class="scoreClass(a.score)">{{ a.score }}</span>
        </div>
        <div class="g-progress">
          <div class="g-progress-bar" :class="a.status" :style="{ width: pct(a) + '%' }" />
        </div>
        <div class="g-ep">{{ a.currentEpisode }} / {{ a.totalEpisodes }} ep &middot; {{ t(`status.${a.status}` as any) }}</div>
        <div class="g-actions">
          <button class="g-btn" @click="emit('edit', a.id)">{{ t('table.edit') }}</button>
          <button class="g-btn ep" @click="store.prevEp(a.id)">-</button>
          <button class="g-btn ep" @click="store.nextEp(a.id)">+</button>
          <button class="g-btn del" @click="store.deleteAnime(a.id)">{{ t('detail.delete') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
