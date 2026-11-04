<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BiCard from './BiCard.vue'
import { GAP, MIN_H, MIN_W, ROW_H, clampRect, colWidth, gridToPx, packMasonry, totalRows } from './biLayout'

const props = defineProps({
  cards: { type: Array, default: () => [] },
  selectedKey: { type: String, default: '' },
  editable: { type: Boolean, default: false },
  dataMap: { type: Object, default: () => ({}) },
  loadingMap: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['select', 'change', 'open'])

const stage = ref(null)
const width = ref(0)
const drag = ref(null)
/** 拖动时卡片相对排布位置的像素偏移，让它死死跟着鼠标 */
const offset = ref({ dx: 0, dy: 0 })
/** 缩放时先按像素预览，松手才换算成格数 */
const resizing = ref(null)
const activeKey = ref('')
let observer = null

// 拖动结束后浏览器仍会补一个 click/dblclick。拖过就不再当成「单击」，
// 否则每次挪位置都会顺带把配置抽屉弹出来。
const DRAG_THRESHOLD = 3
let moved = false

// 位置全部由瀑布流算：卡片只带 w / h，x / y 是算出来的，所以永远不会重叠
const packed = computed(() => packMasonry(props.cards))
const layout = computed(() => packed.value.map((card, index) => ({
  card,
  index,
  key: keyOf(card),
  style: gridToPx(card, width.value)
})))
const canvasHeight = computed(() => (Math.max(totalRows(packed.value), 6) + 1) * (ROW_H + GAP))

function keyOf(card) {
  return card._k || (card.id != null ? 'c' + card.id : '')
}

function rectOf(style) {
  return {
    left: parseFloat(style.left) || 0,
    top: parseFloat(style.top) || 0,
    width: parseFloat(style.width) || 0,
    height: parseFloat(style.height) || 0
  }
}

onMounted(() => {
  if (!stage.value) return
  // 容器宽度决定一列多宽；jsdom 里量到 0，gridToPx 会退回兜底宽度
  observer = new ResizeObserver(entries => {
    width.value = entries[0]?.contentRect?.width || stage.value?.clientWidth || 0
  })
  observer.observe(stage.value)
  width.value = stage.value.clientWidth || 0
})

onBeforeUnmount(() => {
  observer?.disconnect()
  stop()
})

function start(event, card, mode) {
  if (!props.editable || event.button !== 0) return
  moved = false
  const key = keyOf(card)
  const item = layout.value.find(entry => entry.key === key)
  const rect = item ? rectOf(item.style) : rectOf(gridToPx(card, width.value))
  drag.value = {
    key,
    mode,
    startX: event.clientX,
    startY: event.clientY,
    grabX: event.clientX - rect.left,
    grabY: event.clientY - rect.top,
    width: rect.width,
    height: rect.height
  }
  activeKey.value = key
  offset.value = { dx: 0, dy: 0 }
  resizing.value = null
  // 捕获阶段：卡片内部有 .stop 的 pointerup，冒泡阶段收不到
  window.addEventListener('pointermove', onMove, true)
  window.addEventListener('pointerup', stop, true)
  window.addEventListener('pointercancel', stop, true)
}

function onMove(event) {
  const state = drag.value
  if (!state) return
  const dx = event.clientX - state.startX
  const dy = event.clientY - state.startY
  if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) moved = true

  if (state.mode === 'move') {
    // 先按指针位置决定插到第几个，再让卡片按像素跟手
    const target = targetIndex(event, state.key)
    if (target !== indexOf(state.key)) moveCard(state.key, target)
    const item = layout.value.find(entry => entry.key === state.key)
    if (!item) return
    const rect = rectOf(item.style)
    offset.value = {
      dx: (event.clientX - state.grabX) - rect.left,
      dy: (event.clientY - state.grabY) - rect.top
    }
    return
  }

  const nextWidth = state.width + (state.mode.includes('e') ? dx : 0)
  const nextHeight = state.height + (state.mode.includes('s') ? dy : 0)
  resizing.value = {
    width: Math.max(MIN_W * colWidth(width.value) + (MIN_W - 1) * GAP, nextWidth),
    height: Math.max(MIN_H * ROW_H + (MIN_H - 1) * GAP, nextHeight)
  }
}

/** 指针落在谁的上半部分就插到谁前面。瀑布流自上而下排，按 y 判断就够。 */
function targetIndex(event, key) {
  const others = layout.value.filter(entry => entry.key !== key)
  for (let i = 0; i < others.length; i++) {
    const rect = rectOf(others[i].style)
    if (event.clientY < rect.top + rect.height / 2) return i
  }
  return others.length
}

function indexOf(key) {
  return props.cards.findIndex(card => keyOf(card) === key)
}

function moveCard(key, target) {
  const list = props.cards
  const from = list.findIndex(card => keyOf(card) === key)
  if (from < 0) return
  const [item] = list.splice(from, 1)
  list.splice(Math.max(0, Math.min(target, list.length)), 0, item)
}

function stop() {
  const state = drag.value
  if (state && moved) {
    if (state.mode !== 'move' && resizing.value) {
      const card = props.cards.find(item => keyOf(item) === state.key)
      if (card) {
        const w = Math.round((resizing.value.width + GAP) / (colWidth(width.value) + GAP))
        const h = Math.round((resizing.value.height + GAP) / (ROW_H + GAP))
        Object.assign(card, clampRect({ x: card.x, y: card.y, w, h }))
      }
    }
    emit('change')
  }
  drag.value = null
  activeKey.value = ''
  resizing.value = null
  offset.value = { dx: 0, dy: 0 }
  window.removeEventListener('pointermove', onMove, true)
  window.removeEventListener('pointerup', stop, true)
  window.removeEventListener('pointercancel', stop, true)
}

function onSelect(key) {
  if (moved) {
    moved = false
    return
  }
  emit('select', key)
}

/** 正在拖 / 正在缩的那张卡要覆盖排布给的位置。 */
function styleOf(item) {
  const active = activeKey.value && item.key === activeKey.value
  if (!active) return item.style
  if (drag.value?.mode === 'move') {
    return {
      ...item.style,
      transform: `translate(${offset.value.dx}px, ${offset.value.dy}px)`,
      transition: 'none',
      zIndex: 6
    }
  }
  if (resizing.value) {
    return {
      ...item.style,
      width: `${resizing.value.width}px`,
      height: `${resizing.value.height}px`,
      transition: 'none',
      zIndex: 6
    }
  }
  return item.style
}
</script>

<template>
  <div ref="stage" class="bi-grid" :style="{ minHeight: canvasHeight + 'px' }">
    <BiCard v-for="item in layout" :key="item.key"
            :card="item.card" :rect="styleOf(item)"
            :selected="item.key === selectedKey"
            :dragging="item.key === activeKey && drag?.mode === 'move'"
            :editable="editable"
            :data="dataMap[item.key] || null"
            :loading="!!loadingMap[item.key]"
            @select="onSelect(item.key)"
            @drag-start="start($event, item.card, 'move')"
            @resize-start="(event, mode) => start(event, item.card, mode)"
            @open="emit('open', item.key)"/>
  </div>
</template>
