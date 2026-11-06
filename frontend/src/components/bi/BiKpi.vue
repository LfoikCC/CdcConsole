<script setup>
import { computed } from 'vue'
import { formatNumber, kpiValue } from './biChartOption'
import { formatMetricValue, hasMetricFormat } from './biCards'

const props = defineProps({ card: { type: Object, required: true }, data: { type: Object, default: null } })

const value = computed(() => kpiValue(props.data))
const metric = computed(() => props.card?.config?.metrics?.[0] || null)
// 设过数据格式就按格式走，否则保持原来的千分位显示
const text = computed(() => (hasMetricFormat(metric.value)
  ? formatMetricValue(value.value, metric.value)
  : formatNumber(value.value)))
const label = computed(() => {
  const alias = props.card?.config?.metrics?.[0]?.alias
  if (alias) return alias
  const columns = props.data?.columns || []
  return columns[0] || '指标'
})
</script>

<template>
  <div class="bi-kpi">
    <div class="bi-kpi-value mono">{{ text }}</div>
    <div class="bi-kpi-label">{{ label }}</div>
  </div>
</template>
