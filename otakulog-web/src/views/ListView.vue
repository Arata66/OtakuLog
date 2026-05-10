<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import DefaultLayout from '@/components/DefaultLayout.vue'
import SearchStrip from '@/components/SearchStrip.vue'
import StatsRow from '@/components/StatsRow.vue'
import AddAnimeForm from '@/components/AddAnimeForm.vue'
import AnimeTable from '@/components/AnimeTable.vue'
import AnimeDetailGrid from '@/components/AnimeDetailGrid.vue'
import AnimeGallery from '@/components/AnimeGallery.vue'
import EditAnimeModal from '@/components/EditAnimeModal.vue'
import AnimeDetailModal from '@/components/AnimeDetailModal.vue'
import BatchToolbar from '@/components/BatchToolbar.vue'
import TraceMoeSearch from '@/components/TraceMoeSearch.vue'
import GroupPanel from '@/components/GroupPanel.vue'
import Toast from '@/components/Toast.vue'

const store = useAnimeStore()
const { t } = useI18n()

const editId = ref<number | null>(null)
const detailId = ref<number | null>(null)

function openEdit(id: number) {
  detailId.value = null
  editId.value = id
}

function openDetail(id: number) {
  detailId.value = id
}

function closeEdit() {
  editId.value = null
}

function closeDetail() {
  detailId.value = null
}

onMounted(() => {
  store.search()
  store.refreshStats()
})
</script>

<template>
  <DefaultLayout>
    <AddAnimeForm />
    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 16px">
      <TraceMoeSearch @add-to-tracking="(name) => { store.searchName = name; store.search() }" />
    </div>
    <SearchStrip />
    <StatsRow />
    <GroupPanel />

    <!-- 空状态 -->
    <div v-if="store.isEmpty" class="empty">
      <div class="e-icon">📺</div>
      <p>{{ t('empty.title') }}</p>
    </div>

    <!-- 表格视图 -->
    <AnimeTable v-if="store.viewMode === 'table' && !store.isEmpty" @edit="openEdit" @detail="openDetail" />

    <!-- 详情卡片视图 -->
    <AnimeDetailGrid v-if="store.viewMode === 'detail' && !store.isEmpty" @edit="openEdit" @detail="openDetail" />

    <!-- 封面墙视图 -->
    <AnimeGallery v-if="store.viewMode === 'gallery' && !store.isEmpty" @edit="openEdit" @detail="openDetail" />

    <!-- 加载更多提示 -->
    <div v-if="store.hasMore && !store.isEmpty && !store.loading" class="pagination-indicator" style="cursor: pointer" @click="store.loadMore()">
      加载更多...
    </div>
    <div v-if="store.loading" class="pagination-indicator">加载中...</div>

    <!-- 批量操作栏 -->
    <BatchToolbar />

    <!-- 编辑弹窗 -->
    <EditAnimeModal v-if="editId != null" :anime-id="editId" @close="closeEdit" />

    <!-- 详情弹窗 -->
    <AnimeDetailModal v-if="detailId != null" :anime-id="detailId" @close="closeDetail" @edit="openEdit" />

    <!-- Toast -->
    <Toast />
  </DefaultLayout>
</template>

<style scoped>
.empty {
  text-align: center;
  padding: 80px 20px;
  color: var(--text-faint);
}
.empty .e-icon {
  font-size: 2.5em;
  margin-bottom: 12px;
  opacity: 0.5;
}
.empty p {
  font-size: 0.92em;
}
.pagination-indicator {
  text-align: center;
  padding: 16px 0;
  font-size: 0.78em;
  color: var(--text-faint);
  letter-spacing: 0.5px;
}
</style>
