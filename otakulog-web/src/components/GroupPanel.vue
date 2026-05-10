<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import * as groupApi from '@/api/group'
import type { AnimeGroupDTO } from '@/api/group'

const { t } = useI18n()
const toast = useToast()

const groups = ref<AnimeGroupDTO[]>([])
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const newColor = ref('#4a6ad0')

async function loadGroups() {
  try {
    groups.value = await groupApi.getAllGroups()
  } catch (_) {}
}

async function onCreate() {
  if (!newName.value.trim()) return
  try {
    await groupApi.createGroup(newName.value.trim(), newDesc.value, newColor.value)
    toast.success('分组已创建')
    newName.value = ''
    newDesc.value = ''
    showCreate.value = false
    await loadGroups()
  } catch (e: any) {
    toast.error(e.message || '创建失败')
  }
}

async function onDelete(id: number) {
  if (!confirm('确定删除此分组？')) return
  try {
    await groupApi.deleteGroup(id)
    toast.success('分组已删除')
    await loadGroups()
  } catch (e: any) {
    toast.error(e.message || '删除失败')
  }
}

onMounted(loadGroups)
</script>

<template>
  <div class="group-panel">
    <div class="group-header">
      <h3>分组管理</h3>
      <button class="btn-sm" @click="showCreate = !showCreate">{{ showCreate ? '取消' : '+ 新建分组' }}</button>
    </div>

    <div v-if="showCreate" class="group-create">
      <input v-model="newName" type="text" placeholder="分组名称" />
      <input v-model="newDesc" type="text" placeholder="描述（可选）" />
      <input v-model="newColor" type="color" style="width: 40px; height: 36px; border: none; cursor: pointer" />
      <button class="btn-add" @click="onCreate">创建</button>
    </div>

    <div v-if="groups.length === 0" style="text-align: center; padding: 20px; color: var(--text-faint); font-size: 0.85em">
      暂无分组
    </div>

    <div v-else class="group-list">
      <div v-for="g in groups" :key="g.id" class="group-item">
        <span class="group-dot" :style="{ background: g.color }" />
        <span class="group-name">{{ g.name }}</span>
        <span class="group-count">{{ g.animeCount }} 部</span>
        <button class="a-btn del" style="font-size: 0.72em; padding: 4px 10px" @click="onDelete(g.id)">删除</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.group-panel {
  background: var(--card);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: var(--shadow);
  margin-bottom: 24px;
}
.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.group-header h3 {
  font-family: var(--serif);
  font-size: 1em;
  font-weight: 400;
  color: var(--text);
}
.group-create {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}
.group-create input[type='text'] {
  background: var(--bg);
  border: 1.5px solid var(--border);
  color: var(--text);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 0.85em;
  font-family: inherit;
  flex: 1;
}
.group-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg);
  border-radius: 8px;
  border: 1px solid var(--border-light);
}
.group-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
.group-name {
  font-weight: 500;
  font-size: 0.88em;
  flex: 1;
}
.group-count {
  font-size: 0.78em;
  color: var(--text-dim);
}
</style>
