<script setup lang="ts">
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import type { AnimeVO } from '@/api/types'

const store = useAnimeStore()
const { t } = useI18n()

const emit = defineEmits<{
  edit: [id: number]
  detail: [id: number]
}>()

function scoreClass(s: number) {
  return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'
}

function renderTags(tags: string) {
  if (!tags) return []
  return tags.split(',').map((t) => t.trim()).filter(Boolean)
}
</script>

<template>
  <div class="table-card">
    <table>
      <thead>
        <tr>
          <th style="width: 36px; padding: 12px 8px; text-align: center">
            <input
              type="checkbox"
              class="batch-cb"
              :checked="store.selectedIds.size > 0 && store.selectedIds.size === store.list.length"
              @change="store.toggleSelectAll(($event.target as HTMLInputElement).checked)"
            />
          </th>
          <th>{{ t('table.index') }}</th>
          <th></th>
          <th>{{ t('table.name') }}</th>
          <th>{{ t('table.season') }}</th>
          <th>{{ t('table.status') }}</th>
          <th>{{ t('table.score') }}</th>
          <th>{{ t('table.episodes') }}</th>
          <th>{{ t('table.remark') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(a, i) in store.list"
          :key="a.id"
          :class="{ selected: store.selectedIds.has(a.id) }"
        >
          <td style="width: 36px; padding: 12px 8px; text-align: center">
            <input
              type="checkbox"
              class="batch-cb"
              :checked="store.selectedIds.has(a.id)"
              @change="store.toggleSelect(a.id, ($event.target as HTMLInputElement).checked)"
            />
          </td>
          <td style="color: var(--text-dim); font-size: 0.82em">{{ i + 1 }}</td>
          <td>
            <img
              v-if="a.coverUrl"
              :src="a.coverUrl"
              loading="lazy"
              class="cover-img"
              @error="($event.target as HTMLImageElement).style.display='none'"
            />
            <div v-else class="cover-empty">N/A</div>
          </td>
          <td>
            <span class="clickable-name" @click="emit('detail', a.id)">{{ a.name }}</span>
            <div v-if="a.tags" style="margin-top: 4px">
              <span v-for="tag in renderTags(a.tags)" :key="tag" class="tag-pill">{{ tag }}</span>
            </div>
          </td>
          <td><span class="season-tag">{{ a.season }}</span></td>
          <td>
            <select class="e-select" :value="a.status" @change="store.changeStatus(a.id, ($event.target as HTMLSelectElement).value)">
              <option value="watching">{{ t('status.watching') }}</option>
              <option value="finished">{{ t('status.finished') }}</option>
              <option value="planning">{{ t('status.planning') }}</option>
              <option value="dropped">{{ t('status.dropped') }}</option>
            </select>
          </td>
          <td><span class="score-pill" :class="scoreClass(a.score)">{{ a.score }}</span></td>
          <td class="ep">{{ a.currentEpisode }} / {{ a.totalEpisodes }}</td>
          <td><span class="remark-cell" :title="a.remark">{{ a.remark }}</span></td>
          <td>
            <div class="acts">
              <button class="a-btn" @click="emit('edit', a.id)">{{ t('table.edit') }}</button>
              <button class="a-btn ep-btn" @click="store.prevEp(a.id)">-</button>
              <button class="a-btn ep-btn" @click="store.nextEp(a.id)">+</button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
