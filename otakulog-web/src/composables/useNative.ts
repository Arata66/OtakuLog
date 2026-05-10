import { Capacitor } from '@capacitor/core'
import { StatusBar, Style } from '@capacitor/status-bar'
import { SplashScreen } from '@capacitor/splash-screen'
import { Share } from '@capacitor/share'
import { App } from '@capacitor/app'
import { useRouter } from 'vue-router'

// 初始化状态栏
export async function initStatusBar() {
  if (!Capacitor.isNativePlatform()) return

  try {
    await StatusBar.setStyle({ style: Style.Dark })
    await StatusBar.setBackgroundColor({ color: '#1a1b2e' })
  } catch {
    // 部分平台可能不支持
  }
}

// 隐藏启动屏
export async function hideSplash() {
  if (!Capacitor.isNativePlatform()) return

  try {
    await SplashScreen.hide()
  } catch {
    // 忽略
  }
}

// 分享番剧信息
export async function shareAnime(title: string, text: string, url?: string) {
  if (!Capacitor.isNativePlatform()) {
    // Web 端使用 clipboard fallback
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      return false
    }
  }

  try {
    await Share.share({
      title,
      text,
      url,
      dialogTitle: '分享番剧',
    })
    return true
  } catch {
    return false
  }
}

// 监听应用生命周期
export function useAppLifecycle() {
  const router = useRouter()

  if (!Capacitor.isNativePlatform()) return

  // 监听返回按钮
  App.addListener('backButton', ({ canGoBack }) => {
    if (canGoBack) {
      router.back()
    } else {
      App.exitApp()
    }
  })

  // 监听应用状态变化
  App.addListener('appStateChange', ({ isActive }) => {
    if (isActive) {
      // 应用恢复前台时可以刷新数据
    }
  })
}
