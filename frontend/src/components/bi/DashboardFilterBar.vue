<script setup>
/**
 * 看板筛选栏：顶部一条，放筛选器控件，可收起。
 *
 * <p>每个筛选器都渲染成一个真实的控件（下拉、输入框、日期区间），
 * 而不是一行文字摘要——看板使用者要能直接改条件看数。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { CalendarDays, ChevronDown, Filter, Pencil, Plus, X } from 'lucide-vue-next'
import FilterValues from './FilterValues.vue'
import { QUICK_RANGES, filterName, filterSummary, quickRange } from './biFilters'

const props = defineProps({
  filters: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  /** 查看页：控件照样能改值，但不能新增、删除、编辑定义。 */
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['update:filters', 'add', 'edit'])

const collapsed = ref(false)

const datasetName = id => props.datasets.find(item => Number(item.id) === Number(id))?.name || ''

/** 改一个筛选器：整组回传新数组，父级好统一保存。 */
function patch(filter, changes) {
  emit('update:filters', props.filters.map(item =>
    item.id === filter.id ? { ...item, ...changes } : item))
}

function remove(filter) {
  emit('update:filters', props.filters.filter(item => item.id !== filter.id))
}

function applyQuick(filter, value) {
  const [from, to] = quickRange(value)
  patch(filter, { quick: value, from, to })
}

const activeCount = computed(() => props.filters.length)

// ---------------------------------------------------------------- 日期区间弹层
/**
 * 日期区间在栏上只占一个按钮。
 *
 * <p>原来把「快捷下拉 + 两个日期框」平铺在栏里，快捷文案被压成「最近 7.」，
 * 而且日期块比别的筛选器宽一大截。改成按钮 + 弹层后宽度和其他筛选器一致。
 */
const dateOpen = ref('')
const datePanel = ref(null)
/** 弹层正在编辑的那个筛选器。 */
const activeFilter = computed(() => props.filters.find(item => item.id === dateOpen.value) || null)

const dateText = value => {
  if (!value) return ''
  const [year, month, day] = String(value).split('-')
  return year && month && day ? `${year}/${month}/${day}` : value
}

function dateLabel(filter) {
  if (!filter.from && !filter.to) return '全部时间'
  return `${dateText(filter.from) || '不限'} ~ ${dateText(filter.to) || '不限'}`
}

/**
 * 打开日期弹层：位置直接写元素样式。
 *
 * <p>不用 `:style` 绑定——弹层在 Teleport 里、又是 v-if 新建的节点，
 * 绑定的对象在这条链路上没落到 DOM 上，弹层会按文档流排在页面最底部。
 */
function toggleDate(filter, event) {
  if (dateOpen.value === filter.id) {
    dateOpen.value = ''
    return
  }
  const rect = event.currentTarget.getBoundingClientRect()
  dateOpen.value = filter.id
  nextTick(() => {
    const panel = datePanel.value
    if (!panel) return
    const edge = 8
    const width = panel.offsetWidth || 272
    const height = panel.offsetHeight || 180
    const left = Math.max(edge, Math.min(rect.left, window.innerWidth - width - edge))
    const below = rect.bottom + 4
    const top = below + height <= window.innerHeight - edge
      ? below
      : Math.max(edge, rect.top - height - 4)
    panel.style.left = `${Math.round(left)}px`
    panel.style.top = `${Math.round(top)}px`
  })
}

function closeDate(event) {
  if (!dateOpen.value) return
  if (event?.target?.closest?.('.bi-date-panel, .bi-filter-date')) return
  dateOpen.value = ''
}

function onKeydown(event) {
  if (event.key === 'Escape') dateOpen.value = ''
}

onMounted(() => {
  document.addEventListener('pointerdown', closeDate)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeDate)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="bi-filter-bar" :class="{ 'is-collapsed': collapsed, 'is-readonly': readonly }">
    <!-- 只留图标 + 数量 + 箭头：这三个字占的 40px 够多放半个筛选器了 -->
    <button type="button" class="bi-filter-toggle" @click="collapsed = !collapsed"
            :aria-expanded="!collapsed" aria-label="筛选器" title="筛选器">
      <Filter :size="13"/>
      <span v-if="activeCount" class="bi-filter-count">{{ activeCount }}</span>
      <ChevronDown :size="12" :class="['bi-filter-caret', !collapsed && 'is-open']"/>
    </button>

    <template v-if="!collapsed">
      <div v-for="filter in filters" :key="filter.id" class="bi-filter-item">
        <button v-if="!readonly" type="button" class="bi-filter-name"
                :title="`${datasetName(filter.datasetId)} · ${filterSummary(filter)}`"
                @click="emit('edit', filter)">
          {{ filterName(filter) }}
        </button>
        <span v-else class="bi-filter-name is-static"
              :title="`${datasetName(filter.datasetId)} · ${filterSummary(filter)}`">
          {{ filterName(filter) }}
        </span>

        <!-- 选择：多选下拉 -->
        <!-- 「排除」收进下拉面板，栏上只留下拉本身，宽度全给控件 -->
        <div v-if="filter.type === 'select'" class="bi-filter-control">
          <FilterValues :dataset-id="filter.datasetId" :column="filter.column"
                        :multiple="filter.multiple" :model-value="filter.values || []"
                        :exclude="!!filter.exclude"
                        @update:model-value="value => patch(filter, { values: value })"
                        @update:exclude="value => patch(filter, { exclude: value })"/>
        </div>

        <!-- 条件：关键词输入 -->
        <div v-else-if="filter.type === 'condition'" class="bi-filter-control">
          <input class="input-control" :value="filter.keyword" placeholder="输入关键词"
                 :aria-label="filterName(filter)" @input="event => patch(filter, { keyword: event.target.value })">
        </div>

        <!-- 范围：起止数值 -->
        <div v-else-if="filter.type === 'range'" class="bi-filter-control">
          <template v-if="filter.valueOp === 'BETWEEN'">
            <input class="input-control" type="number" :value="filter.from" placeholder="最小"
                   :aria-label="`${filterName(filter)} 最小`"
                   @input="event => patch(filter, { from: event.target.value })">
            <span class="bi-filter-tilde">~</span>
            <input class="input-control" type="number" :value="filter.to" placeholder="最大"
                   :aria-label="`${filterName(filter)} 最大`"
                   @input="event => patch(filter, { to: event.target.value })">
          </template>
          <input v-else-if="filter.valueOp !== 'IS NULL' && filter.valueOp !== 'IS NOT NULL'"
                 class="input-control" type="number" :value="filter.from" placeholder="输入数值"
                 :aria-label="filterName(filter)"
                 @input="event => patch(filter, { from: event.target.value })">
          <span v-else class="bi-filter-static">{{ filterSummary(filter) }}</span>
        </div>

        <!-- 日期：栏上只放一个按钮，点开选快捷或自定义 -->
        <div v-else-if="filter.type === 'date'" class="bi-filter-control">
          <button type="button" class="bi-filter-date" :class="{ 'is-open': dateOpen === filter.id }"
                  :aria-label="`${filterName(filter)} ${dateLabel(filter)}`"
                  :aria-expanded="dateOpen === filter.id"
                  @click="event => toggleDate(filter, event)">
            <CalendarDays :size="12"/>
            <span>{{ dateLabel(filter) }}</span>
          </button>
        </div>

        <!-- 组合条件：摘要 + 进编辑 -->
        <div v-else class="bi-filter-control">
          <span class="bi-filter-static">{{ filterSummary(filter) }}</span>
        </div>

        <template v-if="!readonly">
          <button type="button" class="bi-filter-x" :aria-label="`编辑 ${filterName(filter)}`"
                  @click="emit('edit', filter)">
            <Pencil :size="11"/>
          </button>
          <button type="button" class="bi-filter-x" :aria-label="`移除 ${filterName(filter)}`" @click="remove(filter)">
            <X :size="11"/>
          </button>
        </template>
      </div>

      <template v-if="!readonly">
        <span v-if="!filters.length" class="bi-filter-empty">还没有筛选器；添加后同数据集的卡片会一起生效</span>
        <button type="button" class="btn btn-ghost btn-sm bi-filter-add" @click="emit('add')">
          <Plus :size="12"/>添加筛选
        </button>
      </template>
    </template>

    <Teleport to="body">
      <div v-if="dateOpen" ref="datePanel" class="bi-date-panel">
        <div class="bi-date-quick">
          <button v-for="item in QUICK_RANGES" :key="item.value" type="button"
                  class="bi-quick-chip" :class="{ active: activeFilter?.quick === item.value }"
                  @click="activeFilter && applyQuick(activeFilter, item.value)">{{ item.label }}</button>
        </div>
        <div class="bi-date-range">
          <input class="input-control" type="date" :value="activeFilter?.from" aria-label="开始日期"
                 @input="event => activeFilter && patch(activeFilter, { from: event.target.value, quick: '' })">
          <span class="bi-filter-tilde">~</span>
          <input class="input-control" type="date" :value="activeFilter?.to" aria-label="结束日期"
                 @input="event => activeFilter && patch(activeFilter, { to: event.target.value, quick: '' })">
        </div>
        <div class="bi-date-actions">
          <button type="button" class="btn btn-ghost btn-sm"
                  @click="activeFilter && patch(activeFilter, { from: '', to: '', quick: '' })">清空</button>
          <span style="flex:1"/>
          <button type="button" class="btn btn-primary btn-sm" @click="dateOpen = ''">完成</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>
