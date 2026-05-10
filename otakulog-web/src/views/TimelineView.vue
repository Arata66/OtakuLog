<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useStatsStore } from '@/stores/stats'
import { useI18n } from '@/composables/useI18n'
import DefaultLayout from '@/components/DefaultLayout.vue'
import Toast from '@/components/Toast.vue'

const store = useStatsStore()
const { t } = useI18n()

const mode = ref<'watch' | 'air'>('watch')

function switchMode(m: 'watch' | 'air') {
  mode.value = m
  store.loadTimeline(m)
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return dateStr
}

onMounted(() => {
  store.loadTimeline('watch')
})
</script>

<template>
  <DefaultLayout>
    <h2 style="font-family: var(--serif); font-size: 1.2em; color: var(--text); font-weight: 400; margin-bottom: 20px">
      {{ t('timeline.title') }}
    </h2>

    <div class="cal-toggle" style="margin-bottom: 24px">
      <button class="cal-toggle-btn" :class="{ active: mode === 'watch' }" @click="switchMode('watch')">追番时间</button>
      <button class="cal-toggle-btn" :class="{ active: mode === 'air' }" @click="switchMode('air')">开播时间</button>
    </div>

    <div v-if="store.timeline.length === 0" style="text-align: center; padding: 60px 20px; color: var(--text-faint)">
      暂无时间线数据
    </div>

    <div v-else class="timeline">
      <div v-for="a in store.timeline" :key="a.id" class="tl">
        <div class="t-date">{{ formatDate(mode === 'watch' ? a.watchStartDate : a.startDate) }}</div>
        <div class="t-name">{{ a.name }}</div>
        <div class="t-meta">{{ a.season }} · {{ a.score }}分 · {{ a.currentEpisode }}/{{ a.totalEpisodes }} ep</div>
      </div>
    </div>

    <Toast />
  </DefaultLayout>
</template>
