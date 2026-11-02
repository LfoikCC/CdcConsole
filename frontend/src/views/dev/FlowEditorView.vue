<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Play, Save, Search, ShieldCheck } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import FlowCanvas from '../../components/dev/FlowCanvas.vue'
import NodeDrawer from '../../components/dev/NodeDrawer.vue'
import { createNode, PALETTE_GROUPS } from '../../components/dev/nodeTypes'
import { nextNodeId, upstreamNodesOf } from '../../components/dev/flowGraph'

const route = useRoute()
const router = useRouter()

const flowId = ref(route.params.id ? Number(route.params.id) : null)
const name = ref('未命名数据流')
const description = ref('')
const graph = ref({ nodes: [], edges: [] })
const selectedId = ref(null)
const drawerOpen = ref(false)
const preview = ref(null)
const upstreamColumns = ref([])
const secondUpstreamColumns = ref([])
const busy = ref(false)
const saving = ref(false)
const loading = ref(false)
const snapshot = ref('')
const canvas = ref(null)
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

function addNode(type, operator) {
  const id = nextNodeId(graph.value.nodes)
  const offset = graph.value.nodes.length * 32
  const node = createNode(type, id, 120 + offset, 120 + offset, operator)
  graph.value.nodes.push(node)
  selectedId.value = id
  drawerOpen.value = true
  preview.value = null
  upstreamColumns.value = []
  secondUpstreamColumns.value = []
  nextTick(() => canvas.value?.fit())
}

/** 点画布空白处：收起抽屉并取消选中。 */
function deselect() {
  selectedId.value = null
  drawerOpen.value = false
  preview.value = null
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

function deleteNode(id) {
  graph.value.nodes = graph.value.nodes.filter(node => node.id !== id)
  graph.value.edges = graph.value.edges.filter(edge => edge.from !== id && edge.to !== id)
  if (selectedId.value === id) {
    selectedId.value = null
    drawerOpen.value = false
  }
}

function openDrawer(id) {
  selectedId.value = id
  drawerOpen.value = true
  preview.value = null
  upstreamColumns.value = []
  secondUpstreamColumns.value = []
  const node = graph.value.nodes.find(item => item.id === id)
  if (node?.type === 'OPERATOR') {
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
    const body = JSON.stringify({ name: name.value.trim(), description: description.value, graph: graph.value })
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

async function runPreview(draft) {
  const node = selectedNode.value
  if (!node) return
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
      <aside class="flow-palette" aria-label="节点面板">
        <div class="palette-search">
          <Search :size="14" aria-hidden="true"/>
          <input v-model="paletteQuery" type="search" placeholder="搜索操作" aria-label="搜索操作"/>
        </div>
        <template v-for="group in paletteGroups" :key="group.label">
          <div class="palette-group">{{ group.label }}</div>
          <div class="palette-grid">
            <button v-for="option in group.items" :key="option.operator || option.type" class="palette-item"
                    :title="option.label" :aria-label="option.label"
                    @click="addNode(option.type, option.operator)">
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
        <FlowCanvas ref="canvas" :graph="graph" :selected-id="selectedId"
                    @node-move="onNodeMove" @select="openDrawer($event)"
                    @open="openDrawer" @connect="onConnect" @delete-node="deleteNode"
                    @deselect="deselect"/>
        <NodeDrawer v-if="drawerOpen && selectedNode" :node="selectedNode"
                    :sources="sources" :targets="targets" :datasets="datasets"
                    :upstream-columns="upstreamColumns" :second-upstream-columns="secondUpstreamColumns"
                    :preview="preview" :busy="busy"
                    @apply="applyConfig" @preview="runPreview" @delete="deleteSelected"
                    @dataset-created="reloadDatasets"
                    @need-upstream="loadUpstreamColumns(selectedNode)"
                    @close="drawerOpen = false"/>
      </div>
    </div>
  </div>
</template>
