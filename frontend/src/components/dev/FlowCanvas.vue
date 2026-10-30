<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import FlowNodeCard from './FlowNodeCard.vue'
import {
  NODE_H, NODE_W, edgePathForNodes, fitView, hasEdge, nodeById, portOut, snap, toWorld, wouldCycle
} from './flowGraph'

const props = defineProps({
  graph: { type: Object, required: true },
  selectedId: { type: String, default: null },
  readonly: { type: Boolean, default: false }
})

const emit = defineEmits(['node-move', 'select', 'open', 'connect', 'delete-node', 'deselect'])

const root = ref(null)
const pan = ref({ x: 0, y: 0 })
const zoom = ref(1)
const linking = ref(null)   // { from, cursor: {x,y} }
const dragging = ref(null)  // { id, startX, startY, originX, originY }
const panning = ref(null)   // { startX, startY, originX, originY }

const nodes = computed(() => props.graph?.nodes || [])
const edges = computed(() => props.graph?.edges || [])

const worldSize = computed(() => {
  const maxX = nodes.value.reduce((acc, node) => Math.max(acc, node.x + NODE_W), 0)
  const maxY = nodes.value.reduce((acc, node) => Math.max(acc, node.y + NODE_H), 0)
  return { width: maxX + 480, height: maxY + 360 }
})

const linkingTargetId = computed(() => {
  if (!linking.value) return null
  return linking.value.targetId || null
})

const draftPath = computed(() => {
  if (!linking.value) return ''
  const from = nodeById(props.graph, linking.value.from)
  if (!from) return ''
  const start = portOut(from)
  const end = linking.value.cursor
  const dx = Math.max(48, Math.abs(end.x - start.x) / 2)
  return `M ${start.x} ${start.y} C ${start.x + dx} ${start.y}, ${end.x - dx} ${end.y}, ${end.x} ${end.y}`
})

function worldPoint(event) {
  return toWorld(event.clientX, event.clientY, root.value.getBoundingClientRect(), pan.value, zoom.value)
}

function pathOf(edge) {
  const from = nodeById(props.graph, edge.from)
  const to = nodeById(props.graph, edge.to)
  return from && to ? edgePathForNodes(from, to) : ''
}

// ---------------------------------------------------------------- dragging

// 拖动结束后浏览器仍会补一个 click。拖过节点就不该再当成「单击」，
// 否则每次挪位置都会顺带把配置抽屉弹出来。
const suppressClick = ref(false)
const DRAG_THRESHOLD = 3

function startDrag(node, event) {
  if (props.readonly) return
  suppressClick.value = false
  dragging.value = {
    id: node.id, startX: event.clientX, startY: event.clientY,
    originX: node.x, originY: node.y, moved: false
  }
  // 捕获阶段监听：卡片上的 pointerup 带 .stop，冒泡阶段收不到，拖拽状态就永远清不掉
  window.addEventListener('pointermove', onDragMove, true)
  window.addEventListener('pointerup', stopDrag, true)
  window.addEventListener('pointercancel', stopDrag, true)
}

function onDragMove(event) {
  if (!dragging.value) return
  const dx = event.clientX - dragging.value.startX
  const dy = event.clientY - dragging.value.startY
  if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
    dragging.value.moved = true
  }
  // 除以 zoom：屏幕位移换算成世界位移，否则放大后拖动会「跟不上手」
  const x = snap(dragging.value.originX + dx / zoom.value)
  const y = snap(dragging.value.originY + dy / zoom.value)
  // 不夹 0：画布本身可以平移到负坐标，节点却拖不过去会让人以为卡住了
  emit('node-move', { id: dragging.value.id, x, y })
}

function stopDrag() {
  if (dragging.value?.moved) {
    suppressClick.value = true
  }
  dragging.value = null
  window.removeEventListener('pointermove', onDragMove, true)
  window.removeEventListener('pointerup', stopDrag, true)
  window.removeEventListener('pointercancel', stopDrag, true)
}

/** 单击才打开配置栏；刚拖过的那一次 click 直接吞掉。 */
function onSelect(id) {
  if (suppressClick.value) {
    suppressClick.value = false
    return
  }
  emit('select', id)
}

// ---------------------------------------------------------------- linking

function startLink(node, event) {
  if (props.readonly) return
  linking.value = { from: node.id, cursor: worldPoint(event), targetId: null }
  window.addEventListener('pointermove', onLinkMove, true)
  window.addEventListener('pointerup', onLinkUp, true)
  window.addEventListener('pointercancel', cancelLink, true)
}

function onLinkMove(event) {
  if (!linking.value) return
  linking.value.cursor = worldPoint(event)
  const element = document.elementFromPoint(event.clientX, event.clientY)
  const card = element?.closest?.('.fnode')
  linking.value.targetId = card?.dataset?.nodeId || null
}

function cancelLink() {
  linking.value = null
  window.removeEventListener('pointermove', onLinkMove, true)
  window.removeEventListener('pointerup', onLinkUp, true)
  window.removeEventListener('pointercancel', cancelLink, true)
}

/**
 * 松开鼠标时统一在这里决定连到谁。
 *
 * 不依赖目标卡片的 pointerup：那个处理器必须带 .stop 才能避免冒泡到画布，
 * 而 .stop 又会让拖拽的 pointerup 收不到——两个需求互相打架。
 * 用 elementFromPoint 判定落点，两边都干净。
 */
function onLinkUp(event) {
  if (!linking.value) return
  const from = linking.value.from
  const element = document.elementFromPoint(event.clientX, event.clientY)
  const to = element?.closest?.('.fnode')?.dataset?.nodeId
  cancelLink()
  if (!to || to === from) return
  if (hasEdge(props.graph, from, to)) return
  if (wouldCycle(props.graph, from, to)) {
    emit('connect', { from, to, rejected: 'cycle' })
    return
  }
  emit('connect', { from, to })
}


// ------------------------------------------------------------------ pan

function onCanvasPointerDown(event) {
  if (event.button !== 0) return
  // 只有点在空白处才平移；点节点时由卡片自己处理
  if (event.target.closest?.('.fnode')) return
  // 缩放条就嵌在画布内部：不排除的话，点「适应画布」会顺带把配置抽屉关掉
  if (event.target.closest?.('.flow-zoom')) return
  panning.value = {
    startX: event.clientX, startY: event.clientY,
    originX: pan.value.x, originY: pan.value.y, moved: false
  }
  window.addEventListener('pointermove', onPanMove, true)
  window.addEventListener('pointerup', stopPan, true)
  window.addEventListener('pointercancel', stopPan, true)
}

function onPanMove(event) {
  if (!panning.value) return
  const dx = event.clientX - panning.value.startX
  const dy = event.clientY - panning.value.startY
  if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
    panning.value.moved = true
  }
  pan.value = {
    x: panning.value.originX + dx,
    y: panning.value.originY + dy
  }
}

/**
 * 空白处「拖动=平移、单击=取消选中」，和节点上「拖动=移动、单击=选中」保持同一套规则。
 * 3px 阈值沿用节点拖拽的判定，避免手抖把单击识别成拖动。
 */
function stopPan(event) {
  const clicked = panning.value && !panning.value.moved && event?.type === 'pointerup'
  panning.value = null
  window.removeEventListener('pointermove', onPanMove, true)
  window.removeEventListener('pointerup', stopPan, true)
  window.removeEventListener('pointercancel', stopPan, true)
  if (clicked) {
    emit('deselect')
  }
}

// ----------------------------------------------------------------- zoom

function onWheel(event) {
  const rect = root.value.getBoundingClientRect()
  const before = toWorld(event.clientX, event.clientY, rect, pan.value, zoom.value)
  const next = Math.min(1.6, Math.max(0.4, zoom.value * (1 - event.deltaY * 0.0015)))
  pan.value = {
    x: event.clientX - rect.left - before.x * next,
    y: event.clientY - rect.top - before.y * next
  }
  zoom.value = next
}

function zoomBy(factor) {
  const rect = root.value.getBoundingClientRect()
  const center = toWorld(rect.left + rect.width / 2, rect.top + rect.height / 2, rect, pan.value, zoom.value)
  const next = Math.min(1.6, Math.max(0.4, zoom.value * factor))
  pan.value = {
    x: rect.width / 2 - center.x * next,
    y: rect.height / 2 - center.y * next
  }
  zoom.value = next
}

function fit() {
  if (!root.value) return
  const rect = root.value.getBoundingClientRect()
  const view = fitView(nodes.value, { width: rect.width, height: rect.height })
  pan.value = { x: view.panX, y: view.panY }
  zoom.value = view.zoom
}

function onKeydown(event) {
  if (props.readonly) return
  if ((event.key === 'Delete' || event.key === 'Backspace') && props.selectedId) {
    event.preventDefault()
    emit('delete-node', props.selectedId)
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onDragMove, true)
  window.removeEventListener('pointerup', stopDrag, true)
  window.removeEventListener('pointercancel', stopDrag, true)
  window.removeEventListener('pointermove', onLinkMove, true)
  window.removeEventListener('pointerup', onLinkUp, true)
  window.removeEventListener('pointercancel', cancelLink, true)
  window.removeEventListener('pointermove', onPanMove, true)
  window.removeEventListener('pointerup', stopPan, true)
  window.removeEventListener('pointercancel', stopPan, true)
})

defineExpose({ fit, zoomBy })
</script>

<template>
  <div ref="root" class="flow-canvas" tabindex="0"
       @wheel.prevent="onWheel" @pointerdown="onCanvasPointerDown" @keydown="onKeydown">
    <div class="flow-world" :style="{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }">
      <svg class="flow-edges" :width="worldSize.width" :height="worldSize.height" aria-hidden="true">
        <path v-for="edge in edges" :key="`${edge.from}->${edge.to}`" :d="pathOf(edge)" class="flow-edge"/>
        <path v-if="linking" :d="draftPath" class="flow-edge is-draft"/>
      </svg>
      <FlowNodeCard v-for="node in nodes" :key="node.id" :node="node"
                    :selected="node.id === selectedId"
                    :target="node.id === linkingTargetId"
                    @drag-start="startDrag(node, $event)"
                    @select="onSelect"
                    @open="emit('open', $event)"
                    @connect-start="startLink(node, $event)"/>
    </div>

    <div class="flow-zoom">
      <button type="button" aria-label="放大" @click="zoomBy(1.15)">＋</button>
      <button type="button" aria-label="缩小" @click="zoomBy(1 / 1.15)">－</button>
      <button type="button" aria-label="适应画布" @click="fit">适应</button>
    </div>

    <p v-if="!nodes.length" class="flow-hint">从左侧拖入节点开始搭建数据流</p>
  </div>
</template>
