import request from './request'

export interface BangumiResult {
  id: number
  name: string
  nameCn: string
  image: string
  score: number
  eps: number
  date: string
  rank?: number
  isTracked?: boolean
}

export interface BangumiSubjectDetail {
  name: string
  nameCn: string
  summary: string
  rating: number
  ratingCount: number
  ratingDetails: { count: Record<number, number> }
  tags: { name: string; count: number }[]
}

export interface BangumiEpisode {
  sort: number
  name: string
  nameCn: string
  airdate: string
}

// 搜索 Bangumi
export function search(keyword: string, limit = 8) {
  return request.get('/bangumi/search', { params: { keyword, limit } }) as Promise<BangumiResult[]>
}

// 获取作品详情
export function getSubject(id: number) {
  return request.get(`/bangumi/subject/${id}`) as Promise<BangumiSubjectDetail>
}

// 获取剧集列表
export function getSubjectEpisodes(id: number) {
  return request.get(`/bangumi/subject/${id}/episodes`) as Promise<BangumiEpisode[]>
}

// 获取放送日历
export function getCalendar() {
  return request.get('/bangumi/calendar') as Promise<Record<string, unknown>[]>
}

// 排行榜
export function getRankings(sort = 'rank', limit = 20, offset = 0) {
  return request.get('/bangumi/rankings', { params: { sort, limit, offset } }) as Promise<BangumiResult[]>
}

// 当季新番
export function getSeasonAnime(sort = 'rank', limit = 20, offset = 0) {
  return request.get('/bangumi/season', { params: { sort, limit, offset } }) as Promise<BangumiResult[]>
}

// 从 Bangumi 导入
export function importFromBangumi(username: string) {
  return request.post(`/bangumi/import/${encodeURIComponent(username)}`) as Promise<Record<string, unknown>>
}

// 以图搜番
export function searchByImage(file: File) {
  const form = new FormData()
  form.append('image', file)
  return request.post('/tracemoe/search', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }) as Promise<Record<string, unknown>>
}
