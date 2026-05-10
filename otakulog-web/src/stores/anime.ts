import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AnimeVO, AnimeStatus } from '@/api/types'
import * as api from '@/api/anime'
import { useToast } from '@/composables/useToast'
import { isOfflineEnabled, offlineGetAnimeList } from '@/utils/offlineApi'
import { cacheAnimeList, getCachedAnimeList } from '@/utils/cache'

const PAGE_SIZE = 12

export const useAnimeStore = defineStore('anime', () => {
  const toast = useToast()

  // 列表数据
  const list = ref<AnimeVO[]>([])
  const cache = ref<Record<number, AnimeVO>>({})
  const loading = ref(false)
  const currentPage = ref(0)
  const hasMore = ref(true)
  const totalElements = ref(0)

  // 筛选条件
  const searchName = ref('')
  const filterStatus = ref('')
  const filterTag = ref('')
  const sortBy = ref('id-desc')

  // 视图模式
  const viewMode = ref(localStorage.getItem('otakulog-view') || 'table')

  // 批量选择
  const selectedIds = ref<Set<number>>(new Set())

  // 统计
  const stats = ref<Record<string, unknown>>({})

  // 计算属性
  const selectedCount = computed(() => selectedIds.value.size)
  const isEmpty = computed(() => !loading.value && list.value.length === 0)

  // 更新缓存
  function updateCache(items: AnimeVO[]) {
    items.forEach((a) => (cache.value[a.id] = a))
  }

  // 搜索（重置分页）
  async function search(reset = true) {
    if (reset) {
      currentPage.value = 0
      hasMore.value = true
      selectedIds.value.clear()
    }
    if (loading.value || !hasMore.value) return
    loading.value = true
    try {
      // 离线模式：从缓存加载
      if (isOfflineEnabled()) {
        const cached = await offlineGetAnimeList()
        list.value = cached
        updateCache(cached)
        hasMore.value = false
        totalElements.value = cached.length
        return
      }

      const res = await api.searchAnimePaged({
        name: searchName.value || undefined,
        status: filterStatus.value || undefined,
        tag: filterTag.value || undefined,
        sortBy: sortBy.value,
        page: currentPage.value,
        size: PAGE_SIZE,
      })
      hasMore.value = !(res as any).last
      totalElements.value = (res as any).totalElements || 0
      currentPage.value++
      const content = (res as any).content || []
      updateCache(content)
      // 缓存到本地
      if (reset && isOfflineEnabled()) {
        await cacheAnimeList(content)
      }
      if (reset) {
        list.value = content
      } else {
        list.value.push(...content)
      }
    } catch (e: any) {
      // 网络失败时尝试缓存
      if (isOfflineEnabled() && reset) {
        const cached = await getCachedAnimeList()
        if (cached.length > 0) {
          list.value = cached
          updateCache(cached)
          toast.info('已从缓存加载')
          return
        }
      }
      toast.error(e.message || '搜索失败')
    } finally {
      loading.value = false
    }
  }

  // 加载更多（无限滚动）
  async function loadMore() {
    if (!hasMore.value || loading.value) return
    await search(false)
  }

  // 刷新统计
  async function refreshStats() {
    try {
      stats.value = await api.getStats()
    } catch (_) {}
  }

  // CRUD 操作
  async function addAnime(dto: Parameters<typeof api.addAnime>[0]) {
    const vo = await api.addAnime(dto)
    toast.success('已添加')
    await search()
    await refreshStats()
    return vo
  }

  async function updateAnime(id: number, dto: Parameters<typeof api.updateAnime>[1]) {
    const vo = await api.updateAnime(id, dto)
    toast.success('已保存')
    await search()
    await refreshStats()
    return vo
  }

  async function deleteAnime(id: number) {
    await api.deleteAnime(id)
    toast.success('已删除')
    await search()
    await refreshStats()
  }

  async function nextEp(id: number) {
    try {
      const vo = await api.nextEpisode(id)
      cache.value[id] = vo
      // 更新列表中的对应项
      const idx = list.value.findIndex((a) => a.id === id)
      if (idx >= 0) list.value[idx] = vo
      toast.success('集数已更新')
      await refreshStats()
    } catch (e: any) {
      if (e.message === 'reached_max') toast.info('已经是最后一集了')
      else toast.error(e.message || '更新失败')
    }
  }

  async function prevEp(id: number) {
    try {
      const vo = await api.prevEpisode(id)
      cache.value[id] = vo
      const idx = list.value.findIndex((a) => a.id === id)
      if (idx >= 0) list.value[idx] = vo
      toast.success('集数已更新')
      await refreshStats()
    } catch (e: any) {
      if (e.message === 'reached_min') toast.info('已经是第1集了')
      else toast.error(e.message || '更新失败')
    }
  }

  async function changeStatus(id: number, status: string) {
    try {
      const vo = await api.updateStatus(id, status)
      cache.value[id] = vo
      const idx = list.value.findIndex((a) => a.id === id)
      if (idx >= 0) list.value[idx] = vo
      toast.success('状态已更新')
      await refreshStats()
    } catch (e: any) {
      toast.error(e.message || '修改失败')
    }
  }

  async function matchBangumi(id: number) {
    const vo = await api.matchBangumi(id)
    cache.value[id] = vo
    const idx = list.value.findIndex((a) => a.id === id)
    if (idx >= 0) list.value[idx] = vo
    toast.success('匹配成功')
    return vo
  }

  // 批量操作
  async function batchDelete() {
    if (selectedIds.value.size === 0) return
    await api.batchDelete([...selectedIds.value])
    toast.success('批量删除成功')
    selectedIds.value.clear()
    await search()
    await refreshStats()
  }

  async function batchChangeStatus(status: AnimeStatus) {
    if (selectedIds.value.size === 0) return
    await api.batchUpdateStatus([...selectedIds.value], status)
    toast.success('批量更新成功')
    selectedIds.value.clear()
    await search()
    await refreshStats()
  }

  // 选择操作
  function toggleSelect(id: number, checked: boolean) {
    if (checked) selectedIds.value.add(id)
    else selectedIds.value.delete(id)
    // 触发响应式更新
    selectedIds.value = new Set(selectedIds.value)
  }

  function toggleSelectAll(checked: boolean) {
    if (checked) {
      list.value.forEach((a) => selectedIds.value.add(a.id))
    } else {
      selectedIds.value.clear()
    }
    selectedIds.value = new Set(selectedIds.value)
  }

  function clearSelection() {
    selectedIds.value.clear()
    selectedIds.value = new Set(selectedIds.value)
  }

  // 视图切换
  function setViewMode(mode: string) {
    viewMode.value = mode
    localStorage.setItem('otakulog-view', mode)
  }

  // 设置筛选条件并搜索
  function setFilter(filters: { name?: string; status?: string; tag?: string; sortBy?: string }) {
    if (filters.name !== undefined) searchName.value = filters.name
    if (filters.status !== undefined) filterStatus.value = filters.status
    if (filters.tag !== undefined) filterTag.value = filters.tag
    if (filters.sortBy !== undefined) sortBy.value = filters.sortBy
    search()
  }

  function resetFilters() {
    searchName.value = ''
    filterStatus.value = ''
    filterTag.value = ''
    sortBy.value = 'id-desc'
    search()
  }

  return {
    list,
    cache,
    loading,
    currentPage,
    hasMore,
    totalElements,
    searchName,
    filterStatus,
    filterTag,
    sortBy,
    viewMode,
    selectedIds,
    selectedCount,
    isEmpty,
    stats,
    search,
    loadMore,
    refreshStats,
    addAnime,
    updateAnime,
    deleteAnime,
    nextEp,
    prevEp,
    changeStatus,
    matchBangumi,
    batchDelete,
    batchChangeStatus,
    toggleSelect,
    toggleSelectAll,
    clearSelection,
    setViewMode,
    setFilter,
    resetFilters,
  }
})
