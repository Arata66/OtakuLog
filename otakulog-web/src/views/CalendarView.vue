<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useStatsStore } from '@/stores/stats'
import { useI18n } from '@/composables/useI18n'
import DefaultLayout from '@/components/DefaultLayout.vue'
import Toast from '@/components/Toast.vue'

const store = useStatsStore()
const { t } = useI18n()

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const calViewMode = ref<'mine' | 'all'>('mine')
const selectedDay = ref(0)

const scheduleData = computed(() => store.schedule as any)

const dataSource = computed(() => {
  if (!scheduleData.value) return {}
  return calViewMode.value === 'mine' ? scheduleData.value.mySchedule : scheduleData.value.bangumiSchedule
})

const currentDayList = computed(() => {
  return dataSource.value?.[selectedDay.value] || []
})

const todayDay = computed(() => scheduleData.value?.todayDay || 1)

function selectDay(day: number) {
  selectedDay.value = day
}

function scoreClass(s: number) {
  return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'
}

onMounted(async () => {
  await store.loadSchedule()
  selectedDay.value = todayDay.value
})
</script>

<template>
  <DefaultLayout>
    <h2 style="font-family: var(--serif); font-size: 1.2em; color: var(--text); font-weight: 400; margin-bottom: 20px">
      {{ t('calendar.title') }}
    </h2>

    <!-- 视图切换 -->
    <div class="cal-top-bar">
      <div class="cal-toggle">
        <button class="cal-toggle-btn" :class="{ active: calViewMode === 'mine' }" @click="calViewMode = 'mine'">我的追番</button>
        <button class="cal-toggle-btn" :class="{ active: calViewMode === 'all' }" @click="calViewMode = 'all'">本季新番</button>
      </div>
    </div>

    <!-- 星期选择 -->
    <div class="cal-days">
      <div
        v-for="day in 7"
        :key="day"
        class="cal-day-btn"
        :class="{ active: selectedDay === day, today: day === todayDay }"
        @click="selectDay(day)"
      >
        <div class="cal-day-name">{{ DAY_NAMES[day] }}</div>
        <div class="cal-day-num">{{ (dataSource?.[day]?.length) || '·' }}</div>
      </div>
    </div>

    <!-- 日历内容 -->
    <div class="cal-content">
      <div v-if="currentDayList.length === 0" class="cal-empty">
        <div class="cal-empty-icon">📭</div>
        <div>{{ DAY_NAMES[selectedDay] }}暂无{{ calViewMode === 'mine' ? '追番' : '放送' }}</div>
      </div>
      <div v-else class="cal-grid">
        <div v-for="a in currentDayList" :key="a.id || a.bangumiId" class="cal-card">
          <!-- 我的追番 -->
          <template v-if="calViewMode === 'mine'">
            <img v-if="a.coverUrl" :src="a.coverUrl" class="cc-cover" @error="($event.target as HTMLImageElement).outerHTML='<div class=cc-cover-empty>' + a.name.charAt(0) + '</div>'" />
            <div v-else class="cc-cover-empty">{{ a.name.charAt(0) }}</div>
            <span class="cc-badge" :class="a.status">{{ a.status === 'watching' ? '追中' : a.status === 'finished' ? '已完成' : a.status === 'planning' ? '计划' : '放弃' }}</span>
            <div class="cc-body">
              <div class="cc-name">{{ a.name }}</div>
              <div class="cc-meta"><span class="cc-tag cc-score" :class="scoreClass(a.score)">{{ a.score || '-' }}</span></div>
              <div class="cc-ep">{{ a.currentEpisode }}/{{ a.totalEpisodes }} ep</div>
              <div v-if="a.airDate" class="cc-airdate">{{ a.airDate }}</div>
              <div v-if="a.daysUntilAir != null" class="cc-countdown" :class="a.daysUntilAir > 0 ? 'future' : 'past'">
                {{ a.daysUntilAir > 0 ? `开播 ${a.daysUntilAir} 天后` : a.daysUntilAir === 0 ? '今天开播' : `已放送 ${Math.abs(a.daysUntilAir)} 天` }}
              </div>
            </div>
          </template>

          <!-- 本季新番 -->
          <template v-else>
            <img v-if="a.image" :src="a.image.startsWith('//') ? 'https:' + a.image : a.image" class="cc-cover" @error="($event.target as HTMLImageElement).outerHTML='<div class=cc-cover-empty>' + ((a.nameCn || a.name || '?') as string).charAt(0) + '</div>'" />
            <div v-else class="cc-cover-empty">{{ ((a.nameCn || a.name || '?') as string).charAt(0) }}</div>
            <span class="cc-badge" :class="a.isTracked ? 'tracked' : ''">{{ a.isTracked ? '已追' : (a.eps || '?') + '集' }}</span>
            <div class="cc-body">
              <div class="cc-name">{{ a.nameCn || a.name }}</div>
              <div class="cc-meta">
                <span class="cc-tag cc-score" :class="scoreClass(a.score)">{{ a.score ? Number(a.score).toFixed(1) : '-' }}</span>
              </div>
              <div v-if="a.airDate" class="cc-airdate">{{ a.airDate }}</div>
              <div v-if="a.daysUntilAir != null" class="cc-countdown" :class="a.daysUntilAir > 0 ? 'future' : 'past'">
                {{ a.daysUntilAir > 0 ? `开播 ${a.daysUntilAir} 天后` : a.daysUntilAir === 0 ? '今天开播' : '' }}
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <Toast />
  </DefaultLayout>
</template>
