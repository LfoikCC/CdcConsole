<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BiCard from './BiCard.vue'
import {
  COLS, GAP, MIN_H, MIN_W, ROW_H, colWidth, compact, gridToPx, placeCard, totalRows, withRects
} from './biLayout'

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
/** 拖动时自动滚动的容器（.bi-stage）；没有可滚动的祖先就退回整个文档 */
let scrollEl = null
let scrollRaf = 0
const pointer = { x: 0, y: 0 }

// 拖动结束后浏览器仍会补一个 click/dblclick。拖过就不再当成「单击」，
// 否则每次挪位置都会顺带把配置抽屉弹出来。
const DRAG_THRESHOLD = 3
let moved = false

// 卡片自己带 x / y：可以左右拖，也能并排；缺坐标的老数据在 withRects 里补空位
const packed = computed(() => withRects(props.cards))
const layout = computed(() => packed.value.map((card, index) => ({
  card,
  index,
  key: keyOf(card),
  style: gridToPx(card, width.value)
})))
/**
 * 画布高度正好到最后一行的下沿，末尾不留一整行空白。
 *
 * <p>以前是 (行数 + 1) * 行距，最后一张卡下面空出 52px，页面看着像没排完。
 * 空看板仍留 6 行，否则没地方拖卡片。
 */
const canvasHeight = computed(() => {
  const rows = totalRows(packed.value)
  const step = ROW_H + GAP
  return (rows > 0 ? rows : 6) * step - GAP
})

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
  scrollEl = scrollHost()
  drag.value = {
    key,
    mode,
    startX: event.clientX,
    startY: event.clientY,
    grabX: event.clientX - rect.left,
    grabY: event.clientY - rect.top,
    width: rect.width,
    height: rect.height,
    // 容器滚了多少也要算进去：拖动中滚屏时，同一个指针位置对应的是更下面的格子
    scrollTop: scrollEl ? scrollEl.scrollTop : 0,
    // 拖动前的位置快照：每一帧都从它重算，被推开的卡才会自己回位
    baseline: props.cards.map(entry => ({ x: entry.x, y: entry.y, w: entry.w, h: entry.h }))
  }
  pointer.x = event.clientX
  pointer.y = event.clientY
  queueScroll()
  activeKey.value = key
  offset.value = { dx: 0, dy: 0 }
  resizing.value = null
  // 捕获阶段：卡片内部有 .stop 的 pointerup，冒泡阶段收不到
  window.addEventListener('pointermove', onMove, true)
  window.addEventListener('pointerup', stop, true)
  window.addEventListener('pointercancel', stop, true)
}

function restore(baseline) {
  props.cards.forEach((card, index) => {
    const rect = baseline[index]
    if (rect) Object.assign(card, rect)
  })
}

function onMove(event) {
  pointer.x = event.clientX
  pointer.y = event.clientY
  moveTo(event.clientX, event.clientY)
}

/**
 * 把卡片挪到指针位置。
 *
 * <p>单独拆出来是因为「自动滚屏」时指针没动、内容动了：
 * 那一帧指针坐标不变，但目标格子要跟着滚过的距离往下走，所以还得再算一次。
 */
function moveTo(clientX, clientY) {
  const state = drag.value
  if (!state) return
  const scrolled = scrollEl ? scrollEl.scrollTop - state.scrollTop : 0
  const dx = clientX - state.startX
  const dy = clientY - state.startY
  if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) moved = true

  if (state.mode === 'move') {
    const index = indexOf(state.key)
    const card = props.cards[index]
    if (!card) return
    // 指针左上角 → 格坐标，允许横着放；贴到左右边界时卡片停住，指针继续走
    const stepX = colWidth(width.value) + GAP
    const stepY = ROW_H + GAP
    const wantLeft = Math.min(Math.max(clientX - state.grabX, 0), Math.max(0, (COLS - card.w) * stepX))
    const wantTop = Math.max(clientY - state.grabY + scrolled, 0)
    const x = Math.round(wantLeft / stepX)
    const y = Math.round(wantTop / stepY)
    restore(state.baseline)
    placeCard(props.cards, index, { x, y, w: card.w, h: card.h })
    // 被让开的空位马上补上：被拖的这张先不动（它要跟着指针），松手时再一起补
    compact(props.cards, state.key)
    const rect = rectOf(gridToPx(card, width.value))
    offset.value = {
      dx: (clientX - state.grabX) - rect.left,
      dy: (clientY - state.grabY + scrolled) - rect.top
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

function indexOf(key) {
  return props.cards.findIndex(card => keyOf(card) === key)
}

function stop() {
  const state = drag.value
  if (state && moved) {
    const index = indexOf(state.key)
    const card = props.cards[index]
    if (card && state.mode !== 'move' && resizing.value) {
      const w = Math.round((resizing.value.width + GAP) / (colWidth(width.value) + GAP))
      const h = Math.round((resizing.value.height + GAP) / (ROW_H + GAP))
      restore(state.baseline)
      placeCard(props.cards, index, { x: card.x, y: card.y, w, h })
    }
    // 松手后连被拖的这张一起往上补，上方不留空洞
    compact(props.cards)
    emit('change')
  }
  drag.value = null
  activeKey.value = ''
  resizing.value = null
  offset.value = { dx: 0, dy: 0 }
  window.removeEventListener('pointermove', onMove, true)
  window.removeEventListener('pointerup', stop, true)
  window.removeEventListener('pointercancel', stop, true)
  stopAutoScroll()
}

// ---------------------------------------------------------------- 拖动时自动滚屏
/** 指针离容器上下边缘多近开始滚（px） */
const SCROLL_EDGE = 64
/** 最贴近边缘时的每帧滚动量（px），越靠边越快 */
const SCROLL_MAX = 18

/** 往上找第一层真正能纵向滚的祖先；找不到就用整页。 */
function scrollHost() {
  let node = stage.value?.parentElement
  while (node) {
    const style = getComputedStyle(node)
    if (/(auto|scroll)/.test(style.overflowY) && node.scrollHeight > node.clientHeight + 1) return node
    node = node.parentElement
  }
  return document.scrollingElement || document.documentElement
}

function edgeBox(el) {
  if (el === document.scrollingElement || el === document.documentElement) {
    return { top: 0, bottom: window.innerHeight }
  }
  const rect = el.getBoundingClientRect()
  return { top: rect.top, bottom: rect.bottom }
}

/**
 * 拖到底部/顶部时自动滚。
 *
 * <p>页面很长时，最下面的卡要拖到最上面得先滚回去——以前只能松手、滚屏、再拖一次。
 * 这里每帧按指针离边缘的距离滚一点，滚完再重算一次落点。
 */
function autoScrollTick() {
  scrollRaf = 0
  const state = drag.value
  if (!state || !scrollEl) return
  const box = edgeBox(scrollEl)
  let delta = 0
  if (pointer.y < box.top + SCROLL_EDGE) {
    delta = -((box.top + SCROLL_EDGE - pointer.y) / SCROLL_EDGE) * SCROLL_MAX
  } else if (pointer.y > box.bottom - SCROLL_EDGE) {
    delta = ((pointer.y - (box.bottom - SCROLL_EDGE)) / SCROLL_EDGE) * SCROLL_MAX
  }
  if (delta) {
    const before = scrollEl.scrollTop
    scrollEl.scrollTop = before + delta
    if (scrollEl.scrollTop !== before) moveTo(pointer.x, pointer.y)
  }
  queueScroll()
}

function queueScroll() {
  if (!scrollRaf) scrollRaf = requestAnimationFrame(autoScrollTick)
}

function stopAutoScroll() {
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  scrollRaf = 0
  scrollEl = null
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
