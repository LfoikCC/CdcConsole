<script setup>
import { computed } from 'vue'
import { formatNumber, kpiValue } from './biChartOption'

const props = defineProps({ card: { type: Object, required: true }, data: { type: Object, default: null } })

const value = computed(() => kpiValue(props.data))
const label = computed(() => {
  const alias = props.card?.config?.metrics?.[0]?.alias
  if (alias) return alias
  const columns = props.data?.columns || []
  return columns[0] || '指标'
})
</script>

<template>
  <div class="bi-kpi">
    <div class="bi-kpi-value mono">{{ formatNumber(value) }}</div>
    <div class="bi-kpi-label">{{ label }}</div>
  </div>
</template>
