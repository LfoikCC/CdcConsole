<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { formatMetricValue, hasMetricFormat } from './biCards'

const props = defineProps({
  data: { type: Object, default: null },
  /** 卡片配置：分页、每页行数、行序号都从这里读 */
  options: { type: Object, default: () => ({}) }
})

// 分批渲染：一次只挂 100 行，滑到下一批再换窗口。
// 行高固定 30px，所以上下用占位行撑出真实滚动高度，滚动条不会跳。
const ROW_H = 30
const CHUNK = 100
const VIRTUAL_THRESHOLD = 60

const columns = computed(() => props.data?.columns || [])
const rows = computed(() => props.data?.rows || [])
const paged = computed(() => props.options?.pagination === true)
const pageSize = computed(() => (Number(props.options?.pageSize) > 0 ? Number(props.options.pageSize) : 50))
const SIZE_OPTIONS = [20, 50, 100, 200]
/** 分页条上的「每页几条」：本地改立即生效，同时抛事件给编辑器落库 */
const size = ref(pageSize.value)
const jump = ref('')
const emit = defineEmits(['page-size'])
const showIndex = computed(() => props.options?.showRowIndex === true)
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(rows.value.length / size.value)))
/** 分页时只把当前页交给渲染层，其余照旧走分批虚拟滚动。 */
const source = computed(() => (paged.value
  ? rows.value.slice((page.value - 1) * size.value, page.value * size.value)
  : rows.value))

const wrap = ref(null)
const scrollTop = ref(0)
const rowHeight = ref(ROW_H)

const virtual = computed(() => !paged.value && source.value.length > VIRTUAL_THRESHOLD)
// 按 100 行对齐：滚动过程中窗口不会一行一行抖，跟着批次走
const start = computed(() => (virtual.value
  ? Math.floor(scrollTop.value / rowHeight.value / CHUNK) * CHUNK
  : 0))
const end = computed(() => (virtual.value
  ? Math.min(source.value.length, start.value + CHUNK)
  : source.value.length))
const visibleRows = computed(() => source.value.slice(start.value, end.value))
const topPad = computed(() => (virtual.value ? start.value * rowHeight.value : 0))
const bottomPad = computed(() => (virtual.value ? Math.max(0, (source.value.length - end.value) * rowHeight.value) : 0))
/** 行序号接的不是 DOM 下标：翻页和虚拟滚动都要接着上一批数下去。 */
const rowOffset = computed(() => (paged.value ? (page.value - 1) * size.value : start.value))

/** 页码条：首尾常驻，中间只留当前页左右各一页，其余用省略号，宽度稳定不跳。 */
const pageItems = computed(() => {
  const total = pageCount.value
  const current = page.value
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)
  const items = [1]
  const from = Math.max(2, current - 1)
  const to = Math.min(total - 1, current + 1)
  if (from > 2) items.push('...')
  for (let i = from; i <= to; i += 1) items.push(i)
  if (to < total - 1) items.push('...')
  items.push(total)
  return items
})

function changeSize(value) {
  const next = Number(value)
  if (!Number.isFinite(next) || next <= 0) return
  size.value = next
  page.value = 1
  emit('page-size', next)
}

function submitJump() {
  const target = Number(jump.value)
  jump.value = ''
  if (!Number.isFinite(target)) return
  goPage(Math.floor(target))
}

function goPage(next) {
  page.value = Math.max(1, Math.min(next, pageCount.value))
  scrollTop.value = 0
  if (wrap.value) wrap.value.scrollTop = 0
}

/**
 * 数值列右对齐。
 *
 * <p>金额、计数、ID 混在一张表里，全左对齐时位数对不齐，扫一眼根本看不出量级。
 * 抽样前 50 行判断：80% 以上能当数字读的列就右对齐；空值不算样本。
 */
const numeric = computed(() => {
  const result = new Set()
  const sample = rows.value.slice(0, 50)
  if (!sample.length) return result
  columns.value.forEach((_, index) => {
    let hits = 0
    let seen = 0
    for (const row of sample) {
      const value = row[index]
      if (value === null || value === undefined || value === '') continue
      seen += 1
      if (typeof value === 'number' || /^-?\d+(\.\d+)?$/.test(String(value).trim())) hits += 1
    }
    if (seen && hits / seen >= 0.8) result.add(index)
  })
  return result
})

function onScroll(event) {
  scrollTop.value = event.target.scrollTop
}

/** 行高按真实渲染量一次，避免 padding / 边框让偏移越滚越歪。 */
function measure() {
  const row = wrap.value?.querySelector('.bi-table tbody tr.bi-row')
  const height = row?.getBoundingClientRect().height
  if (height && Math.abs(height - rowHeight.value) > 0.5) rowHeight.value = height
}

onMounted(() => {
  // 窗口按 100 行固定，不再随视口高度变化，量一次行高就够了
  nextTick(measure)
})

watch(rows, () => {
  // 换了一份数据就回到第一页，否则停在第 5 页看到的是新结果的中段
  page.value = 1
  scrollTop.value = wrap.value?.scrollTop || 0
  nextTick(measure)
})

watch(pageSize, value => { size.value = value; page.value = 1 })
watch(pageCount, count => { if (page.value > count) page.value = count })

/** 设过数据格式的数值列：列下标 → 字段配置。维度在前、数值在后，和后端 SELECT 顺序一致。 */
const metricFormats = computed(() => {
  const offset = (props.options?.dimensions || []).length
  const map = new Map()
  ;(props.options?.metrics || []).forEach((metric, index) => {
    if (hasMetricFormat(metric)) map.set(offset + index, metric)
  })
  return map
})

function cell(value) {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}

/** 单元格展示：数值列设了格式就按格式渲染，否则维持原样。 */
function display(value, columnIndex) {
  const metric = metricFormats.value.get(columnIndex)
  return metric ? formatMetricValue(value, metric) : cell(value)
}
</script>

<template>
  <div class="bi-table-box">
    <div ref="wrap" class="bi-table-wrap" @scroll.passive="onScroll">
      <table class="bi-table">
        <thead>
          <tr>
            <th v-if="showIndex" class="bi-th-index">#</th>
            <th v-for="(column, index) in columns" :key="column"
                :class="{ 'is-num': numeric.has(index) }">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="topPad" class="bi-row-pad" aria-hidden="true">
            <td :colspan="columns.length + (showIndex ? 1 : 0)" :style="{ height: `${topPad}px` }"/>
          </tr>
          <tr v-for="(row, index) in visibleRows" :key="start + index" class="bi-row"
              :class="{ 'is-alt': (start + index) % 2 === 1 }">
            <td v-if="showIndex" class="bi-td-index">{{ rowOffset + index + 1 }}</td>
            <td v-for="(value, columnIndex) in row" :key="columnIndex"
                :class="{ 'mono': !numeric.has(columnIndex), 'is-num': numeric.has(columnIndex), 'is-blank': cell(value) === '—' }">
              {{ display(value, columnIndex) }}
            </td>
          </tr>
          <tr v-if="bottomPad" class="bi-row-pad" aria-hidden="true">
            <td :colspan="columns.length + (showIndex ? 1 : 0)" :style="{ height: `${bottomPad}px` }"/>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="paged" class="bi-pager">
      <span class="bi-pager-total">共 {{ rows.length }} 条</span>

      <button type="button" class="bi-pager-nav" :disabled="page <= 1" aria-label="上一页"
              @click="goPage(page - 1)">
        <ChevronLeft :size="12"/>
      </button>
      <template v-for="(item, index) in pageItems" :key="index">
        <span v-if="item === '...'" class="bi-pager-gap">…</span>
        <button v-else type="button" class="bi-pager-num" :class="{ 'is-active': item === page }"
                :aria-current="item === page ? 'page' : undefined" @click="goPage(item)">{{ item }}</button>
      </template>
      <button type="button" class="bi-pager-nav" :disabled="page >= pageCount" aria-label="下一页"
              @click="goPage(page + 1)">
        <ChevronRight :size="12"/>
      </button>

      <!-- 用自绘下拉而不是原生 select：原生弹层在部分环境下会飘到页面左上角，
           而且样式跟站内其它下拉不一致 -->
      <cdc-select class="bi-pager-size" :model-value="size" aria-label="每页行数"
                  @update:model-value="changeSize">
        <cdc-option v-for="item in SIZE_OPTIONS" :key="item" :value="item" :label="`${item} 条/页`"/>
      </cdc-select>

      <label class="bi-pager-jump">
        跳至
        <input v-model="jump" class="bi-pager-jump-input" inputmode="numeric" aria-label="跳转到第几页"
               @keyup.enter="submitJump" @blur="submitJump"/>
        页
      </label>
    </div>
  </div>
</template>
