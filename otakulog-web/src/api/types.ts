// 与后端 ApiResponse<T> 对应
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

// 番剧状态枚举
export type AnimeStatus = 'WATCHING' | 'FINISHED' | 'PLANNING' | 'DROPPED'

// 番剧视图对象 — 对应后端 AnimeVO
export interface AnimeVO {
  id: number
  name: string
  currentEpisode: number
  totalEpisodes: number
  status: AnimeStatus
  statusDisplay: string
  score: number
  season: string
  remark: string
  progress: number
  coverUrl: string
  startDate: string
  endDate: string
  tags: string
  sortOrder: number
  broadcastDay: number
  bangumiId: number
  watchStartDate: string
  legacy: boolean
}

// 添加番剧 — 对应后端 AnimeDTO
export interface AnimeDTO {
  name: string
  totalEpisodes: number
  season: string
  score: number
  remark?: string
  coverUrl?: string
  startDate?: string
  endDate?: string
  tags?: string
  broadcastDay?: number
  bangumiId?: number
  watchStartDate?: string
  status?: AnimeStatus
  legacy?: boolean
}

// 更新番剧 — 对应后端 AnimeUpdateDTO
export interface AnimeUpdateDTO {
  name?: string
  totalEpisodes?: number
  season?: string
  score?: number
  remark?: string
  coverUrl?: string
  startDate?: string
  endDate?: string
  tags?: string
  broadcastDay?: number
  bangumiId?: number
  status?: AnimeStatus
}

// 批量请求
export interface BatchRequest {
  ids: number[]
  action?: string
  status?: AnimeStatus
}

// 分页响应
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}
