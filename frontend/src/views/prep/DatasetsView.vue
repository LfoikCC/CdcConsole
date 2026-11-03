<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, CirclePlus, Code2, Database, FileSpreadsheet, RefreshCw, Search, Table2 } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { can } from '../../auth'

const router = useRouter()
const datasets = ref([])
const loading = ref(false)
const q = ref('')
const type = ref('')
const preview = reactive({ open: false, loading: false, title: '', columns: [], rows: [], truncated: false, elapsedMs: 0 })
const edit = reactive({
  open: false, loading: false, saving: false, id: null, name: '', description: '',
  type: '', tableName: '', sqlText: '', originalTable: '', originalSql: ''
})
const isAdmin = computed(() => can('admin'))

const TYPE_LABELS = { TABLE: '数据库', SQL: '数据库', EXCEL: 'Excel', EXTRACT: '离线' }
const TYPE_ICONS = { TABLE: Table2, SQL: Code2, EXCEL: FileSpreadsheet, EXTRACT: Archive }
const TYPE_TONES = { TABLE: 'running', SQL: 'warning', EXCEL: '', EXTRACT: 'running' }

const filtered = computed(() => datasets.value.filter(item => {
  const matchType = !type.value
    || (type.value === 'DB' ? ['TABLE', 'SQL'].includes(item.datasetType) : item.datasetType === type.value)
  const matchText = !q.value || item.name.toLowerCase().includes(q.value.trim().toLowerCase())
  return matchType && matchText
}))
const counts = computed(() => ({
  all: datasets.value.length,
  DB: datasets.value.filter(item => ['TABLE', 'SQL'].includes(item.datasetType)).length,
  TABLE: datasets.value.filter(item => item.datasetType === 'TABLE').length,
  SQL: datasets.value.filter(item => item.datasetType === 'SQL').length,
  EXCEL: datasets.value.filter(item => item.datasetType === 'EXCEL').length,
  EXTRACT: datasets.value.filter(item => item.datasetType === 'EXTRACT').length
}))

/**
 * 一次取全量，类型和关键字都在前端筛。
 *
 * <p>以前把 type 传给后端，回来的是过滤后的子集，四个页签的计数就都跟着变成子集的大小——
 * 点「数据库」之后「全部」也显示 0，看着像数据没了。数据集数量有限（上限 500），本地筛更稳也更快。
 */
async function load() {
  loading.value = true
  try {
    const body = await api('/datasets?size=500')
    datasets.value = body.items || []
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function selectType(value) {
  type.value = value
}

function source(item) {
  if (item.datasetType === 'TABLE') return `${item.databaseName || ''}.${item.tableName || ''}`.replace(/^\./, '')
  if (item.datasetType === 'SQL') return '自定义查询'
  if (item.datasetType === 'EXTRACT') return '平台存储'
  return 'Excel 文件'
}

async function openPreview(item) {
  preview.open = true
  preview.loading = true
  preview.title = item.name
  preview.columns = []
  preview.rows = []
  try {
    const data = await api(`/datasets/${item.id}/preview?limit=200`)
    preview.columns = data.columns || []
    preview.rows = data.rows || []
    preview.truncated = data.truncated
    preview.elapsedMs = data.elapsedMs
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    preview.loading = false
  }
}

async function openEdit(item) {
  Object.assign(edit, {
    open: true, loading: true, saving: false, id: item.id, name: item.name, description: item.description || '',
    type: item.datasetType, tableName: item.tableName || '', sqlText: '', originalTable: item.tableName || '', originalSql: ''
  })
  try {
    const detail = await api(`/datasets/${item.id}`)
    edit.name = detail.name
    edit.description = detail.description || ''
    edit.tableName = detail.tableName || ''
    edit.sqlText = detail.sqlText || ''
    edit.originalTable = detail.tableName || ''
    edit.originalSql = detail.sqlText || ''
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    edit.loading = false
  }
}

async function saveEdit() {
  if (!edit.name.trim()) {
    CdcMessage.warning('请填写数据集名称')
    return
  }
  const sourceChanged = edit.type === 'TABLE'
    ? edit.tableName.trim() !== edit.originalTable
    : edit.type === 'SQL' && edit.sqlText.trim() !== edit.originalSql
  if (sourceChanged) {
    try {
      await CdcMessageBox.confirm('修改数据源会重新读取字段，引用该数据集的数据流可能需要重新绑定。是否继续？', '修改数据源', { type: 'warning' })
    } catch {
      return
    }
  }
  edit.saving = true
  try {
    await api(`/datasets/${edit.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name: edit.name,
        description: edit.description,
        tableName: edit.type === 'TABLE' ? edit.tableName : null,
        sqlText: edit.type === 'SQL' ? edit.sqlText : null,
        rebind: sourceChanged
      })
    })
    edit.open = false
    await load()
    CdcMessage.success('数据集已更新')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    edit.saving = false
  }
}

async function refresh(item) {  try {
    await api(`/datasets/${item.id}/refresh`, { method: 'POST' })
    await load()
    CdcMessage.success('字段已刷新')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function remove(item) {
  try {
    await CdcMessageBox.confirm(`确定删除数据集「${item.name}」？被数据流引用时无法删除。`, '删除数据集', { type: 'warning' })
    await api(`/datasets/${item.id}`, { method: 'DELETE' })
    await load()
    CdcMessage.success('数据集已删除')
  } catch (e) {
    if (e !== 'cancel') CdcMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <header class="page-header">
    <div>
      <h1>数据集</h1>
      <p>数据流与后续分析的输入输出。支持直连库表、自定义 SQL、Excel 文件与离线数据集。</p>
    </div>
    <div class="header-actions">
      <button v-if="isAdmin" class="console-btn primary" @click="router.push('/prep/datasets/new')">
        <CirclePlus :size="16"/>新建数据集
      </button>
    </div>
  </header>

  <div class="page-controls">
    <section class="summary-grid">
      <button class="summary-item task-filter" :class="{ active: !type }" :aria-pressed="!type" @click="selectType('')">
        <span>全部数据集</span><strong>{{ counts.all }}</strong>
      </button>
      <button class="summary-item task-filter" :class="{ active: type === 'DB' }" :aria-pressed="type === 'DB'" @click="selectType('DB')">
        <span>数据库</span><strong>{{ counts.DB }}</strong>
      </button>
      <button class="summary-item task-filter" :class="{ active: type === 'EXCEL' }" :aria-pressed="type === 'EXCEL'" @click="selectType('EXCEL')">
        <span>Excel 文件</span><strong>{{ counts.EXCEL }}</strong>
      </button>
      <button class="summary-item task-filter" :class="{ active: type === 'EXTRACT' }" :aria-pressed="type === 'EXTRACT'" @click="selectType('EXTRACT')">
        <span>离线数据集</span><strong>{{ counts.EXTRACT }}</strong>
      </button>
    </section>
  </div>

  <section class="panel">
    <div class="toolbar task-filters">
      <div class="search-box">
        <Search :size="14" class="search-icon"/>
        <input v-model="q" placeholder="搜索数据集名称" aria-label="搜索数据集名称"/>
      </div>
      <span class="toolbar-spacer"/>
      <button class="console-btn" :disabled="loading" @click="load"><RefreshCw :size="14" :class="{ spin: loading }"/>刷新</button>
    </div>

    <div class="table-wrap task-table">
      <table>
        <thead>
          <tr><th>数据集</th><th>类型</th><th>来源</th><th>字段数</th><th>行数</th><th>更新时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in filtered" :key="item.id">
            <td>
              <a class="task-link" @click="openPreview(item)">{{ item.name }}</a>
              <div class="task-desc muted">{{ item.description || '暂无描述' }}</div>
            </td>
            <td>
              <span class="status-badge" :class="'status-' + (TYPE_TONES[item.datasetType] || 'paused')">
                <component :is="TYPE_ICONS[item.datasetType]" :size="12"/>{{ TYPE_LABELS[item.datasetType] || item.datasetType }}
              </span>
            </td>
            <td class="mono dim">{{ source(item) }}</td>
            <td class="mono">{{ item.columnCount }}</td>
            <td class="mono">{{ ['EXCEL', 'EXTRACT'].includes(item.datasetType) ? item.rowCount : '实时' }}</td>
            <td class="mono dim">{{ String(item.updatedAt || '').replace('T', ' ').slice(0, 19) }}</td>
            <td>
              <div class="row-actions">
                <button class="btn btn-ghost btn-sm" @click="openPreview(item)">预览</button>
                <cdc-menu>
                  <button @click="openPreview(item)">查看数据</button>
                  <button v-if="isAdmin" @click="openEdit(item)">编辑</button>
                  <button v-if="isAdmin && !['EXCEL', 'EXTRACT'].includes(item.datasetType)" @click="refresh(item)">刷新字段</button>
                  <button v-if="isAdmin" class="danger" @click="remove(item)">删除</button>
                </cdc-menu>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && !filtered.length" class="empty-state">
        <Database :size="34"/>
        <p>{{ q || type ? '当前筛选下没有数据集' : '还没有数据集' }}</p>
        <button v-if="isAdmin && !q && !type" class="console-btn primary" @click="router.push('/prep/datasets/new')">创建第一个数据集</button>
      </div>
    </div>
    <div class="table-footer"><span class="muted">共 {{ filtered.length }} 个数据集</span></div>
  </section>

  <cdc-modal v-model="preview.open" :title="'预览 · ' + preview.title" width="min(1000px,96vw)">
    <div v-if="preview.loading" class="loading-line">正在读取数据…</div>
    <div v-else class="table-wrap preview-table">
      <table>
        <thead><tr><th v-for="column in preview.columns" :key="column">{{ column }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in preview.rows" :key="index">
            <td v-for="(value, cell) in row" :key="cell" class="mono">{{ value == null ? 'NULL' : value }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!preview.rows.length" class="empty-state"><p>没有数据</p></div>
    </div>
    <template #footer>
      <span class="muted preview-meta">共 {{ preview.rows.length }} 行{{ preview.truncated ? '（已截断）' : '' }} · {{ preview.elapsedMs }} ms</span>
      <cdc-button @click="preview.open = false">关闭</cdc-button>
    </template>
  </cdc-modal>

  <cdc-modal v-model="edit.open" title="编辑数据集" width="min(560px,94vw)">
    <div v-if="edit.loading" class="loading-line">正在读取数据集…</div>
    <cdc-form v-else label-position="top">
      <cdc-form-item label="数据集名称" required>
        <cdc-input v-model="edit.name" maxlength="160"/>
      </cdc-form-item>
      <cdc-form-item label="描述">
        <cdc-input v-model="edit.description" type="textarea" :rows="3" maxlength="1000"/>
      </cdc-form-item>
      <cdc-form-item v-if="edit.type === 'TABLE'" label="数据表">
        <cdc-input v-model="edit.tableName" placeholder="orders"/>
        <div class="form-help">改表会重新读取字段；被数据流引用时需二次确认。</div>
      </cdc-form-item>
      <cdc-form-item v-if="edit.type === 'SQL'" label="查询 SQL">
        <textarea v-model="edit.sqlText" class="dataset-sql" rows="8" placeholder="SELECT ..."/>
        <div class="form-help">改 SQL 会重新推导字段；被数据流引用时需二次确认。</div>
      </cdc-form-item>
      <div v-if="edit.type === 'EXCEL'" class="form-help">Excel 数据集只能修改名称和描述，数据源需重新上传文件。</div>
      <div v-if="edit.type === 'EXTRACT'" class="form-help">离线数据集只能修改名称和描述，数据由写入它的数据流重新生成。</div>
    </cdc-form>
    <template #footer>
      <cdc-button @click="edit.open = false">取消</cdc-button>
      <cdc-button type="primary" :loading="edit.saving" :disabled="edit.loading" @click="saveEdit">保存</cdc-button>
    </template>
  </cdc-modal>
</template>

<style scoped>
.dataset-sql {
  width: 100%; font: 12px var(--mono); padding: 10px; border: 1px solid var(--border);
  border-radius: 6px; background: var(--card); color: var(--fg); resize: vertical;
}
.preview-table { max-height: 60vh; overflow: auto; }
.preview-meta { margin-right: auto; }
</style>
