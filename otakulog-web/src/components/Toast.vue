<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts } = useToast()
</script>

<template>
  <div class="tw">
    <div
      v-for="t in toasts"
      :key="t.id"
      class="toast"
      :class="t.type"
    >
      {{ t.message }}
    </div>
  </div>
</template>

<style scoped>
.tw {
  position: fixed;
  top: 24px;
  right: 24px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
}
.toast {
  padding: 14px 22px;
  border-radius: 12px;
  color: white;
  font-weight: 500;
  font-size: 0.85em;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.1);
  animation: tIn 0.35s cubic-bezier(0.16, 1, 0.3, 1), tOut 0.3s ease 2.7s forwards;
  pointer-events: auto;
}
.toast.success {
  background: #5a9a6a;
}
.toast.error {
  background: #c46a6a;
}
.toast.info {
  background: #6a8ab0;
}
@keyframes tIn {
  from {
    opacity: 0;
    transform: translateX(40px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateX(0) scale(1);
  }
}
@keyframes tOut {
  from {
    opacity: 1;
    transform: translateX(0);
  }
  to {
    opacity: 0;
    transform: translateX(40px);
  }
}
</style>
