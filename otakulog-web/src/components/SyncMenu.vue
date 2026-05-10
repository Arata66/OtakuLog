<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { useAnimeStore } from '@/stores/anime'
import * as bangumiApi from '@/api/bangumi'
import { request } from '@/api'

const { t } = useI18n()
const toast = useToast()
const store = useAnimeStore()

const showMenu = ref(false)
const importing = ref(false)

async function onExport() {
  try {
    const data = (await request.get('/anime/export', { responseType: 'text' })) as unknown as string
    const blob = new Blob([data], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'otakulog_export.json'
    a.click()
    URL.revokeObjectURL(url)
    toast.success(t('toast.exportSuccess'))
  } catch (e: any) {
    toast.error(e.message || '导出失败')
  }
  showMenu.value = false
}

function onImport() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json'
  input.onchange = async (e) => {
    const file = (e.target as HTMLInputElement).files?.[0]
    if (!file) return
    try {
      const text = await file.text()
      await request.post('/anime/import', text)
      toast.success(t('toast.importSuccess'))
      await store.search()
      await store.refreshStats()
    } catch (err: any) {
      toast.error(err.message || '导入失败')
    }
  }
  input.click()
  showMenu.value = false
}

async function onImportBangumi() {
  const username = prompt('请输入 Bangumi 用户名：')
  if (!username?.trim()) return
  importing.value = true
  try {
    const res = (await bangumiApi.importFromBangumi(username.trim())) as any
    toast.success(`导入完成：新增 ${res.created}，跳过 ${res.skipped}，共 ${res.total}`)
    await store.search()
    await store.refreshStats()
  } catch (e: any) {
    toast.error(e.message || '导入失败')
  }
  importing.value = false
  showMenu.value = false
}
</script>

<template>
  <div class="sync-wrap">
    <button class="btn-h" @click="showMenu = !showMenu">同步</button>
    <div v-if="showMenu" class="sync-dropdown">
      <button class="sync-menu-btn" @click="onExport">{{ t('btn.export') }}</button>
      <button class="sync-menu-btn" @click="onImport">{{ t('btn.import') }}</button>
      <button class="sync-menu-btn" :disabled="importing" @click="onImportBangumi">
        {{ importing ? '导入中...' : '从 Bangumi 导入' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.sync-wrap {
  position: relative;
}
.sync-dropdown {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 4px;
  background: var(--card);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  box-shadow: var(--shadow-hover);
  z-index: 50;
  min-width: 160px;
}
.sync-menu-btn {
  display: block;
  width: 100%;
  padding: 10px 16px;
  border: none;
  background: none;
  text-align: left;
  cursor: pointer;
  font-size: 0.85em;
  color: var(--text);
  transition: background 0.2s;
  font-family: inherit;
}
.sync-menu-btn:hover {
  background: var(--bg);
}
.sync-menu-btn:first-child {
  border-radius: 10px 10px 0 0;
}
.sync-menu-btn:last-child {
  border-radius: 0 0 10px 10px;
}
</style>
