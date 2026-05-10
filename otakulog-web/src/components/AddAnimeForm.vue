<script setup lang="ts">
import { ref } from 'vue'
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { request } from '@/api'

const store = useAnimeStore()
const { t } = useI18n()
const toast = useToast()

// 表单字段
const form = ref({
  name: '',
  totalEpisodes: 12,
  season: '',
  score: 7.0,
  coverUrl: '',
  startDate: '',
  endDate: '',
  remark: '',
  broadcastDay: '',
  bangumiId: '',
  tags: '',
  status: 'watching',
})

// Bangumi 搜索
const bangumiResults = ref<any[]>([])
const showBangumi = ref(false)
const searching = ref(false)

async function searchBangumi() {
  if (!form.value.name.trim()) {
    toast.info(t('toast.inputName'))
    return
  }
  searching.value = true
  try {
    const res = await request.get('/bangumi/search', {
      params: { keyword: form.value.name.trim(), limit: 8 },
    })
    bangumiResults.value = (res as any) || []
    showBangumi.value = bangumiResults.value.length > 0
    if (!showBangumi.value) toast.info(t('toast.noResults'))
  } catch (e: any) {
    toast.error(e.message || '搜索失败')
  } finally {
    searching.value = false
  }
}

function selectBangumi(item: any) {
  form.value.bangumiId = item.id || ''
  const name = item.nameCn || item.name
  if (name) form.value.name = name
  if (item.eps) form.value.totalEpisodes = item.eps
  if (item.score) form.value.score = parseFloat(item.score.toFixed(1))
  if (item.image) {
    form.value.coverUrl = item.image.startsWith('//') ? 'https:' + item.image : item.image
  }
  if (item.date) {
    form.value.startDate = item.date
    const m = parseInt(item.date.split('-')[1])
    const y = item.date.split('-')[0]
    if (m && y) {
      const seasons: Record<number, string> = { 1: '冬', 2: '冬', 3: '春', 4: '春', 5: '春', 6: '夏', 7: '夏', 8: '夏', 9: '秋', 10: '秋', 11: '秋', 12: '冬' }
      form.value.season = y + seasons[m]
    }
    try {
      const d = new Date(item.date)
      const jsDay = d.getDay()
      form.value.broadcastDay = String(jsDay === 0 ? 7 : jsDay)
    } catch (_) {}
  }
  toast.success(t('toast.filled', name))
}

function closeBangumi() {
  showBangumi.value = false
}

async function onSubmit() {
  if (!form.value.name.trim() || !form.value.season.trim()) {
    toast.error(t('toast.fillRequired'))
    return
  }
  try {
    await store.addAnime({
      name: form.value.name.trim(),
      totalEpisodes: form.value.totalEpisodes,
      season: form.value.season.trim(),
      score: form.value.score,
      remark: form.value.remark || undefined,
      coverUrl: form.value.coverUrl || undefined,
      startDate: form.value.startDate || undefined,
      endDate: form.value.endDate || undefined,
      tags: form.value.tags || undefined,
      broadcastDay: form.value.broadcastDay ? parseInt(form.value.broadcastDay) : undefined,
      bangumiId: form.value.bangumiId ? parseInt(form.value.bangumiId) : undefined,
      status: form.value.status as any,
    })
    // 重置表单
    form.value = { name: '', totalEpisodes: 12, season: '', score: 7.0, coverUrl: '', startDate: '', endDate: '', remark: '', broadcastDay: '', bangumiId: '', tags: '', status: 'watching' }
    showBangumi.value = false
  } catch (_) {}
}
</script>

<template>
  <div class="add-form">
    <h3>{{ t('form.add') }}</h3>
    <form class="form-grid" @submit.prevent="onSubmit">
      <div class="form-row">
        <div class="ff">
          <label>{{ t('form.name') }}</label>
          <input v-model="form.name" type="text" :placeholder="t('form.namePlaceholder')" />
        </div>
        <button type="button" class="btn-bangumi" :disabled="searching" @click="searchBangumi">
          {{ searching ? t('bangumi.searching') : t('bangumi.search') }}
        </button>
      </div>
      <div class="form-row">
        <div class="ff"><label>{{ t('form.totalEpisodes') }}</label><input v-model.number="form.totalEpisodes" type="number" min="1" /></div>
        <div class="ff"><label>{{ t('form.season') }}</label><input v-model="form.season" type="text" placeholder="2024冬" /></div>
        <div class="ff"><label>{{ t('form.score') }}</label><input v-model.number="form.score" type="number" min="0" max="10" step="0.1" /></div>
      </div>
      <div class="form-row">
        <div class="ff"><label>{{ t('form.cover') }}</label><input v-model="form.coverUrl" type="text" :placeholder="t('form.coverPlaceholder')" /></div>
        <div class="ff"><label>{{ t('form.startDate') }}</label><input v-model="form.startDate" type="date" /></div>
        <div class="ff"><label>{{ t('form.endDate') }}</label><input v-model="form.endDate" type="date" /></div>
      </div>
      <div class="form-row">
        <div class="ff"><label>{{ t('form.remark') }}</label><input v-model="form.remark" type="text" :placeholder="t('form.remarkPlaceholder')" /></div>
        <div class="ff">
          <label>{{ t('form.broadcastDay') }}</label>
          <select v-model="form.broadcastDay">
            <option value="">{{ t('form.broadcastDayUnset') }}</option>
            <option value="1">{{ t('day.mon') }}</option>
            <option value="2">{{ t('day.tue') }}</option>
            <option value="3">{{ t('day.wed') }}</option>
            <option value="4">{{ t('day.thu') }}</option>
            <option value="5">{{ t('day.fri') }}</option>
            <option value="6">{{ t('day.sat') }}</option>
            <option value="7">{{ t('day.sun') }}</option>
          </select>
        </div>
        <div class="ff"><label>{{ t('form.tags') }}</label><input v-model="form.tags" type="text" :placeholder="t('form.tagsPlaceholder')" /></div>
      </div>
      <button type="submit" class="btn-add">{{ t('form.submit') }}</button>
    </form>

    <!-- Bangumi 搜索结果 -->
    <div v-if="showBangumi" class="bangumi-results active">
      <div class="bangumi-header">
        <h4>{{ t('bangumi.results') }}</h4>
        <button class="btn-sm" @click="closeBangumi">{{ t('bangumi.close') }}</button>
      </div>
      <div class="bangumi-grid">
        <div
          v-for="item in bangumiResults"
          :key="item.id"
          class="bg-card"
          @click="selectBangumi(item)"
        >
          <img v-if="item.image" :src="item.image.startsWith('//') ? 'https:' + item.image : item.image" class="bg-cover" @error="($event.target as HTMLImageElement).style.display='none'" />
          <div v-else class="bg-cover-empty">N/A</div>
          <div class="bg-info">
            <div class="bg-name" :title="item.nameCn || item.name">{{ item.nameCn || item.name }}</div>
            <div class="bg-sub" :title="item.name">{{ item.name }}</div>
            <div class="bg-meta">
              <span>ep {{ item.eps || '?' }}</span>
              <span>{{ item.score ? item.score.toFixed(1) : '-' }}</span>
              <span>{{ item.date || '' }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
