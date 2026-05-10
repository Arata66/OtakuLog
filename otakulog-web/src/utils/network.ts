import { ref, onMounted, onUnmounted } from 'vue'
import { Network } from '@capacitor/network'
import { Capacitor } from '@capacitor/core'

// 全局网络状态
const isOnline = ref(navigator.onLine)
const connectionType = ref<string>('unknown')

let initialized = false

// 初始化 Capacitor 网络监听
async function initCapacitorNetwork() {
  if (!Capacitor.isNativePlatform()) return

  const status = await Network.getStatus()
  isOnline.value = status.connected
  connectionType.value = status.connectionType

  Network.addListener('networkStatusChange', (status) => {
    isOnline.value = status.connected
    connectionType.value = status.connectionType
  })
}

// 浏览器网络监听
function initBrowserNetwork() {
  if (Capacitor.isNativePlatform()) return

  const onOnline = () => { isOnline.value = true }
  const onOffline = () => { isOnline.value = false }

  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)

  return () => {
    window.removeEventListener('online', onOnline)
    window.removeEventListener('offline', onOffline)
  }
}

// 初始化（全局只执行一次）
function ensureInit() {
  if (initialized) return
  initialized = true

  if (Capacitor.isNativePlatform()) {
    initCapacitorNetwork()
  } else {
    initBrowserNetwork()
  }
}

// 在组件中使用的 composable
export function useNetwork() {
  ensureInit()

  return {
    isOnline,
    connectionType,
  }
}

// 直接获取当前状态（非响应式）
export function getOnlineStatus(): boolean {
  return isOnline.value
}
