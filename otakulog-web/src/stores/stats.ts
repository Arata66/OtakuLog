import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api/anime'
import { request } from '@/api'
import { useToast } from '@/composables/useToast'

export const useStatsStore = defineStore('stats', () => {
  const toast = useToast()

  // 详细统计
  const detailed = ref<Record<string, unknown>>({})
  // 季度统计
  const seasons = ref<{ season: string; count: number; avgScore: number | null }[]>([])
  // 增强统计
  const enhanced = ref<Record<string, unknown>>({})
  // 热力图
  const heatmap = ref<Record<string, number>>({})
  // 推荐
  const recommendations = ref<Record<string, unknown>[]>([])
  // 日历/放送时间表
  const schedule = ref<Record<string, unknown>>({})
  // 时间线
  const timeline = ref<any[]>([])

  const loading = ref(false)

  async function loadDetailed() {
    try {
      detailed.value = (await api.getDetailedStats()) as Record<string, unknown>
    } catch (_) {}
  }

  async function loadSeasons() {
    try {
      const res = (await api.getSeasonStats()) as Record<string, unknown>
      seasons.value = (res.seasons as any[]) || []
    } catch (_) {}
  }

  async function loadEnhanced() {
    try {
      enhanced.value = (await api.getEnhancedStats()) as Record<string, unknown>
    } catch (_) {}
  }

  async function loadHeatmap() {
    try {
      heatmap.value = (await api.getHeatmap()) as Record<string, number>
    } catch (_) {}
  }

  async function loadRecommendations() {
    try {
      recommendations.value = (await api.getRecommendations()) as Record<string, unknown>[]
    } catch (_) {}
  }

  async function loadSchedule() {
    try {
      schedule.value = (await api.getAiringSchedule()) as Record<string, unknown>
    } catch (_) {}
  }

  async function loadTimeline(mode: string = 'watch') {
    try {
      timeline.value = await api.getTimeline(mode)
    } catch (_) {}
  }

  // 一次性加载所有图表数据
  async function loadAllCharts() {
    loading.value = true
    await Promise.all([loadDetailed(), loadSeasons(), loadEnhanced(), loadHeatmap(), loadRecommendations()])
    loading.value = false
  }

  return {
    detailed,
    seasons,
    enhanced,
    heatmap,
    recommendations,
    schedule,
    timeline,
    loading,
    loadDetailed,
    loadSeasons,
    loadEnhanced,
    loadHeatmap,
    loadRecommendations,
    loadSchedule,
    loadTimeline,
    loadAllCharts,
  }
})
