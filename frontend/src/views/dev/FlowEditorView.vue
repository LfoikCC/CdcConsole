<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Play, Save, ShieldCheck, Trash2 } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import FlowCanvas from '../../components/dev/FlowCanvas.vue'
import NodeDrawer from '../../components/dev/NodeDrawer.vue'
import { createNode, PALETTE_GROUPS } from '../../components/dev/nodeTypes'
import { nextNodeId, upstreamOf } from '../../components/dev/flowGraph'

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
const busy = ref(false)
const saving = ref(false)
const loading = ref(false)
const snapshot = ref('')
const canvas = ref(null)
const sources = ref([])
const targets = ref([])

const selectedNode = computed(() => graph.value.nodes.find(node => node.id === selectedId.value) || null)
const dirty = computed(() => JSON.stringify(graph.value) !== snapshot.value)

const runLabels = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELED: '已取消' }

function serialize(value) {
  return JSON.stringify(value)
}

async function load() {
  loading.value = true
  try {
    sources.value = await api('/sources').then(body => body.items || [])
    targets.value = await api('/targets').then(body => body.items || [])
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
  nextTick(() => canvas.value?.fit())
}

/** 点画布空白处：收起抽屉并取消选中。 */
function deselect() {
  selectedId.value = null
  drawerOpen.value = false
  preview.value = null
  upstreamColumns.value = []
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
  const node = graph.value.nodes.find(item => item.id === id)
  if (node?.type === 'OPERATOR') {
    loadUpstreamColumns(node)
  }
}

async function loadUpstreamColumns(node) {
  const upstream = upstreamOf(graph.value, node.id)
  if (!upstream) return
  try {
    const id = await ensureSaved()
    const result = await api(`/flows/${id}/nodes/${upstream.id}/preview`, {
      method: 'POST',
      body: JSON.stringify({ limit: 1 })
    })
    upstreamColumns.value = (result.columns || []).map(label => ({ label }))
  } catch (e) {
    upstreamColumns.value = []
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
      router.replace(`/dev/flows/${created.id}/edit`)
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
  if (!selectedNode.value) return
  busy.value = true
  preview.value = null
  try {
    const id = await ensureSaved()
    preview.value = await api(`/flows/${id}/nodes/${selectedNode.value.id}/preview`, {
      method: 'POST',
      body: JSON.stringify({ limit: 200 })
    })
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    busy.value = false
  }
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
        <template v-for="group in PALETTE_GROUPS" :key="group.label">
          <div class="palette-group">{{ group.label }}</div>
          <button v-for="option in group.items" :key="option.operator || option.type" class="palette-item"
                  @click="addNode(option.type, option.operator)">
            <span class="palette-icon" :class="`tone-${option.tone}`">
              <component :is="option.icon" :size="14"/>
            </span>
            {{ option.label }}
          </button>
        </template>
        <div v-if="selectedId" class="palette-actions">
          <button class="btn btn-ghost btn-sm danger-text" @click="deleteNode(selectedId)">
            <Trash2 :size="13"/>删除选中节点
          </button>
        </div>
      </aside>

      <div class="flow-stage">
        <FlowCanvas ref="canvas" :graph="graph" :selected-id="selectedId"
                    @node-move="onNodeMove" @select="openDrawer($event)"
                    @open="openDrawer" @connect="onConnect" @delete-node="deleteNode"
                    @deselect="deselect"/>
        <NodeDrawer v-if="drawerOpen && selectedNode" :node="selectedNode"
                    :sources="sources" :targets="targets"
                    :upstream-columns="upstreamColumns" :preview="preview" :busy="busy"
                    @apply="applyConfig" @preview="runPreview"
                    @need-upstream="loadUpstreamColumns(selectedNode)"
                    @close="drawerOpen = false"/>
      </div>
    </div>
  </div>
</template>
