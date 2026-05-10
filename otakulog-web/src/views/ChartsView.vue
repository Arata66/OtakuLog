<script setup lang="ts">
import { onMounted, ref, nextTick, watch, onUnmounted } from 'vue'
import { useStatsStore } from '@/stores/stats'
import { useI18n } from '@/composables/useI18n'
import { useTheme } from '@/composables/useTheme'
import DefaultLayout from '@/components/DefaultLayout.vue'
import Toast from '@/components/Toast.vue'
import { Chart, registerables } from 'chart.js'

Chart.register(...registerables)

const store = useStatsStore()
const { t } = useI18n()
const { isDark } = useTheme()

// Chart 实例引用，方便销毁
const charts: Chart[] = []

function getGridColor() {
  return isDark.value ? '#3a3630' : '#f0ece5'
}
function getTickColor() {
  return isDark.value ? '#a09888' : '#8a8278'
}

function destroyCharts() {
  charts.forEach((c) => c.destroy())
  charts.length = 0
}

function scoreClass(s: number) {
  return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'
}

async function renderCharts() {
  destroyCharts()
  await nextTick()

  const d = store.detailed as any
  const en = store.enhanced as any

  // 状态分布饼图
  const c1 = document.getElementById('c1') as HTMLCanvasElement | null
  if (c1) {
    charts.push(
      new Chart(c1, {
        type: 'doughnut',
        data: {
          labels: ['追中', '已完成', '计划', '放弃'],
          datasets: [
            {
              data: [d.watching || 0, d.finished || 0, d.planning || 0, d.dropped || 0],
              backgroundColor: ['#7a9ec4', '#9a8ab8', '#d4a574', '#c47a8a'],
              borderWidth: 0,
            },
          ],
        },
        options: {
          responsive: true,
          cutout: '62%',
          plugins: {
            legend: {
              position: 'bottom',
              labels: { padding: 20, usePointStyle: true, pointStyleWidth: 8, font: { size: 12 } },
            },
          },
        },
      }),
    )
  }

  // 评分柱状图
  const c2 = document.getElementById('c2') as HTMLCanvasElement | null
  if (c2) {
    charts.push(
      new Chart(c2, {
        type: 'bar',
        data: {
          labels: ['高分 >=8', '中等 6~8', '低分 <6'],
          datasets: [
            {
              data: [d.highScore || 0, d.mediumScore || 0, d.lowScore || 0],
              backgroundColor: ['rgba(124,168,130,0.5)', 'rgba(212,165,116,0.5)', 'rgba(196,122,138,0.5)'],
              borderRadius: 8,
              borderSkipped: false,
            },
          ],
        },
        options: {
          responsive: true,
          plugins: { legend: { display: false } },
          scales: {
            y: { beginAtZero: true, ticks: { stepSize: 1 }, grid: { color: getGridColor() } },
            x: { grid: { display: false } },
          },
        },
      }),
    )
  }

  // 年度对比
  if (en.yearly?.length) {
    const cYoy = document.getElementById('c-yoy') as HTMLCanvasElement | null
    if (cYoy) {
      charts.push(
        new Chart(cYoy, {
          type: 'bar',
          data: {
            labels: en.yearly.map((y: any) => y.year + '年'),
            datasets: [
              { label: '数量', data: en.yearly.map((y: any) => y.count), backgroundColor: 'rgba(122,158,196,0.5)', borderRadius: 6, yAxisID: 'y' },
              { label: '均分', data: en.yearly.map((y: any) => y.avgScore || 0), type: 'line', borderColor: '#d4a574', backgroundColor: 'transparent', pointBackgroundColor: '#d4a574', tension: 0.3, yAxisID: 'y1' },
            ],
          },
          options: {
            responsive: true,
            plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, pointStyleWidth: 8, font: { size: 11 } } } },
            scales: {
              y: { beginAtZero: true, position: 'left', ticks: { stepSize: 1, color: getTickColor() }, grid: { color: getGridColor() } },
              y1: { beginAtZero: true, position: 'right', min: 0, max: 10, ticks: { color: getTickColor() }, grid: { display: false } },
              x: { ticks: { color: getTickColor() }, grid: { display: false } },
            },
          },
        }),
      )
    }
  }

  // 评分分布直方图
  if (en.scoreDistribution?.length) {
    const cHist = document.getElementById('c-hist') as HTMLCanvasElement | null
    if (cHist) {
      charts.push(
        new Chart(cHist, {
          type: 'bar',
          data: {
            labels: en.scoreDistribution.map((s: any) => s.bucket + '分'),
            datasets: [
              {
                label: '数量',
                data: en.scoreDistribution.map((s: any) => s.count),
                backgroundColor: en.scoreDistribution.map((s: any) => {
                  if (s.bucket >= 8) return 'rgba(124,168,130,0.6)'
                  if (s.bucket >= 6) return 'rgba(212,165,116,0.6)'
                  return 'rgba(196,122,138,0.6)'
                }),
                borderRadius: 6,
                borderSkipped: false,
              },
            ],
          },
          options: {
            responsive: true,
            plugins: { legend: { display: false } },
            scales: {
              y: { beginAtZero: true, ticks: { stepSize: 1, color: getTickColor() }, grid: { color: getGridColor() } },
              x: { ticks: { color: getTickColor() }, grid: { display: false } },
            },
          },
        }),
      )
    }
  }

  // 标签统计
  if (en.tags?.length) {
    const cTags = document.getElementById('c-tags') as HTMLCanvasElement | null
    if (cTags) {
      const tagColors = ['#7a9ec4', '#9a8ab8', '#d4a574', '#c47a8a', '#7ac4a4', '#c4b87a', '#8ac47a', '#c47a7a', '#7a7ac4', '#c4a47a', '#a47ac4', '#7ac4c4', '#c47aa4', '#7aa4c4', '#c4a4a4']
      charts.push(
        new Chart(cTags, {
          type: 'doughnut',
          data: {
            labels: en.tags.map((t: any) => t.tag),
            datasets: [{ data: en.tags.map((t: any) => t.count), backgroundColor: tagColors.slice(0, en.tags.length), borderWidth: 0 }],
          },
          options: {
            responsive: true,
            cutout: '50%',
            plugins: { legend: { position: 'right', labels: { usePointStyle: true, pointStyleWidth: 8, font: { size: 11 }, padding: 12 } } },
          },
        }),
      )
    }
  }
}

onMounted(async () => {
  await store.loadAllCharts()
  renderCharts()
})

// 主题变化时重新渲染图表
watch(isDark, () => renderCharts())

onUnmounted(() => destroyCharts())
</script>

<template>
  <DefaultLayout>
    <div class="charts-2">
      <div class="chart-card">
        <h4>{{ t('charts.statusDist') }}</h4>
        <canvas id="c1" />
      </div>
      <div class="chart-card">
        <h4>{{ t('charts.scoreDist') }}</h4>
        <canvas id="c2" />
      </div>
    </div>

    <div class="charts-2">
      <div class="chart-card">
        <h4>{{ t('charts.yearly') }}</h4>
        <canvas id="c-yoy" />
      </div>
      <div class="chart-card">
        <h4>评分分布</h4>
        <canvas id="c-hist" />
      </div>
    </div>

    <div class="charts-2">
      <div class="chart-card">
        <h4>{{ t('charts.tagStats') }}</h4>
        <canvas id="c-tags" />
      </div>
      <div class="chart-card">
        <h4>{{ t('charts.seasonSummary') }}</h4>
        <div class="season-grid">
          <div v-for="s in store.seasons" :key="s.season" class="season-card">
            <div class="sn">{{ s.season }}</div>
            <div class="sc">{{ s.count }}</div>
            <div class="sa">{{ s.avgScore != null ? 'avg ' + s.avgScore.toFixed(1) : '-' }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 热力图 -->
    <div class="chart-card" style="margin-bottom: 24px">
      <h4>观看热力图</h4>
      <div class="heatmap-container">
        <div
          v-for="(val, date) in store.heatmap"
          :key="date"
          class="heatmap-cell"
          :data-tip="date + ': ' + val + ' 集'"
          :style="{
            background:
              val === 0
                ? undefined
                : val <= 2
                  ? 'rgba(74,106,208,0.2)'
                  : val <= 4
                    ? 'rgba(74,106,208,0.4)'
                    : val <= 6
                      ? 'rgba(74,106,208,0.6)'
                      : 'rgba(74,106,208,0.85)',
          }"
        />
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="detail-row">
      <div class="d-card">
        <div class="d-label">{{ t('charts.epPerDay') }}</div>
        <div class="d-val">{{ (store.enhanced as any).episodesPerDay || '0' }}</div>
        <div class="d-sub">{{ t('charts.habit') }}</div>
      </div>
      <div class="d-card">
        <div class="d-label">{{ t('charts.epPerMonth') }}</div>
        <div class="d-val">{{ (store.enhanced as any).episodesPerMonth || '0' }}</div>
      </div>
    </div>

    <!-- 推荐 -->
    <div class="chart-card" style="margin-bottom: 24px">
      <h4>为你推荐</h4>
      <div v-if="store.recommendations.length === 0" style="text-align: center; padding: 20px; color: var(--text-faint); font-size: 0.85em">
        暂无推荐，多追几部番就有了
      </div>
      <div v-else class="browse-grid">
        <div v-for="item in store.recommendations" :key="item.id as string" class="browse-card">
          <img v-if="item.image" :src="(item.image as string).startsWith('//') ? 'https:' + item.image : (item.image as string)" class="browse-cover" @error="($event.target as HTMLImageElement).style.display='none'" />
          <div v-else class="browse-cover-empty">{{ ((item.nameCn || item.name || '') as string).charAt(0) }}</div>
          <div class="browse-info">
            <div class="browse-name">{{ item.nameCn || item.name }}</div>
            <div class="browse-meta">
              <span v-if="item.score">⭐ {{ item.score }}</span>
              <span>{{ item.date || '' }}</span>
            </div>
            <div style="font-size: 0.7em; color: var(--text-faint); margin-top: 4px">{{ item.reason }}</div>
          </div>
        </div>
      </div>
    </div>

    <Toast />
  </DefaultLayout>
</template>
