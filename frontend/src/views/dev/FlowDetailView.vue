<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Pencil, Play } from 'lucide-vue-next'
import { api } from '../../api'
import { can } from '../../auth'
import { CdcMessage } from '../../components/ui/feedback'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const flow = ref(null)
const runs = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const busy = ref(false)
const loading = ref(true)
const error = ref(null)

const isAdmin = computed(() => can('admin'))
/** 把 ISO 时间压成 `YYYY-MM-DD HH:mm:ss`：原始串带微秒与时区，在窄栏里会折行。 */
function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

const runLabels = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELED: '已取消' }
const runClasses = { RUNNING: 'status-running', SUCCEEDED: 'status-running', FAILED: 'status-failed', CANCELED: 'status-paused' }

async function load() {
  loading.value = true
  error.value = null
  try {
    flow.value = await api(`/flows/${id}`)
    const body = await api(`/flows/${id}/runs?page=${page.value - 1}&size=${pageSize}`)
    runs.value = body.items || []
    total.value = body.total || 0
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function run() {
  busy.value = true
  try {
    const result = await api(`/flows/${id}/runs`, { method: 'POST' })
    CdcMessage.success('已提交运行')
    router.push(`/dev/flows/${id}/runs/${result.id}`)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="task-detail-page" v-loading="loading">
    <div class="task-detail-heading">
      <div class="crumbs">数据开发 / <span class="cur">{{ flow?.name || '数据流详情' }}</span></div>
      <header class="page-header">
        <div>
          <button class="btn btn-ghost btn-sm" @click="router.push('/dev/flows')">
            <ArrowLeft :size="13"/>返回数据流列表
          </button>
          <h1>{{ flow?.name || '—' }}</h1>
          <p class="muted">{{ flow?.description || '暂无描述' }}</p>
        </div>
        <div class="header-actions">
          <button v-if="isAdmin" class="console-btn" @click="router.push(`/dev/flows/${id}/edit`)">
            <Pencil :size="14"/>编辑
          </button>
          <button class="console-btn primary" :disabled="busy" @click="run">
            <Play :size="14"/>运行
          </button>
        </div>
      </header>

      <p v-if="error" class="alert-bar error">{{ error }}</p>

      <section v-if="flow" class="detail-summary">
        <div class="task-strip">
          <div><span>状态</span><strong>{{ flow.status === 'ACTIVE' ? '已启用' : '草稿' }}</strong></div>
          <div><span>节点数</span><strong class="mono">{{ flow.nodeCount }}</strong></div>
          <div><span>版本</span><strong class="mono">v{{ flow.version }}</strong></div>
          <div><span>最近运行</span><strong class="mono">{{ flow.lastRunAt ? formatTime(flow.lastRunAt) : '从未运行' }}</strong></div>
          <div><span>处理行数</span><strong class="mono">{{ flow.lastRunRows ?? 0 }}</strong></div>
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="section-title"><h2>运行历史</h2></div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>运行 ID</th><th>状态</th><th>开始时间</th><th>耗时</th>
                <th>成功节点</th><th>处理行数</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in runs" :key="item.id" class="clickable"
                @click="router.push(`/dev/flows/${id}/runs/${item.id}`)">
              <td class="mono">#{{ item.id }}</td>
              <td>
                <span class="status-badge" :class="runClasses[item.status] || 'status-paused'">
                  <i/>{{ runLabels[item.status] || item.status }}
                </span>
              </td>
              <td class="mono dim">{{ formatTime(item.startedAt) }}</td>
              <td class="mono">{{ item.durationMs == null ? '—' : `${item.durationMs} ms` }}</td>
              <td class="mono">{{ item.nodeSucceeded }} / {{ item.nodeTotal }}</td>
              <td class="mono">{{ item.rowsWritten }}</td>
              <td class="danger-text">{{ item.message || '' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !runs.length" class="empty-state">
          <p>还没有运行记录</p>
        </div>
      </div>
      <div class="table-footer">
        <span class="muted">共 {{ total }} 次运行</span>
        <cdc-pagination :current-page="page" :total="total" :page-size="pageSize"
                        @update:current-page="value => { page = value; load() }"/>
      </div>
    </section>
  </div>
</template>
