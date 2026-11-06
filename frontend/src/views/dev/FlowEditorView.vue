<script setup>
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ChevronDown, ChevronUp, Play, Save, Search, ShieldCheck } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import FlowCanvas from '../../components/dev/FlowCanvas.vue'
import NodeDrawer from '../../components/dev/NodeDrawer.vue'
import { acceptsInput, createNode, metaOf, PALETTE_GROUPS } from '../../components/dev/nodeTypes'
import { autoUpstream, nextNodeId, upstreamNodesOf } from '../../components/dev/flowGraph'

const route = useRoute()
const router = useRouter()

const flowId = ref(route.params.id ? Number(route.params.id) : null)
// 从某个文件夹里点「新建」时带过来，建完直接落在该文件夹
const initialFolderId = route.query.folder ? Number(route.query.folder) : null
const name = ref('未命名数据流')
const description = ref('')
const graph = ref({ nodes: [], edges: [] })
const selectedId = ref(null)
const drawerOpen = ref(false)
const preview = ref(null)
// 底部预览条默认收起，拿到数据后自动展开
const previewOpen = ref(false)
const upstreamColumns = ref([])
const secondUpstreamColumns = ref([])
const busy = ref(false)
const saving = ref(false)
const loading = ref(false)
const snapshot = ref('')
const canvas = ref(null)
const drawerRef = ref(null)
const sources = ref([])
const targets = ref([])
const datasets = ref([])
const paletteQuery = ref('')

const paletteGroups = computed(() => {
  const query = paletteQuery.value.trim().toLowerCase()
  if (!query) return PALETTE_GROUPS
  return PALETTE_GROUPS
    .map(group => ({
      label: group.label,
      items: group.items.filter(item =>
        item.label.toLowerCase().includes(query) ||
        (item.operator || '').toLowerCase().includes(query))
    }))
    .filter(group => group.items.length)
})

const selectedNode = computed(() => graph.value.nodes.find(node => node.id === selectedId.value) || null)
const dirty = computed(() => JSON.stringify(graph.value) !== snapshot.value)

/** 两个上游的显示名：改过名就用节点名，否则用表名——抽屉只有 280px，带库名会截断。 */
const upstreamNodes = computed(() => (selectedNode.value ? upstreamNodesOf(graph.value, selectedNode.value.id) : []))
const upstreamLabel = computed(() => describeNode(upstreamNodes.value[0]))
const secondUpstreamLabel = computed(() => describeNode(upstreamNodes.value[1]))

function describeNode(node) {
  if (!node) return ''
  const meta = metaOf(node.type)
  // 没改过名时节点名就是类型名（「数据库输入」），没有信息量，改用表名/算子名
  if (node.name && node.name !== meta.label) return node.name
  const subtitle = meta.subtitle ? meta.subtitle(node.config || {}) : ''
  // 「CDC-SOURCE.orders」只留「orders」，否则右半边被省略号吃掉
  return subtitle ? subtitle.split('.').pop() : meta.label
}

const runLabels = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELED: '已取消' }

function serialize(value) {
  return JSON.stringify(value)
}

async function reloadDatasets() {
  try {
    datasets.value = await api('/datasets?size=500').then(body => body.items || [])
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function load() {
  loading.value = true
  try {
    sources.value = await api('/sources').then(body => body.items || [])
    targets.value = await api('/targets').then(body => body.items || [])
    datasets.value = await api('/datasets?size=500').then(body => body.items || [])
    if (flowId.value) {
      const flow = await api(`/flows/${flowId.value}`)
      name.value = flow.name
      description.value = flow.description || ''
      graph.value = normalize(flow.graph)
    }
    snapshot.value = serialize(graph.value)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function normalize(value) {
  return {
    nodes: (value?.nodes || []).map(node => ({ ...node, x: node.x ?? 0, y: node.y ?? 0, config: node.config || {} })),
    edges: value?.edges || []
  }
}

/** 拖动中的调色板条目；dragstart 时记下来，drop 时才知道要创建什么。 */
const paletteDrag = ref(null)
/** 自动连线的吸附半径：落点离某个节点足够近才连线，避免莫名其妙连到远处的节点。 */
const AUTO_CONNECT_RADIUS = 260

function endPaletteDrag() {
  paletteDrag.value = null
}

function startPaletteDrag(event, option) {
  paletteDrag.value = option
  event.dataTransfer.effectAllowed = 'copy'
  // 某些浏览器不写入数据就不允许拖拽
  event.dataTransfer.setData('text/plain', option.label || '')
}

/** 点预览条：收起时重新取一次数据再展开，展开时折叠。 */
async function togglePreview() {
  if (previewOpen.value) {
    previewOpen.value = false
    return
  }
  await runPreview()
}

/** 从调色板拖到画布：落在指针位置，并尝试与最近的节点连线。 */
function onDropNode({ x, y }) {
  if (!paletteDrag.value) return
  const option = paletteDrag.value
  paletteDrag.value = null
  createNodeAt(option.type, option.operator, { x, y }, { openDrawer: false })
}

function createNodeAt(type, operator, position, { openDrawer: open } = {}) {
  const id = nextNodeId(graph.value.nodes)
  const node = createNode(type, id, position.x, position.y, operator)
  graph.value.nodes.push(node)
  autoConnect(node)
  selectedId.value = id
  // 拖进来只选中、不弹抽屉：连加几个节点时抽屉反复弹出会很打断
  if (open) drawerOpen.value = true
  preview.value = null
  upstreamColumns.value = []
  secondUpstreamColumns.value = []
}

/**
 * 新节点自动连到最近的「上游候选」。
 *
 * <p>输入类节点没有入端口，输出类节点不该再有下游，两者都跳过；
 * 连线规则与手工拖拽一致（不重复、不成环）。判定逻辑放在 flowGraph.autoUpstream，
 * 画布上的虚线预览调用的是同一个函数，预览和实际结果不会打架。
 */
function autoConnect(node) {
  if (!acceptsInput(node)) return
  const upstream = autoUpstream(graph.value, node, AUTO_CONNECT_RADIUS)
  if (upstream) graph.value.edges.push({ from: upstream.id, to: node.id })
}

/** 点画布空白处：收起抽屉并取消选中。 */
function deselect() {
  selectedId.value = null
  drawerOpen.value = false
  preview.value = null
  previewOpen.value = false
  upstreamColumns.value = []
  secondUpstreamColumns.value = []
}

function onNodeMove({ id, x, y }) {
  const node = graph.value.nodes.find(item => item.id === id)
  if (node) {
    node.x = x
    node.y = y
  }
}

function onConnect({ from, to, rejected }) {
  if (rejected === 'cycle') {
    CdcMessage.warning('这条连线会形成环，已忽略')
    return
  }
  graph.value.edges.push({ from, to })
}

/** 删除一条连线：只断这条边，两端的节点都留着。 */
function deleteEdge({ from, to }) {
  graph.value.edges = graph.value.edges.filter(edge => !(edge.from === from && edge.to === to))
  preview.value = null
}

function deleteNode(id) {
  graph.value.nodes = graph.value.nodes.filter(node => node.id !== id)
  graph.value.edges = graph.value.edges.filter(edge => edge.from !== id && edge.to !== id)
  if (selectedId.value === id) {
    selectedId.value = null
    drawerOpen.value = false
  }
}

function openDrawer(id) {
  // 点的还是当前这个算子：预览保持原样，既不重新取数也不收起
  const sameNode = id === selectedId.value
  selectedId.value = id
  drawerOpen.value = true
  if (sameNode) return
  // 换了算子，上一条预览已经不属于当前节点，收起并清空
  preview.value = null
  previewOpen.value = false
  upstreamColumns.value = []
  secondUpstreamColumns.value = []
  const node = graph.value.nodes.find(item => item.id === id)
  // 输出节点同样需要上游字段：数据库输出要靠它做字段映射
  if (node && acceptsInput(node)) {
    loadUpstreamColumns(node)
  }
}

async function loadUpstreamColumns(node) {
  // 双输入算子按连线顺序取两个上游；单输入只会有一个
  const upstreams = upstreamNodesOf(graph.value, node.id)
  if (!upstreams.length) return
  try {
    const id = await ensureSaved()
    const results = await Promise.all(upstreams.map(upstream => api(`/flows/${id}/nodes/${upstream.id}/preview`, {
      method: 'POST',
      body: JSON.stringify({ limit: 1 })
    })))
    upstreamColumns.value = (results[0]?.columns || []).map(label => ({ label }))
    secondUpstreamColumns.value = (results[1]?.columns || []).map(label => ({ label }))
  } catch (e) {
    upstreamColumns.value = []
    secondUpstreamColumns.value = []
  }
}

function applyConfig({ config, name }) {
  const node = graph.value.nodes.find(item => item.id === selectedId.value)
  if (!node) return
  node.config = config
  // 名字由用户在抽屉里显式填写；留空时退回算子/节点类型的默认名
  if (name) node.name = name
  drawerOpen.value = false
}

async function save() {
  if (!name.value.trim()) {
    CdcMessage.warning('请填写数据流名称')
    throw new Error('name required')
  }
  saving.value = true
  try {
    const body = JSON.stringify({
      name: name.value.trim(), description: description.value, graph: graph.value,
      folderId: flowId.value ? undefined : initialFolderId
    })
    if (flowId.value) {
      await api(`/flows/${flowId.value}`, { method: 'PUT', body })
    } else {
      const created = await api('/flows', { method: 'POST', body })
      flowId.value = created.id
      // 用 history 换地址而不是 router.replace：后者会触发 RouterView 重挂，
      // 抽屉和预览结果会一起丢掉，表现就是「点预览没反应」
      window.history.replaceState(window.history.state, '', `/dev/flows/${created.id}/edit`)
    }
    snapshot.value = serialize(graph.value)
    CdcMessage.success('数据流已保存')
    return flowId.value
  } catch (e) {
    CdcMessage.error(e.message)
    throw e
  } finally {
    saving.value = false
  }
}

async function ensureSaved() {
  if (!flowId.value || dirty.value) return save()
  return flowId.value
}

async function runPreview() {
  const node = selectedNode.value
  if (!node) return
  // 预览条读不到抽屉里的草稿，必须显式取一次，否则会拿旧配置去跑
  const draft = drawerRef.value?.snapshot?.()
  // 预览要用抽屉里当前编辑的值，而不是上一次「应用」后的旧配置
  if (draft?.config) {
    node.config = draft.config
    if (draft.name) node.name = draft.name
  }
  busy.value = true
  preview.value = null
  try {
    const id = await ensureSaved()
    preview.value = await api(`/flows/${id}/nodes/${node.id}/preview`, {
      method: 'POST',
      body: JSON.stringify({ limit: 100 })
    })
    // 拿到数据就展开底部面板，省一次点击
    previewOpen.value = true
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function deleteSelected() {
  if (!selectedNode.value) return
  try {
    await CdcMessageBox.confirm('删除后该节点及其连线会一并移除。', '删除节点', { type: 'warning' })
  } catch {
    return
  }
  deleteNode(selectedNode.value.id)
}

async function validate() {
  busy.value = true
  try {
    const report = await api('/flows/validate', {
      method: 'POST',
      body: JSON.stringify({ name: name.value, description: description.value, graph: graph.value })
    })
    if (report.valid) {
      CdcMessage.success('校验通过')
    } else {
      CdcMessage.warning(report.errors.map(item => `${item.nodeKey || ''}${item.message}`).join('；'))
    }
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function run() {
  busy.value = true
  try {
    const id = await ensureSaved()
    const result = await api(`/flows/${id}/runs`, { method: 'POST' })
    router.push(`/dev/flows/${id}/runs/${result.id}`)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await CdcMessageBox.confirm('当前配置尚未保存，确认离开？', '放弃修改')
    return true
  } catch {
    return false
  }
})

onMounted(load)
</script>

<template>
  <div class="flow-editor" v-loading="loading">
    <header class="flow-editor-head">
      <button class="icon-btn" aria-label="返回" @click="router.push('/dev/flows')"><ArrowLeft :size="16"/></button>
      <input v-model="name" class="fe-name" aria-label="数据流名称" placeholder="数据流名称"/>
      <span v-if="dirty" class="fe-dirty">未保存</span>
      <div class="fe-actions">
        <button class="console-btn" :disabled="busy" @click="validate"><ShieldCheck :size="14"/>校验</button>
        <button class="console-btn" :disabled="busy || saving" @click="save"><Save :size="14"/>保存</button>
        <button class="console-btn primary" :disabled="busy" @click="run"><Play :size="14"/>运行</button>
      </div>
    </header>

    <div class="flow-editor-body">
      <!-- 编辑时由配置栏接管左栏，避免再挤出一列 -->
      <NodeDrawer v-if="drawerOpen && selectedNode" :node="selectedNode"
                  :sources="sources" :targets="targets" :datasets="datasets"
                  :upstream-columns="upstreamColumns" :second-upstream-columns="secondUpstreamColumns"
                  :upstream-label="upstreamLabel" :second-upstream-label="secondUpstreamLabel"
                  ref="drawerRef" :busy="busy"
                  @apply="applyConfig" @delete="deleteSelected"
                  @dataset-created="reloadDatasets"
                  @need-upstream="loadUpstreamColumns(selectedNode)"
                  @close="drawerOpen = false"/>
      <aside v-else class="flow-palette" aria-label="节点面板">
        <div class="palette-search">
          <Search :size="14" aria-hidden="true"/>
          <input v-model="paletteQuery" type="search" placeholder="搜索操作" aria-label="搜索操作"/>
        </div>
        <template v-for="group in paletteGroups" :key="group.label">
          <div class="palette-group">{{ group.label }}</div>
          <div class="palette-grid">
            <button v-for="option in group.items" :key="option.operator || option.type" class="palette-item"
                    :title="option.label" :aria-label="option.label" draggable="true"
                    @dragstart="startPaletteDrag($event, option)" @dragend="endPaletteDrag">
              <span class="palette-icon" :class="`tone-${option.tone}`">
                <component :is="option.icon" :size="22"/>
              </span>
              <span class="palette-label">{{ option.label }}</span>
            </button>
          </div>
        </template>
        <div v-if="!paletteGroups.length" class="palette-empty">没有匹配的算子</div>
      </aside>

      <div class="flow-stage">
        <FlowCanvas ref="canvas" :graph="graph" :selected-id="selectedId" :dragging="paletteDrag"
                    @node-move="onNodeMove" @select="openDrawer($event)"
                    @open="openDrawer" @connect="onConnect" @delete-node="deleteNode"
                    @select-edge="deselect" @delete-edge="deleteEdge"
                    @deselect="deselect" @drop-node="onDropNode"/>

    <section v-if="selectedNode" class="flow-preview" :class="{ 'is-open': previewOpen }">
      <button type="button" class="flow-preview-bar" @click="togglePreview">
        <component :is="previewOpen ? ChevronDown : ChevronUp" :size="14"/>
        <span>预览</span>
        <span v-if="preview" class="dim">
          {{ preview.rowsRead }} 行 · {{ preview.columns.length }} 列{{ preview.truncated ? '（已截断）' : '' }}
        </span>
        <span v-else class="dim">点击此处预览当前节点的数据</span>
      </button>
      <div v-if="previewOpen" class="flow-preview-body">
        <div v-if="!preview" class="flow-preview-empty">还没有预览数据。</div>
        <template v-else>
          <div class="flow-preview-meta">
            <span>统计信息</span>
            <span>列 {{ preview.columns.length }}</span>
            <span>行 {{ preview.rowsRead }}</span>
            <span v-if="preview.note">{{ preview.note }}</span>
          </div>
          <table class="flow-preview-table">
            <thead><tr><th v-for="column in preview.columns" :key="column">{{ column }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in preview.rows" :key="index">
                <td v-for="(cell, cellIndex) in row" :key="cellIndex" class="mono">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </section>
      </div>
    </div>


  </div>
</template>
