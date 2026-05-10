import request from './request'
import type { AnimeVO } from './types'

export interface AnimeGroupDTO {
  id: number
  name: string
  description: string
  color: string
  sortOrder: number
  animeCount: number
}

// 获取所有分组
export function getAllGroups() {
  return request.get('/groups') as Promise<AnimeGroupDTO[]>
}

// 创建分组
export function createGroup(name: string, description = '', color = '#4a6ad0') {
  return request.post('/groups', { name, description, color }) as Promise<AnimeGroupDTO>
}

// 删除分组
export function deleteGroup(id: number) {
  return request.delete(`/groups/${id}`) as Promise<void>
}

// 获取分组中的番剧
export function getGroupAnime(id: number) {
  return request.get(`/groups/${id}/anime`) as Promise<AnimeVO[]>
}

// 添加番剧到分组
export function addAnimeToGroup(groupId: number, animeId: number) {
  return request.post(`/groups/${groupId}/anime/${animeId}`) as Promise<void>
}

// 从分组移除番剧
export function removeAnimeFromGroup(groupId: number, animeId: number) {
  return request.delete(`/groups/${groupId}/anime/${animeId}`) as Promise<void>
}

// 获取番剧所属分组
export function getAnimeGroups(animeId: number) {
  return request.get(`/anime/${animeId}/groups`) as Promise<number[]>
}
