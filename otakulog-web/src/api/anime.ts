import request from './request'
import type { AnimeVO, AnimeDTO, AnimeUpdateDTO, BatchRequest, PageResult } from './types'

// 添加番剧
export function addAnime(dto: AnimeDTO) {
  return request.post('/anime/add', dto) as Promise<AnimeVO>
}

// 更新番剧
export function updateAnime(id: number, dto: AnimeUpdateDTO) {
  return request.post(`/anime/${id}/update`, dto) as Promise<AnimeVO>
}

// 删除番剧
export function deleteAnime(id: number) {
  return request.delete(`/anime/${id}`) as Promise<void>
}

// 下一集
export function nextEpisode(id: number) {
  return request.post(`/anime/${id}/next-episode`) as Promise<AnimeVO>
}

// 上一集
export function prevEpisode(id: number) {
  return request.post(`/anime/${id}/prev-episode`) as Promise<AnimeVO>
}

// 更新状态
export function updateStatus(id: number, status: string) {
  return request.post(`/anime/${id}/status`, null, { params: { status } }) as Promise<AnimeVO>
}

// 批量删除
export function batchDelete(ids: number[]) {
  return request.post('/anime/batch-delete', { ids } as BatchRequest) as Promise<void>
}

// 批量更新状态
export function batchUpdateStatus(ids: number[], status: string) {
  return request.post('/anime/batch-status', { ids, status } as BatchRequest) as Promise<void>
}

// 搜索番剧（不分页）
export function searchAnime(params: {
  name?: string
  status?: string
  sortBy?: string
  tag?: string
}) {
  return request.get('/anime/search', { params }) as Promise<AnimeVO[]>
}

// 分页搜索番剧
export function searchAnimePaged(params: {
  name?: string
  status?: string
  page?: number
  size?: number
  sortBy?: string
  tag?: string
}) {
  return request.get('/anime/page', { params }) as Promise<PageResult<AnimeVO>>
}

// 统计概览
export function getStats() {
  return request.get('/anime/stats') as Promise<Record<string, unknown>>
}

// 详细统计
export function getDetailedStats() {
  return request.get('/anime/stats/detailed') as Promise<Record<string, unknown>>
}

// 季度统计
export function getSeasonStats() {
  return request.get('/anime/stats/seasons') as Promise<Record<string, unknown>>
}

// 增强统计
export function getEnhancedStats() {
  return request.get('/anime/stats/enhanced') as Promise<Record<string, unknown>>
}

// 时间线
export function getTimeline(mode: string = 'watch') {
  return request.get('/anime/timeline', { params: { mode } }) as Promise<AnimeVO[]>
}

// 日历数据
export function getCalendar() {
  return request.get('/anime/calendar') as Promise<Record<number, AnimeVO[]>>
}

// 放送时间表
export function getAiringSchedule() {
  return request.get('/anime/airing-schedule') as Promise<Record<string, unknown>>
}

// 匹配 Bangumi
export function matchBangumi(id: number) {
  return request.post(`/anime/${id}/match-bangumi`) as Promise<AnimeVO>
}

// 批量匹配 Bangumi
export function batchMatchBangumi() {
  return request.post('/anime/batch-match-bangumi') as Promise<Record<string, unknown>>
}

// 推荐
export function getRecommendations() {
  return request.get('/anime/recommendations') as Promise<Record<string, unknown>[]>
}

// 热力图
export function getHeatmap() {
  return request.get('/anime/heatmap') as Promise<Record<string, number>>
}

// 导出
export function exportJson() {
  return request.get('/anime/export', { responseType: 'text' }) as Promise<string>
}

// 导入
export function importJson(json: string) {
  return request.post('/anime/import', json) as Promise<Record<string, unknown>>
}

// 重新排序
export function reorderAnime(orders: { id: number; sortOrder: number }[]) {
  return request.post('/anime/reorder', orders) as Promise<void>
}
