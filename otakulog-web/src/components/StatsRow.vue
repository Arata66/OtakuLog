<script setup lang="ts">
import { computed } from 'vue'
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'

const store = useAnimeStore()
const { t } = useI18n()

const statCards = computed(() => {
  const s = store.stats
  return [
    { key: 'total', label: t('stat.total'), value: s.total ?? '-', cls: 's-total' },
    { key: 'watching', label: t('stat.watching'), value: s.watching ?? '-', cls: 's-watching' },
    { key: 'finished', label: t('stat.finished'), value: s.finished ?? '-', cls: 's-finished' },
    { key: 'planning', label: t('stat.planning'), value: s.planning ?? '-', cls: 's-planning' },
    { key: 'dropped', label: t('stat.dropped'), value: s.dropped ?? '-', cls: 's-dropped' },
  ]
})

const detailCards = computed(() => {
  const s = store.stats
  return [
    { label: t('stat.progress'), value: s.progress ?? '-', sub: '' },
    { label: t('stat.avgScore'), value: s.avgScore ?? '-', sub: t('stat.avgScoreSub') },
    { label: t('stat.highScore'), value: s.highScore ?? '-', sub: t('stat.highScoreSub') },
    { label: t('stat.mediumScore'), value: s.mediumScore ?? '-', sub: t('stat.mediumScoreSub') },
    { label: t('stat.lowScore'), value: s.lowScore ?? '-', sub: t('stat.lowScoreSub') },
  ]
})
</script>

<template>
  <div>
    <div class="stats-row">
      <div v-for="card in statCards" :key="card.key" class="s-card" :class="card.cls">
        <div class="s-label">{{ card.label }}</div>
        <div class="s-num">{{ card.value }}</div>
      </div>
    </div>
    <div class="detail-row">
      <div v-for="(card, i) in detailCards" :key="i" class="d-card">
        <div class="d-label">{{ card.label }}</div>
        <div class="d-val">{{ card.value }}</div>
        <div v-if="card.sub" class="d-sub">{{ card.sub }}</div>
      </div>
    </div>
  </div>
</template>
