<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createChart } from '../../chartStyle'
import { chartOptionFor } from './biChartOption'

const props = defineProps({ card: { type: Object, required: true }, data: { type: Object, default: null } })

const el = ref(null)
let chart = null

/**
 * 每次重画都重新算 option：chartStyle() 读的是 CSS 变量，缓存住切主题就不生效了。
 * 没有数据时直接销毁图表实例——留一个空盒子比什么都不显示更难看。
 */
function draw() {
  const option = chartOptionFor(props.card, props.data)
  if (!el.value || !option) {
    destroy()
    return
  }
  if (chart && chart.getDom() !== el.value) destroy()
  chart ||= createChart(el.value)
  chart.setOption(option, true)
  // 卡片刚挂载时容器可能还是 0 宽（异步数据、抽屉展开），echarts 会按 0 初始化。
  // 每次画完对比一次容器宽度，不一致就立刻 resize，避免图表缩在一角。
  const width = el.value.clientWidth
  if (width > 0 && Math.abs(chart.getWidth() - width) > 1) chart.resize()
}

function destroy() {
  chart?.dispose()
  chart = null
}

function resize() {
  chart?.resize()
}

onMounted(async () => {
  await nextTick()
  draw()
  window.addEventListener('cdc-theme-change', draw)
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('cdc-theme-change', draw)
  window.removeEventListener('resize', resize)
  destroy()
})

watch(() => [props.card, props.data], draw, { deep: true })
</script>

<template>
  <div ref="el" class="bi-chart"/>
</template>
