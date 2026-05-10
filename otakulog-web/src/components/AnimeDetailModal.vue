<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useAnimeStore } from '@/stores/anime'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { request } from '@/api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ animeId: number | null }>()
const emit = defineEmits<{ close: []; edit: [id: number] }>()

const store = useAnimeStore()
const { t } = useI18n()
const toast = useToast()

const bangumiDetail = ref<any>(null)
const bangumiEpisodes = ref<any[]>([])
const loadingBangumi = ref(false)

const anime = computed(() => (props.animeId != null ? store.cache[props.animeId] : null))

const pct = computed(() => {
  if (!anime.value) return 0
  return anime.value.totalEpisodes > 0
    ? Math.round((anime.value.currentEpisode / anime.value.totalEpisodes) * 100)
    : 0
})

function scoreClass(s: number) {
  return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'
}

function renderRemark(text: string) {
  if (!text) return ''
  const raw = marked.parse(text, { breaks: true, gfm: true }) as string
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
}

async function loadBangumi(bangumiId: number) {
  loadingBangumi.value = true
  try {
    const [detail, episodes] = await Promise.all([
      request.get(`/bangumi/subject/${bangumiId}`).catch(() => null),
      request.get(`/bangumi/subject/${bangumiId}/episodes`).catch(() => null),
    ])
    bangumiDetail.value = detail
    bangumiEpisodes.value = (episodes as any) || []
  } catch (_) {}
  loadingBangumi.value = false
}

async function matchBangumiForAnime() {
  if (!anime.value) return
  try {
    const vo = await store.matchBangumi(anime.value.id)
    if (vo?.bangumiId) loadBangumi(vo.bangumiId)
  } catch (e: any) {
    toast.error(e.message || '匹配失败')
  }
}

watch(
  () => props.animeId,
  (id) => {
    if (id == null) return
    const a = store.cache[id]
    if (a?.bangumiId) loadBangumi(a.bangumiId)
    else {
      bangumiDetail.value = null
      bangumiEpisodes.value = []
    }
  },
  { immediate: true },
)
</script>

<template>
  <div v-if="anime" class="detail-overlay" @click.self="emit('close')">
    <div class="detail-card">
      <div class="detail-header">
        <div class="detail-cover-wrap">
          <img
            v-if="anime.coverUrl"
            :src="anime.coverUrl"
            class="detail-cover"
            @error="($event.target as HTMLImageElement).outerHTML='<div class=detail-cover-empty>' + anime.name.charAt(0) + '</div>'"
          />
          <div v-else class="detail-cover-empty">{{ anime.name.charAt(0) }}</div>
          <span class="detail-badge" :class="anime.status">{{ t(`status.${anime.status}` as any) }}</span>
        </div>
        <div class="detail-info-col">
          <div class="detail-title">{{ anime.name }}</div>
          <div class="detail-meta">
            <span class="detail-tag" style="background: var(--bg); border: 1px solid var(--border-light); color: var(--text-mid)">{{ anime.season }}</span>
            <span
              class="detail-tag"
              :class="scoreClass(anime.score)"
              :style="{ fontFamily: 'var(--serif)', background: anime.score >= 8 ? 'var(--sage-soft)' : anime.score >= 6 ? 'var(--amber-soft)' : 'var(--rose-soft)', color: anime.score >= 8 ? '#5a8a60' : anime.score >= 6 ? '#a08050' : '#a06070' }"
            >{{ anime.score }}</span>
          </div>
          <div class="detail-info">
            <div class="detail-info-item">
              <div class="detail-info-label">{{ t('detail.status') }}</div>
              <div class="detail-info-val">{{ t(`status.${anime.status}` as any) }}</div>
            </div>
            <div class="detail-info-item">
              <div class="detail-info-label">{{ t('detail.episodes') }}</div>
              <div class="detail-info-val">{{ anime.currentEpisode }} / {{ anime.totalEpisodes }}</div>
            </div>
            <div class="detail-info-item">
              <div class="detail-info-label">{{ t('detail.startDate') }}</div>
              <div class="detail-info-val">{{ anime.startDate || '-' }}</div>
            </div>
            <div class="detail-info-item">
              <div class="detail-info-label">{{ t('detail.endDate') }}</div>
              <div class="detail-info-val">{{ anime.endDate || '-' }}</div>
            </div>
          </div>
          <a
            v-if="anime.bangumiId"
            :href="'https://bgm.tv/subject/' + anime.bangumiId"
            target="_blank"
            rel="noopener"
            class="detail-bangumi-link"
          >在 Bangumi 查看 ↗</a>
        </div>
      </div>
      <div class="detail-body">
        <div class="detail-progress-wrap">
          <div class="detail-progress-label">
            <span>{{ t('detail.progress') }}</span>
            <span>{{ pct }}%</span>
          </div>
          <div class="detail-progress">
            <div class="detail-progress-bar" :class="anime.status" :style="{ width: pct + '%' }" />
          </div>
        </div>
        <div v-if="anime.remark" class="detail-remark" v-html="renderRemark(anime.remark)" />

        <!-- Bangumi 详情 -->
        <div v-if="loadingBangumi" class="bangumi-loading">
          <div class="skeleton skeleton-text long" />
          <div class="skeleton skeleton-text medium" />
        </div>
        <template v-else-if="bangumiDetail">
          <div v-if="bangumiDetail.rating" class="bg-section">
            <div class="bg-section-title">Bangumi 评分</div>
            <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 8px">
              <span style="font-size: 28px; font-family: var(--serif); font-weight: 700">{{ bangumiDetail.rating.toFixed(1) }}</span>
              <span style="color: var(--text-faint); font-size: 13px">{{ bangumiDetail.ratingCount || 0 }} 人评分</span>
            </div>
          </div>
          <div v-if="bangumiDetail.tags?.length" class="bg-section">
            <div class="bg-section-title">社区标签</div>
            <div class="bg-tags">
              <span v-for="tag in bangumiDetail.tags" :key="tag.name || tag" class="bg-tag">{{ tag.name || tag }}</span>
            </div>
          </div>
          <div v-if="bangumiDetail.summary" class="bg-section">
            <div class="bg-section-title">简介</div>
            <div class="bg-summary">{{ bangumiDetail.summary.length > 200 ? bangumiDetail.summary.substring(0, 200) + '...' : bangumiDetail.summary }}</div>
          </div>
        </template>
        <div v-else-if="anime.bangumiId === 0 || anime.bangumiId == null" style="text-align: center; padding: 12px">
          <button class="detail-match-btn" @click="matchBangumiForAnime">🔗 匹配 Bangumi 链接</button>
        </div>

        <!-- 剧集列表 -->
        <div v-if="bangumiEpisodes.length > 0" class="bg-section">
          <div class="bg-section-title">剧集列表 ({{ bangumiEpisodes.length }})</div>
          <div class="bg-episodes">
            <div
              v-for="ep in bangumiEpisodes.slice(0, 24)"
              :key="ep.sort"
              class="bg-ep"
              :class="ep.airdate && new Date(ep.airdate) <= new Date() ? 'aired' : 'upcoming'"
            >
              <span class="bg-ep-num">{{ ep.sort }}</span>
              <span class="bg-ep-name">{{ ep.nameCn || ep.name || `EP${ep.sort}` }}</span>
              <span class="bg-ep-date">{{ ep.airdate || '' }}</span>
            </div>
          </div>
        </div>

        <div class="detail-actions">
          <button class="a-btn" @click="emit('edit', anime.id)">{{ t('detail.edit') }}</button>
          <button class="a-btn ep-btn" @click="store.prevEp(anime.id)">{{ t('detail.prevEp') }}</button>
          <button class="a-btn ep-btn" @click="store.nextEp(anime.id)">{{ t('detail.nextEp') }}</button>
          <button class="a-btn del" @click="store.deleteAnime(anime.id); emit('close')">{{ t('detail.delete') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
