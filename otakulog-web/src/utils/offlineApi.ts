import { Capacitor } from '@capacitor/core'
import request from '@/api/request'
import { getOnlineStatus } from './network'
import {
  cacheAnimeList,
  getCachedAnimeList,
  cacheAnime,
  getCachedAnime,
  deleteCachedAnime,
  cacheMeta,
  getCachedMeta,
} from './cache'
import { enqueue, replayQueue } from './offlineQueue'
import type { AnimeVO } from '@/api/types'

// 是否启用离线功能（仅在原生平台启用）
export function isOfflineEnabled(): boolean {
  return Capacitor.isNativePlatform()
}

// 离线感知的 GET 请求
export async function offlineGet<T>(
  url: string,
  cacheKey?: string,
  params?: Record<string, unknown>
): Promise<T> {
  if (!isOfflineEnabled()) {
    return request.get(url, { params }) as Promise<T>
  }

  if (getOnlineStatus()) {
    try {
      const data = await request.get(url, { params }) as T
      // 缓存响应
      if (cacheKey) {
        await cacheMeta(cacheKey, data)
      }
      return data
    } catch (err) {
      // 网络失败时尝试缓存
      const cached = cacheKey ? await getCachedMeta(cacheKey) : undefined
      if (cached) return cached as T
      throw err
    }
  }

  // 离线：从缓存读取
  const cached = cacheKey ? await getCachedMeta(cacheKey) : undefined
  if (cached) return cached as T
  throw new Error('离线状态且无缓存数据')
}

// 离线感知的番剧列表获取
export async function offlineGetAnimeList(): Promise<AnimeVO[]> {
  if (!isOfflineEnabled()) {
    return request.get('/anime/search') as Promise<AnimeVO[]>
  }

  if (getOnlineStatus()) {
    try {
      const data = await request.get('/anime/search') as AnimeVO[]
      await cacheAnimeList(data)
      return data
    } catch {
      const cached = await getCachedAnimeList()
      if (cached.length > 0) return cached
      throw new Error('获取番剧列表失败')
    }
  }

  const cached = await getCachedAnimeList()
  return cached
}

// 离线感知的写操作
export async function offlinePost(url: string, body?: unknown): Promise<unknown> {
  if (!isOfflineEnabled() || getOnlineStatus()) {
    return request.post(url, body)
  }

  // 离线：入队
  await enqueue({ method: 'POST', url, body })
  return { queued: true }
}

export async function offlinePut(url: string, body?: unknown): Promise<unknown> {
  if (!isOfflineEnabled() || getOnlineStatus()) {
    return request.put(url, body)
  }

  await enqueue({ method: 'PUT', url, body })
  return { queued: true }
}

export async function offlineDelete(url: string): Promise<unknown> {
  if (!isOfflineEnabled() || getOnlineStatus()) {
    return request.delete(url)
  }

  await enqueue({ method: 'DELETE', url })
  return { queued: true }
}

// 重放离线队列
export async function syncOfflineQueue(): Promise<{ success: number; failed: number }> {
  if (!isOfflineEnabled()) return { success: 0, failed: 0 }

  return replayQueue(async (item) => {
    switch (item.method) {
      case 'POST':
        await request.post(item.url, item.body)
        break
      case 'PUT':
        await request.put(item.url, item.body)
        break
      case 'DELETE':
        await request.delete(item.url)
        break
    }
  })
}
