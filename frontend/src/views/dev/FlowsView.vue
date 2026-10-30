<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, RefreshCw, Search, Workflow } from 'lucide-vue-next'
import { api } from '../../api'
import { can } from '../../auth'
import { CdcMessage } from '../../components/ui/feedback'

const router = useRouter()
const flows = ref([])
const total = ref(0)
const summary = ref({ total: 0, running: 0, failed: 0, succeeded: 0 })
const page = ref(1)
const pageSize = 20
const q = ref('')
const status = ref('')
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
    const params = new URLSearchParams({ page: String(page.value - 1), size: String(pageSize) })
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

function open(flow) {
  router.push(`/dev/flows/${flow.id}`)
}

function create() {
  router.push('/dev/flows/new')
}

let timer = null
watch(q, () => {
  clearTimeout(timer)
  timer = setTimeout(() => { page.value = 1; load() }, 250)
})
watch(status, () => { page.value = 1; load() })
watch(page, load)

onMounted(load)
</script>

<template>
  <div class="page-controls">
    <section class="summary-grid">
      <button class="summary-item active" :class="{ 'is-active': !status }" @click="status = ''">
        <span>全部数据流</span><strong>{{ summary.total }}</strong>
      </button>
      <button class="summary-item running" @click="status = 'ACTIVE'">
        <span>运行中</span><strong>{{ summary.running }}</strong>
      </button>
      <button class="summary-item warning" @click="status = 'ACTIVE'">
        <span>最近失败</span><strong>{{ summary.failed }}</strong>
      </button>
      <button class="summary-item" @click="status = 'ACTIVE'">
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
      <cdc-select v-model="status" placeholder="全部状态">
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
            <th>处理行数</th><th>最近状态</th><th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="flow in flows" :key="flow.id" class="clickable" @click="open(flow)">
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
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && !flows.length" class="empty-state">
        <Workflow :size="34"/>
        <p>{{ q || status ? '当前筛选下没有数据流' : '还没有数据流' }}</p>
        <button v-if="isAdmin && !q && !status" class="console-btn primary" @click="create">创建第一个数据流</button>
      </div>
    </div>

    <div class="table-footer">
      <span class="muted">共 {{ total }} 个数据流</span>
      <cdc-pagination :current-page="page" :total="total" :page-size="pageSize"
                      @update:current-page="value => (page = value)"/>
    </div>
  </section>
</template>
