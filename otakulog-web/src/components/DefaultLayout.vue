<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const router = useRouter()
const { t, toggleLang, currentLang } = useI18n()
const { isDark, accent, toggleDark, setAccent } = useTheme()

const accentPickerRef = ref<HTMLElement | null>(null)
const showAccentPicker = ref(false)

const tabs = [
  { name: 'list', path: '/list', icon: '📋' },
  { name: 'charts', path: '/charts', icon: '📊' },
  { name: 'calendar', path: '/calendar', icon: '📅' },
  { name: 'timeline', path: '/timeline', icon: '⏳' },
]

const accents = ['indigo', 'jade', 'violet', 'coral', 'amber', 'rose'] as const

function switchTab(path: string) {
  router.push(path)
}

function toggleAccentPicker() {
  showAccentPicker.value = !showAccentPicker.value
}

function pickAccent(a: string) {
  setAccent(a as any)
  showAccentPicker.value = false
}
</script>

<template>
  <div class="app">
    <!-- 顶部导航 -->
    <header class="header">
      <div>
        <div class="logo">OtakuLog</div>
        <div class="logo-sub">{{ t('app.subtitle') }}</div>
      </div>
      <div class="header-right">
        <!-- 主题色选择器 -->
        <div class="accent-picker-wrap">
          <button class="btn-h ghost" @click="toggleAccentPicker">🎨</button>
          <div v-show="showAccentPicker" class="accent-picker active">
            <div
              v-for="a in accents"
              :key="a"
              class="accent-swatch"
              :class="{ active: accent === a }"
              :data-accent="a"
              :style="{ background: `var(--${a === 'indigo' ? 'primary' : a})` }"
              @click="pickAccent(a)"
            />
          </div>
        </div>
        <button class="btn-h ghost" @click="toggleDark">{{ isDark ? '☀' : '☽' }}</button>
        <button class="btn-h" @click="toggleLang">{{ currentLang === 'zh' ? 'EN' : '中' }}</button>
      </div>
    </header>

    <!-- Tab 栏 -->
    <nav class="tabs">
      <div
        v-for="tab in tabs"
        :key="tab.name"
        class="tab"
        :class="{ active: route.path === tab.path }"
        @click="switchTab(tab.path)"
      >
        {{ t(`tab.${tab.name}` as any) }}
      </div>
    </nav>

    <!-- 页面内容 -->
    <slot />

    <!-- 移动端底部导航 -->
    <nav class="mobile-nav">
      <div class="mobile-nav-inner">
        <button
          v-for="(tab, i) in tabs"
          :key="tab.name"
          class="mobile-nav-btn"
          :class="{ active: route.path === tab.path }"
          @click="switchTab(tab.path)"
        >
          <span class="mobile-nav-icon">{{ tab.icon }}</span>
          <span>{{ t(`tab.${tab.name}` as any) }}</span>
        </button>
      </div>
    </nav>
  </div>
</template>

<style scoped>
.accent-picker-wrap {
  position: relative;
}
.accent-picker {
  display: none;
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 4px;
  background: var(--card);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  box-shadow: var(--shadow-hover);
  padding: 12px;
  z-index: 50;
}
.accent-picker.active {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  width: 180px;
}
.accent-swatch {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
}
.accent-swatch:hover {
  transform: scale(1.15);
}
.accent-swatch.active {
  border-color: var(--text);
}
</style>
