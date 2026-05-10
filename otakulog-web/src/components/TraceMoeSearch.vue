<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import * as bangumiApi from '@/api/bangumi'

const { t } = useI18n()
const toast = useToast()

const results = ref<any[]>([])
const showResults = ref(false)
const searching = ref(false)

async function onFileSelect(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  searching.value = true
  toast.info('正在识别...')
  try {
    const res = (await bangumiApi.searchByImage(file)) as any
    results.value = res.allResults || [res]
    showResults.value = true
  } catch (err: any) {
    toast.error(err.message || '识别失败')
  }
  searching.value = false
  // 清空 input 以便重复选择同一文件
  ;(e.target as HTMLInputElement).value = ''
}

function close() {
  showResults.value = false
}

const emit = defineEmits<{
  addToTracking: [name: string]
}>()

function addTracking(name: string) {
  showResults.value = false
  emit('addToTracking', name)
}
</script>

<template>
  <div>
    <label class="btn-h" style="cursor: pointer">
      📷 以图搜番
      <input type="file" accept="image/*" style="display: none" @change="onFileSelect" />
    </label>

    <!-- 搜索结果弹窗 -->
    <div v-if="showResults" class="detail-overlay" @click.self="close">
      <div class="detail-card" style="max-width: 480px">
        <div class="detail-body">
          <div class="detail-title">以图搜番结果</div>
          <div v-for="(r, i) in results" :key="i" style="padding: 12px; margin-bottom: 8px; background: var(--bg); border-radius: 8px; border: 1px solid var(--border-light)">
            <img v-if="r.image" :src="r.image" style="width: 100%; border-radius: 8px; margin-bottom: 8px" @error="($event.target as HTMLImageElement).style.display='none'" />
            <div style="font-weight: 600; font-size: 0.95em; margin-bottom: 4px">{{ r.animeName || '未知' }}</div>
            <div style="font-size: 0.82em; color: var(--text-mid); display: flex; gap: 12px">
              <span>{{ r.episode != null ? `EP${r.episode}` : '' }}</span>
              <span :style="{ color: r.confidence >= 90 ? 'var(--sage)' : r.confidence >= 70 ? 'var(--amber)' : 'var(--rose)' }">
                置信度 {{ r.confidence != null ? r.confidence + '%' : '-' }}
              </span>
            </div>
            <button class="a-btn" style="margin-top: 8px" @click="addTracking(r.animeName)">添加追踪</button>
          </div>
          <div class="modal-actions">
            <button class="btn-h" @click="close">{{ t('bangumi.close') }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
