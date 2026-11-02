<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, RefreshCw, Search, Workflow } from 'lucide-vue-next'
import { api } from '../../api'
import { can } from '../../auth'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'

const router = useRouter()
const flows = ref([])
const total = ref(0)
const summary = ref({ total: 0, running: 0, failed: 0, succeeded: 0 })
const page = ref(1)
const pageSize = 20
const q = ref('')
const status = ref('')
// 顶部汇总卡片的筛选维度，与实时同步任务一致
const view = ref('all')
const loading = ref(false)
const error = ref(null)

const isAdmin = computed(() => can('admin'))

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '已启用' }
]

const runLabels = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELED: '已取消' }
const runClasses = { RUNNING: 'status-running', SUCCEEDED: 'status-running', FAILED: 'status-failed', CANCELED: 'status-paused' }

async function load() {
  loading.value = true
  error.value = null
  try {
    const params = new URLSearchParams({ page: String(page.value - 1), size: String(pageSize), view: view.value })
    if (q.value) params.set('q', q.value)
    if (status.value) params.set('status', status.value)
    const body = await api(`/flows?${params}`)
    flows.value = body.items || []
    total.value = body.total || 0
    summary.value = await api('/flows/summary')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function create() {
  router.push('/dev/flows/new')
}

/** 汇总卡片是快捷筛选：选中后清掉状态下拉，避免两个维度互相打架。 */
function selectView(value) {
  view.value = value
  status.value = ''
  page.value = 1
  load()
}

/** 状态下拉是另一个维度，选了它就回到「全部数据流」。 */
function changeStatus() {
  view.value = 'all'
  page.value = 1
  load()
}

async function runFlow(flow) {
  try {
    const result = await api(`/flows/${flow.id}/runs`, { method: 'POST' })
    CdcMessage.success('已提交运行')
    router.push(`/dev/flows/${flow.id}/runs/${result.id}`)
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function remove(flow) {
  try {
    await CdcMessageBox.confirm(
      `确定删除数据流「${flow.name}」？`
      + (flow.lastRunStatus === 'RUNNING' ? '该数据流正在运行，删除会中断本次运行。' : '')
      + '运行历史与节点记录会一并删除，且不可恢复。',
      '删除数据流', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`/flows/${flow.id}`, { method: 'DELETE' })
    CdcMessage.success('数据流已删除')
    // 删掉当前页最后一条时回退一页，否则会停在空列表
    if (flows.value.length === 1 && page.value > 1) page.value -= 1
    else await load()
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

let timer = null
watch(q, () => {
  clearTimeout(timer)
  timer = setTimeout(() => { page.value = 1; load() }, 250)
})
watch(page, load)

onMounted(load)
</script>

<template>
  <div class="page-controls">
    <section class="summary-grid">
      <button class="summary-item task-filter" :class="{ active: view === 'all' }"
              :aria-pressed="view === 'all'" @click="selectView('all')">
        <span>全部数据流</span><strong>{{ summary.total }}</strong>
      </button>
      <button class="summary-item task-filter running" :class="{ active: view === 'running' }"
              :aria-pressed="view === 'running'" @click="selectView('running')">
        <span>运行中</span><strong>{{ summary.running }}</strong>
      </button>
      <button class="summary-item task-filter warning" :class="{ active: view === 'failed' }"
              :aria-pressed="view === 'failed'" @click="selectView('failed')">
        <span>最近失败</span><strong>{{ summary.failed }}</strong>
      </button>
      <button class="summary-item task-filter" :class="{ active: view === 'succeeded' }"
              :aria-pressed="view === 'succeeded'" @click="selectView('succeeded')">
        <span>最近成功</span><strong>{{ summary.succeeded }}</strong>
      </button>
    </section>
    <button v-if="isAdmin" class="console-btn primary" @click="create">
      <Plus :size="16"/>新建数据流
    </button>
  </div>

  <section class="panel">
    <div class="toolbar task-filters">
      <div class="search-box">
        <Search :size="14" class="search-icon"/>
        <input v-model="q" placeholder="搜索数据流名称" aria-label="搜索数据流名称"/>
      </div>
      <cdc-select v-model="status" placeholder="全部状态" @change="changeStatus">
        <cdc-option v-for="option in statusOptions" :key="option.value"
                    :label="option.label" :value="option.value"/>
      </cdc-select>
      <span class="toolbar-spacer"/>
      <button class="console-btn" :disabled="loading" @click="load">
        <RefreshCw :size="14" :class="{ spin: loading }"/>刷新
      </button>
    </div>

    <p v-if="error" class="alert-bar error">{{ error }}</p>

    <div class="table-wrap task-table">
      <table>
        <thead>
          <tr>
            <th>数据流</th><th>节点数</th><th>版本</th><th>最近运行</th>
            <th>处理行数</th><th>最近状态</th><th>更新时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="flow in flows" :key="flow.id">
            <td>
              <a class="task-link">{{ flow.name }}</a>
              <div class="task-desc muted">{{ flow.description || '暂无描述' }}</div>
            </td>
            <td class="mono">{{ flow.nodeCount }}</td>
            <td class="mono">v{{ flow.version }}</td>
            <td class="mono dim">{{ flow.lastRunAt || '从未运行' }}</td>
            <td class="mono">{{ flow.lastRunRows ?? 0 }}</td>
            <td>
              <span v-if="flow.lastRunStatus" class="status-badge" :class="runClasses[flow.lastRunStatus] || 'status-paused'">
                <i/>{{ runLabels[flow.lastRunStatus] || flow.lastRunStatus }}
              </span>
              <span v-else class="dim">—</span>
            </td>
            <td class="mono dim">{{ flow.updatedAt }}</td>
            <td>
              <div class="row-actions">
                <RouterLink class="btn btn-ghost btn-sm" :to="`/dev/flows/${flow.id}`">详情</RouterLink>
                <cdc-menu>
                  <button @click="runFlow(flow)">运行</button>
                  <button v-if="isAdmin" @click="router.push(`/dev/flows/${flow.id}/edit`)">编辑</button>
                  <button v-if="isAdmin" class="danger" @click="remove(flow)">删除</button>
                </cdc-menu>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && !flows.length" class="empty-state">
        <Workflow :size="34"/>
        <p>{{ q || status || view !== 'all' ? '当前筛选下没有数据流' : '还没有数据流' }}</p>
        <button v-if="isAdmin && !q && !status && view === 'all'" class="console-btn primary" @click="create">创建第一个数据流</button>
      </div>
    </div>

    <div class="table-footer">
      <span class="muted">共 {{ total }} 个数据流</span>
      <cdc-pagination :current-page="page" :total="total" :page-size="pageSize"
                      @update:current-page="value => (page = value)"/>
    </div>
  </section>
</template>
