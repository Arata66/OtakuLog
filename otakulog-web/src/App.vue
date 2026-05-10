<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useOfflineSync } from '@/composables/useOfflineSync'
import { useNetwork } from '@/utils/network'
import { isOfflineEnabled } from '@/utils/offlineApi'

const { pendingCount } = useOfflineSync()
const { isOnline } = useNetwork()
</script>

<template>
  <RouterView />
  <!-- 离线状态指示器 -->
  <div v-if="isOfflineEnabled() && !isOnline" class="offline-banner">
    离线模式
    <span v-if="pendingCount > 0"> · {{ pendingCount }} 个操作待同步</span>
  </div>
</template>

<style>
.offline-banner {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: var(--amber);
  color: #000;
  text-align: center;
  padding: 6px 16px;
  font-size: 0.82em;
  font-weight: 500;
  z-index: 9999;
}
</style>
