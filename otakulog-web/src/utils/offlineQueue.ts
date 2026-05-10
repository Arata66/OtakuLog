import { openDB, type IDBPDatabase } from 'idb'
import { getOnlineStatus } from './network'

const DB_NAME = 'otakulog-offline-queue'
const DB_VERSION = 1
const QUEUE_STORE = 'queue'

interface QueueItem {
  id?: number
  method: 'POST' | 'PUT' | 'DELETE'
  url: string
  body?: unknown
  timestamp: number
}

interface QueueDB {
  [QUEUE_STORE]: {
    key: number
    value: QueueItem
    autoIncrement: true
  }
}

let dbPromise: Promise<IDBPDatabase<QueueDB>> | null = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB<QueueDB>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(QUEUE_STORE)) {
          db.createObjectStore(QUEUE_STORE, { keyPath: 'id', autoIncrement: true })
        }
      },
    })
  }
  return dbPromise
}

// 添加操作到离线队列
export async function enqueue(item: Omit<QueueItem, 'id' | 'timestamp'>) {
  const db = await getDB()
  await db.add(QUEUE_STORE, { ...item, timestamp: Date.now() })
}

// 获取所有待处理的操作
export async function getPending(): Promise<QueueItem[]> {
  const db = await getDB()
  return db.getAll(QUEUE_STORE)
}

// 删除已处理的操作
export async function dequeue(id: number) {
  const db = await getDB()
  await db.delete(QUEUE_STORE, id)
}

// 清空队列
export async function clearQueue() {
  const db = await getDB()
  await db.clear(QUEUE_STORE)
}

// 获取队列长度
export async function getQueueSize(): Promise<number> {
  const db = await getDB()
  return db.count(QUEUE_STORE)
}

// 重放队列中的所有操作
export async function replayQueue(
  executor: (item: QueueItem) => Promise<void>
): Promise<{ success: number; failed: number }> {
  if (!getOnlineStatus()) {
    return { success: 0, failed: 0 }
  }

  const pending = await getPending()
  let success = 0
  let failed = 0

  for (const item of pending) {
    try {
      await executor(item)
      if (item.id != null) {
        await dequeue(item.id)
      }
      success++
    } catch {
      failed++
      // 失败时保留队列项，下次重试
    }
  }

  return { success, failed }
}
