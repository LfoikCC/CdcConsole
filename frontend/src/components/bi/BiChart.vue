<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createChart } from '../../chartStyle'
import { chartOptionFor } from './biChartOption'

const props = defineProps({ card: { type: Object, required: true }, data: { type: Object, default: null } })

const el = ref(null)
let chart = null
/** 建实例时用的那个 DOM，容器被换掉才需要重建。 */
let chartEl = null
/** 上一次画的是哪种图：换图型时整张重建，但仍然复用同一个 canvas。 */
let lastType = null
let frame = 0

/** 数据点太多就别做入场动画了：几千根柱子逐帧长大比直接出现更卡。 */
const HEAVY_ROWS = 800

const raf = typeof window !== 'undefined' && window.requestAnimationFrame
  ? window.requestAnimationFrame.bind(window)
  : callback => setTimeout(callback, 16)
const caf = typeof window !== 'undefined' && window.cancelAnimationFrame
  ? window.cancelAnimationFrame.bind(window)
  : clearTimeout

/**
 * 每次重画都重新算 option：chartStyle() 读的是 CSS 变量，缓存住切主题就不生效了。
 * 没有数据时直接销毁图表实例——留一个空盒子比什么都不显示更难看。
 */
function draw() {
  frame = 0
  const option = chartOptionFor(props.card, props.data)
  if (!el.value || !option) {
    destroy()
    return
  }
  if (chart && chartEl !== el.value) destroy()
  if (!chart) {
    // 常规数据量走 SVG（文字锐利）；几千行时 SVG 节点太多会拖慢，
    // 这一档才切 Canvas，并且只在初始化时决定一次，避免中途重建闪一下
    const rows = props.data?.rows?.length || 0
    chart = createChart(el.value, rows > HEAVY_ROWS ? 'canvas' : 'svg')
    chartEl = el.value
    lastType = null
  }
  if ((props.data?.rows?.length || 0) > HEAVY_ROWS) option.animation = false

  // 换图型时同一个实例整体替换（notMerge）：canvas 一直有内容，不会闪白。
  // 但这里必须关掉入场动画——旧图刚消失、新图再从零慢慢长出来，观感最乱。
  // 新图直接以最终形态出现，再叠一层 140ms 的淡入，干净利落。
  const switched = lastType !== null && lastType !== props.card.type
  lastType = props.card.type
  if (switched) {
    option.animation = false
    chart.setOption(option, { notMerge: true })
    el.value.animate?.([{ opacity: 0.2 }, { opacity: 1 }], { duration: 140, easing: 'ease-out' })
    return
  }
  // 同类型的数据刷新走增量合并：坐标轴/图例状态保留，320ms 平滑变形。
  chart.setOption(option, { notMerge: false, replaceMerge: ['series'], lazyUpdate: true })
}

/** 同一帧里多次触发只画一次，避免连续 setOption 反复打断动画。 */
function scheduleDraw() {
  if (frame) return
  frame = raf(draw)
}

function destroy() {
  chart?.dispose()
  chart = null
  chartEl = null
  lastType = null
}

function resize() {
  chart?.resize()
}

onMounted(async () => {
  await nextTick()
  draw()
  window.addEventListener('cdc-theme-change', scheduleDraw)
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  if (frame) caf(frame)
  frame = 0
  window.removeEventListener('cdc-theme-change', scheduleDraw)
  window.removeEventListener('resize', resize)
  destroy()
})

// 数据整包替换、换图型、配置里的字段/汇总/筛选变化才需要重画；不再对整个 card 做 deep watch
watch(() => props.data, scheduleDraw)
watch(() => props.card.type, scheduleDraw)
watch(() => props.card.config, scheduleDraw, { deep: true })
</script>

<template>
  <div ref="el" class="bi-chart"/>
</template>
