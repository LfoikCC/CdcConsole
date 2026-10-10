<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { CdcMessage } from '../components/ui/feedback'
import TaskWizard from '../components/TaskWizard.vue'
import { api } from '../api'

const route = useRoute(), router = useRouter(), open = ref(true), task = ref(null), loading = ref(Boolean(route.params.id))
const wizardRef=ref(null)
onBeforeRouteLeave(()=>wizardRef.value?.confirmLeave?.()??true)
onMounted(async () => {
  if (!route.params.id) return
  try { task.value = await api(`/tasks/${route.params.id}`) } catch (error) { CdcMessage.error(error.message); router.replace('/tasks') } finally { loading.value = false }
})
function close() { open.value = false; router.push(route.params.id ? `/tasks/${route.params.id}` : '/tasks') }
function saved() { close() }
</script>

<template>
  <div class="wizard-route"><div v-if="loading" class="loading-line">正在加载任务配置...</div><TaskWizard v-else ref="wizardRef" v-model="open" :task="task" @update:model-value="value=>!value&&close()" @saved="saved"/></div>
</template>

