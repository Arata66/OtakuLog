<script setup lang="ts">
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'

const store = useAnimeStore()
const { t } = useI18n()

function onChangeStatus(status: string) {
  if (!status) return
  store.batchChangeStatus(status as any)
}

function onDelete() {
  if (!confirm(t('confirm.batchDelete', String(store.selectedCount)))) return
  store.batchDelete()
}
</script>

<template>
  <div class="batch-toolbar" :class="{ active: store.selectedCount > 0 }">
    <span class="bt-count">{{ t('batch.selected', String(store.selectedCount)) }}</span>
    <select @change="onChangeStatus(($event.target as HTMLSelectElement).value)">
      <option value="">{{ t('batch.changeStatus') }}</option>
      <option value="watching">{{ t('status.watching') }}</option>
      <option value="finished">{{ t('status.finished') }}</option>
      <option value="planning">{{ t('status.planning') }}</option>
      <option value="dropped">{{ t('status.dropped') }}</option>
    </select>
    <button class="bt-btn bt-del" @click="onDelete">{{ t('batch.delete') }}</button>
    <button class="bt-btn bt-cancel" @click="store.clearSelection()">{{ t('batch.cancel') }}</button>
  </div>
</template>
