<script setup>
import { computed } from 'vue'
import { AlertCircle } from 'lucide-vue-next'
import { filterLabel, hasActiveFilter } from './biCards'
import BiChart from './BiChart.vue'
import BiDataTable from './BiDataTable.vue'
import BiKpi from './BiKpi.vue'
import BiTextCard from './BiTextCard.vue'

/**
 * 卡片正文：按类型分派到对应渲染器，顺带处理「没数据 / 加载中 / 取数失败」三种状态。
 * 画布上的卡片和卡片编辑器里的预览都用它，保证两处观感一致。
 */
const props = defineProps({
  card: { type: Object, required: true },
  data: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

const chartTypes = ['bar', 'line', 'pie']
const isChart = computed(() => chartTypes.includes(props.card.type))
const failed = computed(() => props.data && props.data.ok === false)
const hasData = computed(() => (props.data?.rows?.length || 0) > 0)
const empty = computed(() => !hasData.value && !props.loading && !failed.value && props.card.type !== 'text')
// 空图有两种原因：还没配字段 / 被筛选条件筛空了。
// 后者把生效的条件列出来，否则用户只看到一张白卡，根本不知道是哪条条件把数据筛没了。
const activeFilters = computed(() => (props.card?.config?.filters || []).filter(filter => filterLabel(filter)))
const emptyText = computed(() => {
  if (!hasActiveFilter(props.card)) return '暂无数据'
  const labels = activeFilters.value.slice(0, 2).map(filterLabel)
  return `筛选后没有数据（${labels.join('，')}）`
})
</script>

<template>
  <div class="bi-card-view" :class="{ 'is-empty': empty && card.type !== 'table' }">
    <div v-if="failed" class="bi-card-error">
      <AlertCircle :size="16"/>
      <span>{{ data.error || '取数失败' }}</span>
    </div>
    <div v-else-if="loading" class="bi-card-loading">加载中…</div>
    <BiTextCard v-else-if="card.type === 'text'" :card="card"/>
    <BiDataTable v-else-if="card.type === 'table'" :data="data"/>
    <BiKpi v-else-if="card.type === 'kpi'" :card="card" :data="data"/>
    <BiChart v-else-if="isChart" :card="card" :data="data"/>
    <div v-if="empty" class="bi-card-empty">{{ emptyText }}</div>
    <!-- 取数被上限截断时要说出来，否则用户会以为这就是全部数据 -->
    <div v-if="data?.truncated" class="bi-card-truncated">
      已到上限，仅显示前 {{ data.rows?.length || 0 }} 行
    </div>
  </div>
</template>
