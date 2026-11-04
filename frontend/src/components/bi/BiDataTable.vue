<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ data: { type: Object, default: null } })

// 虚拟滚动：只渲染视口里的几十行，两万行也不会把 DOM 撑爆
const ROW_H = 30
const BUFFER = 8
const VIRTUAL_THRESHOLD = 60

const columns = computed(() => props.data?.columns || [])
const rows = computed(() => props.data?.rows || [])

const wrap = ref(null)
const scrollTop = ref(0)
const viewHeight = ref(0)
const rowHeight = ref(ROW_H)
let observer = null

const virtual = computed(() => rows.value.length > VIRTUAL_THRESHOLD)
const start = computed(() => (virtual.value ? Math.max(0, Math.floor(scrollTop.value / rowHeight.value) - BUFFER) : 0))
const end = computed(() => {
  if (!virtual.value) return rows.value.length
  const visible = Math.ceil(viewHeight.value / rowHeight.value) + BUFFER * 2
  return Math.min(rows.value.length, start.value + visible)
})
const visibleRows = computed(() => rows.value.slice(start.value, end.value))
const topPad = computed(() => (virtual.value ? start.value * rowHeight.value : 0))
const bottomPad = computed(() => (virtual.value ? Math.max(0, (rows.value.length - end.value) * rowHeight.value) : 0))

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
  if (!wrap.value) return
  observer = new ResizeObserver(entries => {
    viewHeight.value = entries[0]?.contentRect?.height || 0
  })
  observer.observe(wrap.value)
  viewHeight.value = wrap.value.clientHeight || 0
  nextTick(measure)
})

onBeforeUnmount(() => observer?.disconnect())

watch(rows, () => {
  scrollTop.value = wrap.value?.scrollTop || 0
  nextTick(measure)
})

function cell(value) {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
</script>

<template>
  <div ref="wrap" class="bi-table-wrap" @scroll.passive="onScroll">
    <table class="bi-table">
      <thead>
        <tr><th v-for="column in columns" :key="column">{{ column }}</th></tr>
      </thead>
      <tbody>
        <tr v-if="topPad" class="bi-row-pad" aria-hidden="true">
          <td :colspan="columns.length" :style="{ height: `${topPad}px` }"/>
        </tr>
        <tr v-for="(row, index) in visibleRows" :key="start + index" class="bi-row">
          <td v-for="(value, columnIndex) in row" :key="columnIndex" class="mono">{{ cell(value) }}</td>
        </tr>
        <tr v-if="bottomPad" class="bi-row-pad" aria-hidden="true">
          <td :colspan="columns.length" :style="{ height: `${bottomPad}px` }"/>
        </tr>
      </tbody>
    </table>
  </div>
</template>
