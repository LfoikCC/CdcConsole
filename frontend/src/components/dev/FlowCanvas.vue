<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import FlowNodeCard from './FlowNodeCard.vue'
import { acceptsInput, iconOf, needsTwoInputs } from './nodeTypes'
import {
  MAX_ZOOM, MIN_ZOOM, NODE_H, NODE_W, autoUpstream, edgeMidpoint, edgePath, fitView, hasEdge, nodeById, portIn,
  portOut, snap, toWorld, wouldCycle
} from './flowGraph'

const props = defineProps({
  graph: { type: Object, required: true },
  selectedId: { type: String, default: null },
  readonly: { type: Boolean, default: false },
  // 正在从调色板拖出来的算子 { type, operator }；用来实时预览会连到哪个节点
  dragging: { type: Object, default: null }
})

const emit = defineEmits(['node-move', 'select', 'open', 'connect', 'delete-node', 'deselect', 'drop-node',
  'select-edge', 'delete-edge'])

const root = ref(null)
const pan = ref({ x: 0, y: 0 })
const zoom = ref(1)
const linking = ref(null)   // { from, cursor: {x,y} }
const dragging = ref(null)  // { id, startX, startY, originX, originY }
const panning = ref(null)   // { startX, startY, originX, originY }
// 选中的连线。只存两端 id，边被删掉后 activeEdge 会自动算成 null，不用手动清
const selectedEdge = ref(null)

const nodes = computed(() => props.graph?.nodes || [])
const edges = computed(() => props.graph?.edges || [])

const worldSize = computed(() => {
  const maxX = nodes.value.reduce((acc, node) => Math.max(acc, node.x + NODE_W), 0)
  const maxY = nodes.value.reduce((acc, node) => Math.max(acc, node.y + NODE_H), 0)
  return { width: maxX + 480, height: maxY + 360 }
})

const linkingTargetId = computed(() => {
  if (!linking.value) return null
  return linking.value.snapId || null
})

const draftPath = computed(() => {
  if (!linking.value) return ''
  const from = nodeById(props.graph, linking.value.from)
  if (!from) return ''
  const start = portOut(from)
  // 吸附到目标节点时，虚线直接落到它的入端口上——松手连的就是这里
  const target = linking.value.snapId ? nodeById(props.graph, linking.value.snapId) : null
  if (target) {
    const incoming = edges.value.filter(item => item.to === target.id)
    const total = needsTwoInputs(target) ? 2 : 1
    return edgePath(start, portIn(target, Math.min(incoming.length, total - 1), total))
  }
  return edgePath(start, linking.value.cursor)
})

function worldPoint(event) {
  return toWorld(event.clientX, event.clientY, root.value.getBoundingClientRect(), pan.value, zoom.value)
}

/** 一条边的两端端口坐标。双输入节点按入边顺序决定落到上端口还是下端口，和后台「先连的为左」保持一致。 */
function endpointsOf(edge) {
  const from = nodeById(props.graph, edge.from)
  const to = nodeById(props.graph, edge.to)
  if (!from || !to) return null
  const incoming = edges.value.filter(item => item.to === edge.to)
  const index = Math.max(0, incoming.findIndex(item => item.from === edge.from))
  const total = needsTwoInputs(to) ? 2 : 1
  return { start: portOut(from), end: portIn(to, index, total) }
}

function pathOf(edge) {
  const points = endpointsOf(edge)
  return points ? edgePath(points.start, points.end) : ''
}

/** 选中的那条边；边已经不在了（删除、撤掉节点）就自动失效。 */
const activeEdge = computed(() => {
  if (!selectedEdge.value) return null
  const { from, to } = selectedEdge.value
  return edges.value.find(edge => edge.from === from && edge.to === to) || null
})

function isActiveEdge(edge) {
  return !!activeEdge.value && activeEdge.value.from === edge.from && activeEdge.value.to === edge.to
}

/**
 * 「删除」按钮的屏幕坐标。
 *
 * 位置在画布坐标系里算，再按 pan/zoom 换算成屏幕像素——按钮挂在不缩放的图层上，
 * 放大缩小时字不会跟着一起糊。
 */
const edgeMenu = computed(() => {
  if (!activeEdge.value) return null
  const points = endpointsOf(activeEdge.value)
  if (!points) return null
  const mid = edgeMidpoint(points.start, points.end)
  return { x: mid.x * zoom.value + pan.value.x, y: mid.y * zoom.value + pan.value.y }
})

function selectEdge(edge) {
  selectedEdge.value = { from: edge.from, to: edge.to }
  // 选中连线就等于离开节点编辑，父级据此收起配置抽屉
  emit('select-edge')
}

function removeActiveEdge() {
  if (!activeEdge.value) return
  const { from, to } = activeEdge.value
  selectedEdge.value = null
  emit('delete-edge', { from, to })
}

function clearEdgeSelection() {
  selectedEdge.value = null
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
  // 选了节点就说明不再关心那条连线
  clearEdgeSelection()
  emit('select', id)
}

// ---------------------------------------------------------------- linking

/** 吸附半径：指针离卡片边缘这么近，就认为「要连的就是它」。 */
const SNAP_RADIUS = 72

function startLink(node, event) {
  if (props.readonly) return
  linking.value = {
    from: node.id, cursor: worldPoint(event), hoverId: null, snapId: null,
    startX: event.clientX, startY: event.clientY, moved: false
  }
  window.addEventListener('pointermove', onLinkMove, true)
  window.addEventListener('pointerup', onLinkUp, true)
  window.addEventListener('pointercancel', cancelLink, true)
}

/**
 * 指针附近可以连上去的节点。
 *
 * <p>不用 elementFromPoint：卡片之间有间隙，靠「指针是否压在卡上」判定，
 * 差几像素就连不上，手感很硬。这里按世界坐标算到卡片矩形的距离，
 * 落在半径内就吸附，虚线也就有东西可以指。
 */
function linkCandidate(point) {
  if (!linking.value) return null
  const from = linking.value.from
  let best = null
  let bestDistance = Infinity
  for (const node of nodes.value) {
    // 输入类节点没有入端口，本来就不该成为目标
    if (node.id === from || !acceptsInput(node)) continue
    const dx = Math.max(node.x - point.x, 0, point.x - (node.x + NODE_W))
    const dy = Math.max(node.y - point.y, 0, point.y - (node.y + NODE_H))
    const distance = Math.hypot(dx, dy)
    if (distance < bestDistance) {
      bestDistance = distance
      best = node
    }
  }
  return bestDistance <= SNAP_RADIUS ? best : null
}

function onLinkMove(event) {
  if (!linking.value) return
  const point = worldPoint(event)
  linking.value.cursor = point
  // 用屏幕坐标判「拖动过」：世界坐标会被缩放放大，手感随 zoom 变
  if (Math.abs(event.clientX - linking.value.startX) > DRAG_THRESHOLD ||
      Math.abs(event.clientY - linking.value.startY) > DRAG_THRESHOLD) {
    linking.value.moved = true
  }
  const node = linkCandidate(point)
  linking.value.hoverId = node?.id || null
  // 已经有连线、或者会成环的，不吸附：虚线不该承诺一个按下去必然失败的连接
  linking.value.snapId = node && !hasEdge(props.graph, linking.value.from, node.id) &&
      !wouldCycle(props.graph, linking.value.from, node.id)
    ? node.id
    : null
}

function cancelLink() {
  linking.value = null
  window.removeEventListener('pointermove', onLinkMove, true)
  window.removeEventListener('pointerup', onLinkUp, true)
  window.removeEventListener('pointercancel', cancelLink, true)
}

/**
 * 松开鼠标：落点附近有合法目标就连上去。
 *
 * <p>落点在候选节点上、但重复或成环时仍然给提示——静默失败比报错更让人困惑。
 * 没拖动过（只是点了一下端口）什么也不做，避免点到端口就把旁边的节点连上。
 */
function onLinkUp(event) {
  if (!linking.value) return
  const from = linking.value.from
  const moved = linking.value.moved
  const point = worldPoint(event)
  const node = linkCandidate(point)
  cancelLink()
  if (!moved || !node) return
  if (node.id === from) return
  if (hasEdge(props.graph, from, node.id)) return
  if (wouldCycle(props.graph, from, node.id)) {
    emit('connect', { from, to: node.id, rejected: 'cycle' })
    return
  }
  emit('connect', { from, to: node.id })
}


// ------------------------------------------------------------------ pan

function onCanvasPointerDown(event) {
  if (event.button !== 0) return
  // 只有点在空白处才平移；点节点时由卡片自己处理
  if (event.target.closest?.('.fnode')) return
  // 连线有自己的点击语义（选中→出删除按钮），不能被当成空白处按下去
  if (event.target.closest?.('.flow-edge-hit')) return
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
    clearEdgeSelection()
    emit('deselect')
  }
}

// ----------------------------------------------------------------- zoom

function onWheel(event) {
  const rect = root.value.getBoundingClientRect()
  const before = toWorld(event.clientX, event.clientY, rect, pan.value, zoom.value)
  const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom.value * (1 - event.deltaY * 0.0015)))
  pan.value = {
    x: event.clientX - rect.left - before.x * next,
    y: event.clientY - rect.top - before.y * next
  }
  zoom.value = next
}

function zoomBy(factor) {
  const rect = root.value.getBoundingClientRect()
  const center = toWorld(rect.left + rect.width / 2, rect.top + rect.height / 2, rect, pan.value, zoom.value)
  const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom.value * factor))
  pan.value = {
    x: rect.width / 2 - center.x * next,
    y: rect.height / 2 - center.y * next
  }
  zoom.value = next
}

// ------------------------------------------------------------- 拖放添加

/** 从调色板拖新节点时的落点预览：{ x, y, upstreamId, from, to }。 */
const dropHint = ref(null)
/** 自动连线的吸附半径，和落点判定共用一套。 */
const DROP_CONNECT_RADIUS = 260

/**
 * 算出「现在松手会落在哪、会连到谁」。
 *
 * <p>用和 onDrop 完全相同的取整方式造一个虚拟节点，再走 autoUpstream 同一套判定，
 * 所以虚线指向的和真正连上的永远是同一个节点，不会出现「预览指 A、实际连 B」。
 */
function hintAt(event) {
  const option = props.dragging
  if (!option) return null
  const point = worldPoint(event)
  const ghost = {
    id: '__drop__',
    type: option.type,
    x: snap(point.x - NODE_W / 2),
    y: snap(point.y - NODE_H / 2)
  }
  // 输入类节点没有上游，只画落点虚框，不画连线
  const upstream = acceptsInput({ type: option.type })
    ? autoUpstream(props.graph, ghost, DROP_CONNECT_RADIUS)
    : null
  return {
    x: ghost.x,
    y: ghost.y,
    upstreamId: upstream?.id || null,
    from: upstream ? portOut(upstream) : null,
    to: upstream ? portIn(ghost) : null
  }
}

const dropPath = computed(() => (dropHint.value?.from ? edgePath(dropHint.value.from, dropHint.value.to) : ''))

/** 落点虚框里的图标：按算子类型取，和画布上真实节点用的是同一套。 */
const dropIcon = computed(() => (props.dragging
  ? iconOf({ type: props.dragging.type, config: { operator: props.dragging.operator } })
  : null))

function onDragOver(event) {
  // 不 preventDefault 的话浏览器不会派发 drop
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  dropHint.value = hintAt(event)
}

function onDragLeave(event) {
  // 从画布移到子元素上也会触发 dragleave，relatedTarget 还在画布里就别清
  if (event.relatedTarget && root.value?.contains(event.relatedTarget)) return
  dropHint.value = null
}

function onDrop(event) {
  const point = worldPoint(event)
  dropHint.value = null
  // 以指针为节点中心，落下位置才符合直觉
  emit('drop-node', { x: snap(point.x - NODE_W / 2), y: snap(point.y - NODE_H / 2) })
}

// 拖拽结束（放手或取消）时预览必须消失，否则会留一条幽灵虚线
watch(() => props.dragging, value => {
  if (!value) dropHint.value = null
})


function fit() {
  if (!root.value) return
  const rect = root.value.getBoundingClientRect()
  const view = fitView(nodes.value, { width: rect.width, height: rect.height })
  pan.value = { x: view.panX, y: view.panY }
  zoom.value = view.zoom
}

function onKeydown(event) {
  if (props.readonly) return
  if (event.key !== 'Delete' && event.key !== 'Backspace') return
  // 连线优先：选中连线时按删除只断这条边，不误删刚编辑过的节点
  if (activeEdge.value) {
    event.preventDefault()
    removeActiveEdge()
    return
  }
  if (props.selectedId) {
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
       @wheel.prevent="onWheel" @pointerdown="onCanvasPointerDown" @keydown="onKeydown"
       @dragover.prevent="onDragOver" @dragleave="onDragLeave" @drop.prevent="onDrop">
    <div class="flow-world" :style="{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }">
      <svg class="flow-edges" :width="worldSize.width" :height="worldSize.height" aria-hidden="true">
        <!-- 命中层：1.5px 的线太难点中，垫一条透明的粗线来接点击 -->
        <path v-for="edge in edges" :key="`hit-${edge.from}->${edge.to}`" :d="pathOf(edge)"
              class="flow-edge-hit" @click.stop="selectEdge(edge)"/>
        <path v-for="edge in edges" :key="`${edge.from}->${edge.to}`" :d="pathOf(edge)" class="flow-edge"
              :class="{ 'is-selected': isActiveEdge(edge) }"/>
        <path v-if="linking" :d="draftPath" class="flow-edge is-draft"
              :class="{ 'is-snap': !!linking.snapId }"/>
        <!-- 从调色板拖算子时：落点虚框 + 会自动连上的那条虚线 -->
        <path v-if="dropPath" :d="dropPath" class="flow-edge is-draft is-snap"/>
      </svg>
      <div v-if="dropHint" class="fnode-ghost"
           :style="{ left: `${dropHint.x}px`, top: `${dropHint.y}px`, width: `${NODE_W}px`, height: `${NODE_H}px` }">
        <span v-if="dropIcon" class="fnode-ghost-icon"><component :is="dropIcon" :size="22"/></span>
      </div>
      <FlowNodeCard v-for="node in nodes" :key="node.id" :node="node"
                    :selected="node.id === selectedId"
                    :target="node.id === linkingTargetId || node.id === dropHint?.upstreamId"
                    @drag-start="startDrag(node, $event)"
                    @select="onSelect"
                    @open="emit('open', $event)"
                    @connect-start="startLink(node, $event)"/>
    </div>

    <!-- 删除按钮挂在不缩放的图层上，位置由连线中点换算成屏幕像素 -->
    <button v-if="edgeMenu" type="button" class="flow-edge-menu"
            :style="{ left: `${edgeMenu.x}px`, top: `${edgeMenu.y + 16}px` }"
            @pointerdown.stop @click.stop="removeActiveEdge">删除</button>

    <div class="flow-zoom">
      <button type="button" aria-label="放大" @click="zoomBy(1.15)">＋</button>
      <button type="button" aria-label="缩小" @click="zoomBy(1 / 1.15)">－</button>
      <button type="button" aria-label="适应画布" @click="fit">适应</button>
    </div>

    <p v-if="!nodes.length" class="flow-hint">从左侧拖入节点开始搭建数据流</p>
  </div>
</template>
