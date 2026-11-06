<script setup>
import { computed } from 'vue'
import { AlertCircle } from 'lucide-vue-next'
import { CHART_TYPES, cardMeta, cardRequirement, filterLabel, hasActiveFilter } from './biCards'
import BiChart from './BiChart.vue'
import BiDataTable from './BiDataTable.vue'
import BiKpi from './BiKpi.vue'
import BiTextCard from './BiTextCard.vue'

/**
 * 卡片正文：按类型分派到对应渲染器，顺带处理「字段没配齐 / 没数据 / 加载中 / 取数失败」四种状态。
 * 画布上的卡片和卡片编辑器里的预览都用它，保证两处观感一致。
 */
const props = defineProps({
  card: { type: Object, required: true },
  data: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

// 分页条上改「每页条数」时把值往上抛，编辑器收到后写回卡片配置
const emit = defineEmits(['page-size'])

const isChart = computed(() => CHART_TYPES.includes(props.card.type))
// 字段没配齐时不取数、不画图，只在卡片中间说清楚缺什么
const requirement = computed(() => cardRequirement(props.card))
const meta = computed(() => cardMeta(props.card.type))
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
  <div class="bi-card-view"
       :class="{ 'is-empty': requirement.complete && empty && card.type !== 'table', 'is-hint': !requirement.complete }">
    <!-- 字段没配齐：不渲染任何图形，居中提示还差什么 -->
    <div v-if="!requirement.complete" class="bi-card-hint">
      <component :is="meta.icon" :size="30" class="bi-card-hint-icon"/>
      <p>{{ requirement.text }}</p>
    </div>
    <template v-else>
      <div v-if="failed" class="bi-card-error">
        <AlertCircle :size="16"/>
        <span>{{ data.error || '取数失败' }}</span>
      </div>
      <!-- 只有「第一次取数」才占位；刷新时保留旧图，避免整块闪一下再重画 -->
      <div v-if="loading && !hasData" class="bi-card-loading">加载中…</div>
      <BiTextCard v-else-if="card.type === 'text'" :card="card"/>
      <BiDataTable v-else-if="card.type === 'table'" :data="data" :options="card.config || {}"
                   @page-size="emit('page-size', $event)"/>
      <BiKpi v-else-if="card.type === 'kpi'" :card="card" :data="data"/>
      <!-- 不按 type 换 key：图表实例复用，换图型时在同一块 canvas 上重建，不会闪白 -->
      <BiChart v-else-if="isChart" :card="card" :data="data"/>
      <div v-if="empty" class="bi-card-empty">{{ emptyText }}</div>
      <!-- 取数被上限截断时要说出来，否则用户会以为这就是全部数据 -->
      <div v-if="data?.truncated" class="bi-card-truncated">
        已到上限，仅显示前 {{ data.rows?.length || 0 }} 行
      </div>
    </template>
  </div>
</template>
