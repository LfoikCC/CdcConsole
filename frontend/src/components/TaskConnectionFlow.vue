<script setup>
import { computed } from 'vue'
import { ArrowRight } from 'lucide-vue-next'
import DatabaseBrand from './DatabaseBrand.vue'

const props = defineProps({ task: { type: Object, required: true }, sources: { type: Array, default: () => [] }, targets: { type: Array, default: () => [] } })
function connectionLabel(connection, id, type, role) {
  return connection?.name || `${role} #${id}`
}
const sourceLabel = computed(() => {
  const source = props.sources.find(item => String(item.id) === String(props.task.sourceId))
  return connectionLabel(source, props.task.sourceId, source?.dbType || props.task.sourceType, '源连接')
})
const subscriptions = computed(() => (props.task.targetSubscriptions || []).filter(row=>row.status!=='CANCELLED'))
const sourceType=computed(()=>props.sources.find(item=>String(item.id)===String(props.task.sourceId))?.dbType||props.task.sourceType)
const destinations = computed(() => {
  const rows = props.task.targetSubscriptions?.length ? subscriptions.value : [{ targetId: props.task.targetId }]
  return rows.map((row, index) => {
    const target = props.targets.find(item => String(item.id) === String(row.targetId))
    const type=target?.targetType || (String(row.targetId) === String(props.task.targetId) ? props.task.targetType : null)
    return { key: row.id ?? index, type, label: connectionLabel(target, row.targetId, type, '目标连接'), name: row.name }
  })
})
</script>

<template>
  <div class="flow task-connection-flow" aria-label="源到目标连接">
    <span class="flow-node src"><DatabaseBrand :type="sourceType" :label="sourceLabel"/></span>
    <ArrowRight class="flow-arrow" :size="14" aria-hidden="true"/>
    <span v-for="target in destinations" :key="target.key" class="flow-node sink" :title="target.name"><DatabaseBrand :type="target.type" :label="target.label"/><span v-if="target.name" class="flow-subscription"> / {{ target.name }}</span></span>
    <span class="dim">{{ task.tables?.length || 0 }} 张源表 · {{ subscriptions.length }} 个有效目标订阅</span>
  </div>
</template>

<style scoped>
.task-connection-flow { display:flex; margin-bottom:16px; min-width:0; }
.flow-node { max-width:100%; overflow-wrap:anywhere; white-space:normal; }
.flow-arrow { flex-shrink:0; }
.flow-subscription { color:var(--muted-foreground); }
</style>
