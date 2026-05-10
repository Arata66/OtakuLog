<script setup lang="ts">
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'

const store = useAnimeStore()
const { t } = useI18n()

const emit = defineEmits<{
  search: []
}>()

function onSearch() {
  store.search()
  emit('search')
}

function onReset() {
  store.resetFilters()
  emit('search')
}
</script>

<template>
  <div class="search-strip">
    <label>{{ t('search.label') }}</label>
    <input
      v-model="store.searchName"
      type="text"
      :placeholder="t('search.name')"
      @keyup.enter="onSearch"
    />
    <select v-model="store.filterStatus" @change="onSearch">
      <option value="">{{ t('search.all') }}</option>
      <option value="watching">{{ t('status.watching') }}</option>
      <option value="finished">{{ t('status.finished') }}</option>
      <option value="planning">{{ t('status.planning') }}</option>
      <option value="dropped">{{ t('status.dropped') }}</option>
    </select>
    <select v-model="store.sortBy" @change="onSearch">
      <option value="id-desc">{{ t('search.sortNewest') }}</option>
      <option value="score-desc">{{ t('search.sortScoreHigh') }}</option>
      <option value="score-asc">{{ t('search.sortScoreLow') }}</option>
      <option value="progress-desc">{{ t('search.sortProgressHigh') }}</option>
      <option value="progress-asc">{{ t('search.sortProgressLow') }}</option>
      <option value="name-asc">{{ t('search.sortName') }}</option>
      <option value="sortOrder-asc">{{ t('search.sortCustom') }}</option>
    </select>
    <input
      v-model="store.filterTag"
      type="text"
      :placeholder="t('search.tagPlaceholder')"
      style="width: 120px"
      @keyup.enter="onSearch"
    />
    <button class="btn-sm" @click="onReset">{{ t('search.reset') }}</button>
    <!-- 视图切换 -->
    <div class="view-toggle">
      <button
        class="view-btn"
        :class="{ active: store.viewMode === 'table' }"
        @click="store.setViewMode('table')"
      >
        ☰
      </button>
      <button
        class="view-btn"
        :class="{ active: store.viewMode === 'detail' }"
        @click="store.setViewMode('detail')"
      >
        ▦
      </button>
      <button
        class="view-btn"
        :class="{ active: store.viewMode === 'gallery' }"
        @click="store.setViewMode('gallery')"
      >
        ▣
      </button>
    </div>
  </div>
</template>

<style scoped>
.view-toggle {
  display: flex;
  gap: 2px;
  background: var(--bg);
  border: 1.5px solid var(--border);
  border-radius: 8px;
  padding: 2px;
  margin-left: auto;
}
.view-btn {
  border: none;
  background: transparent;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-dim);
  transition: all 0.2s;
  font-size: 0.85em;
  line-height: 1;
}
.view-btn.active {
  background: var(--card);
  color: var(--text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.view-btn:hover:not(.active) {
  color: var(--text-mid);
}
</style>
