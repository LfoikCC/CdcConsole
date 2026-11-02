<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'

const route = useRoute()
const router = useRouter()
const flowId = Number(route.params.id)
const runId = Number(route.params.runId)

const run = ref(null)
const nodes = ref([])
const loading = ref(true)
const error = ref(null)
const canceling = ref(false)
let timer = null

/** 把 ISO 时间压成 `YYYY-MM-DD HH:mm:ss`：原始串带微秒与时区，在窄栏里会折行。 */
function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

const labels = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELED: '已取消', PENDING: '等待中' }
const classes = { RUNNING: 'status-running', SUCCEEDED: 'status-running', FAILED: 'status-failed', CANCELED: 'status-paused', PENDING: 'status-starting' }

const isRunning = computed(() => run.value?.status === 'RUNNING' || run.value?.status === 'PENDING')

async function load() {
  try {
    const body = await api(`/flows/${flowId}/runs/${runId}`)
    run.value = body.run
    nodes.value = body.nodes || []
    error.value = null
    if (!isRunning.value && timer) {
      clearInterval(timer)
      timer = null
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  // 只在运行中轮询：跑完就停，避免无意义的请求
  if (isRunning.value) timer = setInterval(load, 1000)
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })

async function cancelRun() {
  try {
    await CdcMessageBox.confirm('取消会在当前批次结束后停止，已写入目标的数据不会回滚。确定取消？', '取消运行', { type: 'warning' })
  } catch {
    return
  }
  canceling.value = true
  try {
    await api(`/flows/${flowId}/runs/${runId}/cancel`, { method: 'POST' })
    CdcMessage.success('已发送取消请求')
    await load()
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    canceling.value = false
  }
}
</script>

<template>
  <div class="task-detail-page" v-loading="loading">
    <div class="task-detail-heading">
      <div class="crumbs">数据开发 / 运行 #{{ runId }}</div>
      <header class="page-header">
        <div>
          <button class="btn btn-ghost btn-sm" @click="router.push(`/dev/flows/${flowId}`)">
            <ArrowLeft :size="13"/>返回数据流
          </button>
          <h1>运行 #{{ runId }}</h1>
          <p class="muted">
            <span v-if="run" class="status-badge" :class="classes[run.status] || 'status-paused'">
              <i/>{{ labels[run.status] || run.status }}
            </span>
            <span v-if="isRunning" class="dim">（每秒刷新）</span>
          </p>
        </div>
        <div v-if="isRunning" class="header-actions">
          <cdc-button type="danger" :loading="canceling" @click="cancelRun">取消运行</cdc-button>
        </div>
      </header>

      <p v-if="error" class="alert-bar error">{{ error }}</p>

      <section v-if="run" class="detail-summary">
        <div class="task-strip">
          <div><span>开始时间</span><strong class="mono">{{ formatTime(run.startedAt) }}</strong></div>
          <div><span>耗时</span><strong class="mono">{{ run.durationMs == null ? '—' : `${run.durationMs} ms` }}</strong></div>
          <div><span>成功节点</span><strong class="mono">{{ run.nodeSucceeded }} / {{ run.nodeTotal }}</strong></div>
          <div><span>处理行数</span><strong class="mono">{{ run.rowsWritten }}</strong></div>
          <div><span>触发方式</span><strong>{{ run.triggerType }}</strong></div>
        </div>
      </section>
    </div>

    <section v-if="run?.message" class="panel">
      <p class="alert-bar error">{{ run.message }}</p>
    </section>

    <section class="panel">
      <div class="section-title"><h2>节点执行</h2></div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>节点</th><th>类型</th><th>状态</th><th>读取</th><th>写入</th><th>耗时</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="node in nodes" :key="node.id">
              <td><strong>{{ node.nodeName || node.nodeKey }}</strong><div class="mono dim">{{ node.nodeKey }}</div></td>
              <td class="mono">{{ node.nodeType }}</td>
              <td>
                <span class="status-badge" :class="classes[node.status] || 'status-paused'">
                  <i/>{{ labels[node.status] || node.status }}
                </span>
              </td>
              <td class="mono">{{ node.rowsRead }}</td>
              <td class="mono">{{ node.rowsWritten }}</td>
              <td class="mono">{{ node.durationMs == null ? '—' : `${node.durationMs} ms` }}</td>
              <td class="danger-text">{{ node.message || '' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !nodes.length" class="empty-state"><p>还没有节点记录</p></div>
      </div>
    </section>
  </div>
</template>
