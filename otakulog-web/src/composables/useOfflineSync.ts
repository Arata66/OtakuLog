import { watch } from 'vue'
import { useNetwork } from '@/utils/network'
import { syncOfflineQueue, isOfflineEnabled } from '@/utils/offlineApi'
import { getQueueSize } from '@/utils/offlineQueue'
import { useToast } from './useToast'
import { ref } from 'vue'

const pendingCount = ref(0)

export function useOfflineSync() {
  const { isOnline } = useNetwork()
  const toast = useToast()

  // 监听网络状态变化，恢复在线时自动重放队列
  watch(isOnline, async (online) => {
    if (!isOfflineEnabled()) return

    if (online) {
      const count = await getQueueSize()
      if (count > 0) {
        toast.info(`正在同步 ${count} 个离线操作...`)
        const result = await syncOfflineQueue()
        pendingCount.value = await getQueueSize()

        if (result.success > 0) {
          toast.success(`已同步 ${result.success} 个操作`)
        }
        if (result.failed > 0) {
          toast.error(`${result.failed} 个操作同步失败，稍后重试`)
        }
      }
    }
  })

  // 更新待处理计数
  async function refreshPendingCount() {
    if (isOfflineEnabled()) {
      pendingCount.value = await getQueueSize()
    }
  }

  return {
    pendingCount,
    refreshPendingCount,
  }
}
