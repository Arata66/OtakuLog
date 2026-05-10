<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import type { AnimeVO } from '@/api/types'

const props = defineProps<{ animeId: number | null }>()
const emit = defineEmits<{ close: [] }>()

const store = useAnimeStore()
const { t } = useI18n()
const toast = useToast()

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
  tags: '',
  status: 'watching' as string,
  watchStartDate: '',
  legacy: false,
})

const anime = computed(() => (props.animeId != null ? store.cache[props.animeId] : null))

watch(
  () => props.animeId,
  (id) => {
    if (id == null) return
    const a = store.cache[id]
    if (!a) return
    form.value = {
      name: a.name,
      totalEpisodes: a.totalEpisodes,
      season: a.season,
      score: a.score,
      coverUrl: a.coverUrl || '',
      startDate: a.startDate || '',
      endDate: a.endDate || '',
      remark: a.remark || '',
      broadcastDay: a.broadcastDay ? String(a.broadcastDay) : '',
      tags: a.tags || '',
      status: a.status,
      watchStartDate: a.watchStartDate || '',
      legacy: a.legacy,
    }
  },
  { immediate: true },
)

async function onSave() {
  if (!form.value.name.trim() || !form.value.season.trim()) {
    toast.error(t('toast.fillRequired'))
    return
  }
  if (props.animeId == null) return
  try {
    await store.updateAnime(props.animeId, {
      name: form.value.name.trim(),
      totalEpisodes: form.value.totalEpisodes,
      season: form.value.season.trim(),
      score: form.value.score,
      coverUrl: form.value.coverUrl || undefined,
      startDate: form.value.startDate || undefined,
      endDate: form.value.endDate || undefined,
      remark: form.value.remark || undefined,
      tags: form.value.tags || undefined,
      broadcastDay: form.value.broadcastDay ? parseInt(form.value.broadcastDay) : undefined,
      watchStartDate: form.value.watchStartDate || undefined,
      legacy: form.value.legacy,
    })
    // 如果状态也改了
    if (form.value.status && anime.value && form.value.status !== anime.value.status) {
      await store.changeStatus(props.animeId, form.value.status)
    }
    emit('close')
  } catch (_) {}
}
</script>

<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="modal-card">
      <h3>{{ t('modal.edit', anime?.name || '') }}</h3>
      <div class="modal-field">
        <label>{{ t('modal.name') }}</label>
        <input v-model="form.name" type="text" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.totalEpisodes') }}</label>
        <input v-model.number="form.totalEpisodes" type="number" min="1" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.season') }}</label>
        <input v-model="form.season" type="text" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.score') }}</label>
        <input v-model.number="form.score" type="number" min="0" max="10" step="0.1" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.cover') }}</label>
        <input v-model="form.coverUrl" type="text" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.startDate') }}</label>
        <input v-model="form.startDate" type="date" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.endDate') }}</label>
        <input v-model="form.endDate" type="date" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.remark') }}</label>
        <textarea v-model="form.remark" rows="3" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.broadcastDay') }}</label>
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
      <div class="modal-field">
        <label>{{ t('modal.tags') }}</label>
        <input v-model="form.tags" type="text" placeholder="热血,奇幻" />
      </div>
      <div class="modal-field">
        <label>{{ t('modal.status') }}</label>
        <select v-model="form.status">
          <option value="watching">{{ t('status.watching') }}</option>
          <option value="finished">{{ t('status.finished') }}</option>
          <option value="planning">{{ t('status.planning') }}</option>
          <option value="dropped">{{ t('status.dropped') }}</option>
        </select>
      </div>
      <div class="modal-field" style="flex-direction: row; align-items: center; gap: 8px">
        <input v-model="form.legacy" type="checkbox" style="width: auto" />
        <label style="text-transform: none; letter-spacing: 0; font-size: 0.82em; cursor: pointer">旧番（不计入追番统计）</label>
      </div>
      <div class="modal-actions">
        <button class="btn-h" @click="emit('close')">{{ t('modal.cancel') }}</button>
        <button class="btn-add" @click="onSave">{{ t('modal.save') }}</button>
      </div>
    </div>
  </div>
</template>
