import { ref, watch } from 'vue'

type Accent = 'indigo' | 'jade' | 'violet' | 'coral' | 'amber' | 'rose'

const THEME_KEY = 'otakulog-theme'
const ACCENT_KEY = 'otakulog-accent'

const isDark = ref(localStorage.getItem(THEME_KEY) === 'dark')
const accent = ref<Accent>((localStorage.getItem(ACCENT_KEY) as Accent) || 'indigo')

// 初始化 DOM 属性
function applyTheme() {
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : '')
  document.documentElement.setAttribute('data-accent', accent.value)
}

applyTheme()

watch(isDark, (v) => {
  localStorage.setItem(THEME_KEY, v ? 'dark' : 'light')
  applyTheme()
})

watch(accent, (v) => {
  localStorage.setItem(ACCENT_KEY, v)
  applyTheme()
})

export function useTheme() {
  function toggleDark() {
    isDark.value = !isDark.value
  }

  function setAccent(a: Accent) {
    accent.value = a
  }

  return { isDark, accent, toggleDark, setAccent }
}
