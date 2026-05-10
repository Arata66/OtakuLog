import { openDB, type IDBPDatabase } from 'idb'
import type { AnimeVO } from '@/api/types'

const DB_NAME = 'otakulog-cache'
const DB_VERSION = 1
const ANIME_STORE = 'anime'
const META_STORE = 'meta'

interface CacheDB {
  [ANIME_STORE]: {
    key: number
    value: AnimeVO
    indexes: { 'status': string }
  }
  [META_STORE]: {
    key: string
    value: { key: string; value: unknown; updatedAt: number }
  }
}

let dbPromise: Promise<IDBPDatabase<CacheDB>> | null = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB<CacheDB>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(ANIME_STORE)) {
          const store = db.createObjectStore(ANIME_STORE, { keyPath: 'id' })
          store.createIndex('status', 'status')
        }
        if (!db.objectStoreNames.contains(META_STORE)) {
          db.createObjectStore(META_STORE, { keyPath: 'key' })
        }
      },
    })
  }
  return dbPromise
}

// 缓存番剧列表
export async function cacheAnimeList(animeList: AnimeVO[]) {
  const db = await getDB()
  const tx = db.transaction(ANIME_STORE, 'readwrite')
  await Promise.all([
    ...animeList.map((a) => tx.store.put(a)),
    tx.done,
  ])
}

// 获取缓存的番剧列表
export async function getCachedAnimeList(): Promise<AnimeVO[]> {
  const db = await getDB()
  return db.getAll(ANIME_STORE)
}

// 缓存单个番剧
export async function cacheAnime(anime: AnimeVO) {
  const db = await getDB()
  await db.put(ANIME_STORE, anime)
}

// 获取缓存的单个番剧
export async function getCachedAnime(id: number): Promise<AnimeVO | undefined> {
  const db = await getDB()
  return db.get(ANIME_STORE, id)
}

// 删除缓存的番剧
export async function deleteCachedAnime(id: number) {
  const db = await getDB()
  await db.delete(ANIME_STORE, id)
}

// 缓存元数据（统计信息等）
export async function cacheMeta(key: string, value: unknown) {
  const db = await getDB()
  await db.put(META_STORE, { key, value, updatedAt: Date.now() })
}

// 获取缓存的元数据
export async function getCachedMeta(key: string): Promise<unknown | undefined> {
  const db = await getDB()
  const meta = await db.get(META_STORE, key)
  return meta?.value
}

// 清除所有缓存
export async function clearCache() {
  const db = await getDB()
  await db.clear(ANIME_STORE)
  await db.clear(META_STORE)
}
