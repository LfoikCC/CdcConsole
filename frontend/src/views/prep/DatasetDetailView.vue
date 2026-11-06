<script setup>
/**
 * 数据集详情：概览 / 数据结构 / 数据明细。
 *
 * <p>本地文件（Excel 上传）物化在平台里，可以继续追加或替换数据；
 * 直连库表与 SQL 数据集只读，数据明细取的是实时查询结果。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Archive, ArrowLeft, ChevronDown, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight,
  Download, Pencil, RefreshCw, RotateCcw, Search, Trash2
} from 'lucide-vue-next'
import { api, upload } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { can } from '../../auth'
import DatabaseBrand from '../../components/DatabaseBrand.vue'
import ExcelUploader from '../../components/prep/ExcelUploader.vue'

const route = useRoute()
const router = useRouter()

const id = computed(() => route.params.id)
const detail = ref(null)
const accounts = ref([])
const loading = ref(false)
const tab = ref('schema')
const isAdmin = computed(() => can('admin'))

const TYPE_LABELS = { TABLE: '数据库', SQL: '数据库', EXCEL: '本地文件', EXTRACT: '离线数据集' }
const isLocal = computed(() => detail.value?.datasetType === 'EXCEL')
const isOffline = computed(() => ['EXCEL', 'EXTRACT'].includes(detail.value?.datasetType))

// ---------------------------------------------------------------- 数据结构
const fieldQuery = ref('')
const fields = computed(() => (detail.value?.columns || []).filter(column => {
  const text = fieldQuery.value.trim().toLowerCase()
  if (!text) return true
  return String(column.name || '').toLowerCase().includes(text)
    || String(column.originalName || '').toLowerCase().includes(text)
}))

/** 改展示名：只写平台里的别名，数据源字段（originalName）不动。 */
const editing = reactive({})
const columnBusy = ref(false)
/** 只有直连库表能读到数据源里的字段注释。 */
const canSyncComments = computed(() => detail.value?.datasetType === 'TABLE')

/** 聚焦时记下改之前的值，Esc / 提交失败都能退回去。 */
function beginEdit(column) {
  if (!isAdmin.value || column.ordinal in editing) return
  editing[column.ordinal] = column.name
}

async function commitEdit(column) {
  if (!(column.ordinal in editing)) return
  const before = editing[column.ordinal]
  delete editing[column.ordinal]
  const name = String(column.name ?? '').trim()
  if (!name) {
    column.name = before
    CdcMessage.warning('字段名称不能为空')
    return
  }
  column.name = name
  if (name === before) return
  try {
    await api(`/datasets/${id.value}/columns/${column.ordinal}`, {
      method: 'PUT',
      body: JSON.stringify({ name })
    })
    await load()
    CdcMessage.success('字段名称已更新')
  } catch (e) {
    column.name = before
    CdcMessage.error(e.message)
  }
}

function cancelEdit(column) {
  if (!(column.ordinal in editing)) return
  column.name = editing[column.ordinal]
  delete editing[column.ordinal]
}

/** 用数据源里的字段注释批量替换展示名。 */
async function syncComments() {
  if (!canSyncComments.value || columnBusy.value) return
  try {
    await CdcMessageBox.confirm('将用数据库里的字段注释作为字段名称；没有注释或注释重复的字段保持原样。是否继续？',
      '同步字段注释', { type: 'warning' })
  } catch {
    return
  }
  columnBusy.value = true
  try {
    const body = await api(`/datasets/${id.value}/columns/sync-comments`, { method: 'POST' })
    await load()
    CdcMessage.success(body.updated ? `已同步 ${body.updated} 个字段名称` : '没有可同步的字段注释')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    columnBusy.value = false
  }
}

/** 展示名全部还原成原始字段名。 */
async function resetNames() {
  if (columnBusy.value) return
  try {
    await CdcMessageBox.confirm('把所有字段名称恢复为原始字段名？看板里引用的字段会一起改。',
      '重置字段名称', { type: 'warning' })
  } catch {
    return
  }
  columnBusy.value = true
  try {
    const body = await api(`/datasets/${id.value}/columns/reset`, { method: 'POST' })
    await load()
    CdcMessage.success(body.updated ? `已重置 ${body.updated} 个字段名称` : '字段名称已经是原始字段名')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    columnBusy.value = false
  }
}

// ---------------------------------------------------------------- 数据明细
/** 每页固定 50 行；直连数据集也由后端分页，不再只给前 500 行。 */
const PAGE_SIZE = 50
const table = reactive({ loading: false, columns: [], rows: [], total: 0, page: 0, size: PAGE_SIZE })

const pageCount = computed(() => Math.max(1, Math.ceil(table.total / table.size)))

async function load() {
  loading.value = true
  try {
    detail.value = await api(`/datasets/${id.value}`)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadAccounts() {
  try {
    const body = await api('/accounts?size=200')
    accounts.value = body.items || []
  } catch {
    accounts.value = []
  }
}

async function loadRows() {
  table.loading = true
  try {
    const body = await api(`/datasets/${id.value}/rows?offset=${table.page * table.size}&limit=${table.size}`)
    table.columns = body.columns || []
    table.rows = body.rows || []
    table.total = body.total || 0
    // 替换/追加后行数变少时，当前页可能已经越界，回到最后一页
    const last = Math.max(0, Math.ceil(table.total / table.size) - 1)
    if (table.page > last) {
      table.page = last
      table.loading = false
      await loadRows()
    }
  } catch (e) {
    CdcMessage.error(e.message)
    table.rows = []
  } finally {
    table.loading = false
  }
}

function goPage(page) {
  if (page < 0 || page >= pageCount.value || table.loading) return
  table.page = page
  loadRows()
}

watch(tab, value => {
  if (value === 'rows' && !table.rows.length) loadRows()
})

// ---------------------------------------------------------------- 头部信息
const brand = computed(() => {
  if (!detail.value) return ''
  if (detail.value.datasetType === 'EXCEL') return 'EXCEL'
  const account = accounts.value.find(item => Number(item.id) === Number(detail.value.accountId))
  return account?.accountType || ''
})
const rowText = computed(() => {
  const value = Number(detail.value?.rowCount || 0)
  if (!isOffline.value) return '实时'
  return `${value.toLocaleString('zh-CN')} 行`
})
const sizeText = computed(() => {
  const bytes = Number(detail.value?.file?.sizeBytes || 0)
  if (!bytes) return '—'
  return `${(bytes / 1024 / 1024).toFixed(3)}M`
})
const createdText = computed(() => stamp(detail.value?.createdAt))
const updatedText = computed(() => stamp(detail.value?.updatedAt))

function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function engineName() {
  const account = accounts.value.find(item => Number(item.id) === Number(detail.value?.accountId))
  return account?.accountType ? account.accountType.replace('STARROCKS_STREAM_LOAD', 'StarRocks') : TYPE_LABELS[detail.value?.datasetType]
}

const pathText = computed(() => {
  if (!detail.value) return ''
  if (isLocal.value) return ['根目录', '本地文件', detail.value.file?.fileName || detail.value.name].filter(Boolean).join(' / ')
  if (detail.value.datasetType === 'EXTRACT') return '平台存储'
  return ['根目录', detail.value.databaseName, detail.value.tableName || '自定义 SQL'].filter(Boolean).join(' / ')
})

// ---------------------------------------------------------------- 追加 / 替换
const reload = reactive({
  open: false, mode: 'append', saving: false, parsing: false, inspecting: false, progress: 0,
  token: '', fileName: '', sheets: [], sheetIndex: 0, headerRow: 1, columns: [], rows: 0
})

const reloadTitle = computed(() => (reload.mode === 'replace' ? '替换数据' : '追加数据'))

/** 文件表头与数据集原始字段名的匹配情况，提交前先让用户看清楚。 */
const matched = computed(() => {
  const labels = new Set(reload.columns.map(column => normalize(column.name)))
  const hit = (detail.value?.columns || []).filter(column =>
    labels.has(normalize(column.originalName)) || labels.has(normalize(column.name)))
  return { count: hit.length, total: detail.value?.columns?.length || 0 }
})

function normalize(value) {
  return String(value || '').trim().toLowerCase()
}

function openReload(mode) {
  if (!isLocal.value) return
  Object.assign(reload, {
    open: true, mode, saving: false, parsing: false, inspecting: false, progress: 0,
    token: '', fileName: '', sheets: [], sheetIndex: 0, headerRow: 1, columns: [], rows: 0
  })
}

async function onReloadFile(file) {
  reload.fileName = file.name
  reload.parsing = true
  reload.progress = 0
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await upload('/datasets/excel/parse', data, value => { reload.progress = value })
    reload.token = result.token
    reload.sheets = result.sheets || []
    await selectReloadSheet(0)
  } catch (e) {
    CdcMessage.error(e.message)
    reload.token = ''
    reload.sheets = []
  } finally {
    reload.parsing = false
  }
}

async function selectReloadSheet(index) {
  reload.sheetIndex = index
  reload.headerRow = reload.sheets[index]?.headerRow || 1
  await inspectReload()
}

async function inspectReload() {
  if (!reload.token) return
  reload.inspecting = true
  try {
    const body = await api('/datasets/excel/inspect', {
      method: 'POST',
      body: JSON.stringify({
        token: reload.token, sheetIndex: reload.sheetIndex, headerRow: reload.headerRow
      })
    })
    reload.headerRow = body.headerRow
    reload.columns = body.columns || []
    reload.rows = body.rowCount || 0
  } catch (e) {
    CdcMessage.error(e.message)
    reload.columns = []
  } finally {
    reload.inspecting = false
  }
}

async function submitReload() {
  if (!reload.token) {
    CdcMessage.warning('请先上传文件')
    return
  }
  if (reload.mode === 'replace') {
    try {
      await CdcMessageBox.confirm('替换会用新文件覆盖当前全部数据，字段结构保持不变。是否继续？', '替换数据', { type: 'warning' })
    } catch {
      return
    }
  }
  reload.saving = true
  try {
    const path = reload.mode === 'replace' ? `/datasets/${id.value}/replace` : `/datasets/${id.value}/append`
    const body = await api(path, {
      method: 'POST',
      body: JSON.stringify({
        token: reload.token, fileName: reload.fileName, sheetIndex: reload.sheetIndex, headerRow: reload.headerRow
      })
    })
    CdcMessage.success(reload.mode === 'replace' ? `已替换为 ${body.rows} 行` : `已追加 ${body.added} 行`)
    reload.open = false
    table.page = 0
    await load()
    if (tab.value === 'rows') await loadRows()
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    reload.saving = false
  }
}

// ---------------------------------------------------------------- 编辑 / 刷新 / 删除
const edit = reactive({ open: false, saving: false, name: '', description: '' })

function openEdit() {
  Object.assign(edit, { open: true, saving: false, name: detail.value.name, description: detail.value.description || '' })
}

async function saveEdit() {
  if (!edit.name.trim()) {
    CdcMessage.warning('请填写数据集名称')
    return
  }
  edit.saving = true
  try {
    await api(`/datasets/${id.value}`, {
      method: 'PUT',
      body: JSON.stringify({ name: edit.name.trim(), description: edit.description })
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

async function refreshFields() {
  try {
    await api(`/datasets/${id.value}/refresh`, { method: 'POST' })
    await load()
    CdcMessage.success('字段已刷新')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function remove() {
  try {
    await CdcMessageBox.confirm(`确定删除数据集「${detail.value.name}」？被数据流或看板引用时无法删除。`, '删除数据集', { type: 'warning' })
    await api(`/datasets/${id.value}`, { method: 'DELETE' })
    CdcMessage.success('数据集已删除')
    router.push('/prep/datasets')
  } catch (e) {
    if (e !== 'cancel') CdcMessage.error(e.message)
  }
}

function exportCsv() {
  const columns = table.columns.map(column => column.name)
  const escape = value => {
    if (value == null) return ''
    const text = String(value)
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  const lines = [columns.map(escape).join(',')]
  for (const row of table.rows) lines.push(row.map(escape).join(','))
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${detail.value.name}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  await Promise.all([load(), loadAccounts()])
})
</script>

<template>
  <div v-if="detail" class="dataset-page">
    <div class="dataset-crumb">
      <button class="link-btn" @click="router.push('/prep/datasets')"><ArrowLeft :size="13"/>数据集</button>
      <span>/</span><span class="cur">{{ detail.name }}</span>
    </div>

    <header class="dataset-head">
      <div class="dataset-logo">
        <DatabaseBrand v-if="brand" :type="brand" class="big-brand"/>
        <span v-else class="logo-fallback"><Archive :size="20"/></span>
      </div>
      <div class="dataset-title">
        <h1>{{ detail.name }}</h1>
        <div class="dataset-meta">
          <span>{{ TYPE_LABELS[detail.datasetType] || detail.datasetType }}</span>
          <span v-if="isOffline">{{ rowText }}</span>
          <span>{{ detail.columnCount }} 列</span>
          <span v-if="isLocal && detail.file?.sizeBytes">数据集大小 {{ sizeText }}</span>
          <span>创建时间 {{ createdText }}</span>
          <span>最近数据更新时间 {{ updatedText }}</span>
        </div>
        <p class="dataset-desc">{{ detail.description || '暂无描述' }}</p>
      </div>
      <div class="dataset-actions">
        <cdc-menu v-if="isLocal && isAdmin" label="追加或替换数据">
          <template #trigger>
            <span class="btn btn-primary split-trigger">追加数据<ChevronDown :size="13"/></span>
          </template>
          <button @click="openReload('append')">追加数据</button>
          <button @click="openReload('replace')">替换数据</button>
        </cdc-menu>
        <button v-if="isAdmin && !isOffline" class="btn" @click="refreshFields"><RefreshCw :size="14"/>刷新字段</button>
        <cdc-menu v-if="isAdmin" panel-width="180px">
          <button @click="openEdit"><Pencil :size="13"/>编辑名称与描述</button>
          <button class="danger" @click="remove"><Trash2 :size="13"/>删除数据集</button>
        </cdc-menu>
      </div>
    </header>

    <nav class="tab-list dataset-tabs" role="tablist">
      <button role="tab" :aria-selected="tab === 'overview'" :class="{ active: tab === 'overview' }" @click="tab = 'overview'">概览</button>
      <button role="tab" :aria-selected="tab === 'schema'" :class="{ active: tab === 'schema' }" @click="tab = 'schema'">数据结构</button>
      <button role="tab" :aria-selected="tab === 'rows'" :class="{ active: tab === 'rows' }" @click="tab = 'rows'">数据明细</button>
    </nav>

    <section v-if="tab === 'overview'" class="panel dataset-overview">
      <dl class="overview-grid">
        <div><dt>来源类型</dt><dd>{{ TYPE_LABELS[detail.datasetType] || detail.datasetType }}</dd></div>
        <div><dt>引擎</dt><dd>{{ engineName() }}</dd></div>
        <div><dt>字段数</dt><dd>{{ detail.columnCount }}</dd></div>
        <div><dt>行数</dt><dd>{{ isOffline ? Number(detail.rowCount || 0).toLocaleString('zh-CN') : '实时查询' }}</dd></div>
        <div v-if="isLocal"><dt>文件大小</dt><dd>{{ sizeText }}</dd></div>
        <div v-if="isLocal"><dt>表头行</dt><dd>第 {{ detail.sheet?.headerRow || 1 }} 行</dd></div>
        <div v-if="isLocal"><dt>去重主键</dt><dd>{{ detail.sheet?.dedupeKey || '未设置' }}</dd></div>
        <div class="wide"><dt>数据路径</dt><dd class="mono">{{ pathText }}</dd></div>
      </dl>
    </section>

    <section v-else-if="tab === 'schema'" class="panel">
      <div class="toolbar">
        <div class="search-box">
          <Search :size="14" class="search-icon"/>
          <input v-model="fieldQuery" placeholder="请输入字段名进行搜索" aria-label="搜索字段名"/>
        </div>
        <span class="toolbar-spacer"/>
        <span class="muted">字段名称仅影响系统展示</span>
        <span class="muted">共 {{ detail.columnCount }} 个字段</span>
      </div>
      <div class="table-wrap">
        <table class="field-table">
          <thead>
            <tr>
              <th class="col-idx">#</th>
              <th class="name-head">
                <div class="name-head-inner">
                  <span>字段名称</span>
                  <span v-if="isAdmin" class="name-head-actions">
                    <button type="button" aria-label="同步数据库字段注释" :disabled="!canSyncComments || columnBusy"
                            :title="canSyncComments ? '同步数据库字段注释' : '仅直连库表支持读取字段注释'"
                            @click="syncComments"><RefreshCw :size="12"/></button>
                    <button type="button" aria-label="重置为原始字段名" :disabled="columnBusy"
                            title="重置为原始字段名" @click="resetNames"><RotateCcw :size="12"/></button>
                  </span>
                </div>
              </th>
              <th>原始字段名称</th><th>字段类型</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(column, index) in fields" :key="column.ordinal">
              <td class="col-idx mono">{{ index + 1 }}</td>
              <td class="name-cell">
                <input v-model="column.name" class="field-name-input" :readonly="!isAdmin"
                       :aria-label="`字段名称 ${column.originalName}`"
                       @focus="beginEdit(column)"
                       @blur="commitEdit(column)"
                       @keydown.enter.prevent="$event.target.blur()"
                       @keydown.esc.prevent="cancelEdit(column); $event.target.blur()">
              </td>
              <td class="mono dim">{{ column.originalName }}</td>
              <td class="mono dim">{{ column.dataType }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!fields.length" class="empty-state"><p>没有匹配的字段</p></div>
      </div>
    </section>

    <section v-else class="panel">
      <div class="toolbar">
        <span class="toolbar-spacer"/>
        <button class="btn" :disabled="table.loading" @click="loadRows"><RefreshCw :size="13" :class="{ spin: table.loading }"/>刷新</button>
        <button class="btn" :disabled="!table.rows.length" @click="exportCsv"><Download :size="13"/>导出当前页 CSV</button>
      </div>
      <div v-if="table.loading && !table.rows.length" class="loading-line">正在读取数据…</div>
      <div v-else class="table-wrap rows-table" :class="{ 'is-loading': table.loading }">
        <table>
          <thead>
            <tr><th class="col-idx">#</th><th v-for="column in table.columns" :key="column.name">{{ column.name }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in table.rows" :key="index">
              <td class="col-idx mono">{{ table.page * table.size + index + 1 }}</td>
              <td v-for="(value, cell) in row" :key="cell" class="mono">{{ value == null ? 'NULL' : value }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!table.rows.length" class="empty-state"><p>没有数据</p></div>
      </div>
      <div class="table-footer rows-footer">
        <span class="muted">共 {{ table.total.toLocaleString('zh-CN') }} 条 · 每页 {{ table.size }} 行</span>
        <div class="pager">
          <button class="btn btn-sm" :disabled="table.page === 0 || table.loading" aria-label="第一页" @click="goPage(0)">
            <ChevronsLeft :size="14"/>
          </button>
          <button class="btn btn-sm" :disabled="table.page === 0 || table.loading" aria-label="上一页" @click="goPage(table.page - 1)">
            <ChevronLeft :size="14"/>
          </button>
          <span class="mono">{{ table.page + 1 }} / {{ pageCount }}</span>
          <button class="btn btn-sm" :disabled="table.page + 1 >= pageCount || table.loading" aria-label="下一页" @click="goPage(table.page + 1)">
            <ChevronRight :size="14"/>
          </button>
          <button class="btn btn-sm" :disabled="table.page + 1 >= pageCount || table.loading" aria-label="最后一页" @click="goPage(pageCount - 1)">
            <ChevronsRight :size="14"/>
          </button>
        </div>
      </div>
    </section>

    <cdc-modal v-model="reload.open" :title="reloadTitle" width="min(620px,94vw)">
      <div class="reload-body">
        <ExcelUploader :file-name="reload.fileName" @file="onReloadFile"/>
        <div v-if="reload.parsing" class="loading-line">正在解析文件… {{ reload.progress }}%</div>
        <template v-if="reload.sheets.length">
          <div class="reload-config">
            <cdc-form-item label="Sheet">
              <cdc-select :model-value="reload.sheetIndex" style="width:100%"
                          @update:model-value="value => selectReloadSheet(value)">
                <cdc-option v-for="sheet in reload.sheets" :key="sheet.index"
                            :label="`${sheet.name}（${sheet.rowCount} 行）`" :value="sheet.index"/>
              </cdc-select>
            </cdc-form-item>
            <cdc-form-item label="表头行">
              <cdc-input-number v-model="reload.headerRow" :min="1" style="width:100%" @change="inspectReload"/>
            </cdc-form-item>
          </div>
          <div v-if="reload.inspecting" class="loading-line">正在读取字段…</div>
          <div v-else class="reload-hint">
            文件共 {{ reload.rows }} 行，字段匹配 {{ matched.count }} / {{ matched.total }}。
            <span v-if="matched.count < matched.total" class="warn">未匹配的字段会留空</span>
          </div>
        </template>
      </div>
      <template #footer>
        <button class="btn btn-ghost" @click="reload.open = false">取消</button>
        <span style="flex:1"/>
        <button class="btn" :class="reload.mode === 'replace' ? 'btn-danger' : 'btn-primary'"
                :disabled="!reload.token || reload.saving" @click="submitReload">
          {{ reload.mode === 'replace' ? '确认替换' : '确认追加' }}
        </button>
      </template>
    </cdc-modal>

    <cdc-modal v-model="edit.open" title="编辑数据集" width="min(520px,94vw)">
      <cdc-form label-position="top">
        <cdc-form-item label="数据集名称" required><cdc-input v-model="edit.name" maxlength="160"/></cdc-form-item>
        <cdc-form-item label="描述"><cdc-input v-model="edit.description" type="textarea" :rows="3" maxlength="1000"/></cdc-form-item>
      </cdc-form>
      <template #footer>
        <button class="btn btn-ghost" @click="edit.open = false">取消</button>
        <span style="flex:1"/>
        <button class="btn btn-primary" :disabled="edit.saving" @click="saveEdit">保存</button>
      </template>
    </cdc-modal>
  </div>
  <div v-else-if="loading" class="loading-line">正在读取数据集…</div>
</template>

<style scoped>
.dataset-page { display: flex; flex-direction: column; gap: 14px; }
.dataset-crumb { display: flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--muted-fg); }
.dataset-crumb .cur { color: var(--fg); }
.link-btn { display: inline-flex; align-items: center; gap: 4px; color: var(--muted-fg); font-size: 11.5px; }
.link-btn:hover { color: var(--primary); }
.dataset-head { display: flex; align-items: flex-start; gap: 12px; padding: 4px 0 2px; }
.dataset-logo { flex: 0 0 auto; padding-top: 2px; }
.big-brand :deep(.brand-icon), .big-brand :deep(.brand-icon img) { width: 34px; height: 34px; flex-basis: 34px; }
.logo-fallback {
  display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px;
  border-radius: 8px; background: var(--muted); color: var(--muted-fg);
}
.dataset-title { flex: 1; min-width: 0; }
.dataset-title h1 { margin: 0 0 6px; font-size: 17px; line-height: 1.3; }
.dataset-meta { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 11.5px; color: var(--muted-fg); }
.dataset-desc { margin: 6px 0 0; font-size: 12px; color: var(--muted-fg); }
.dataset-actions { display: flex; align-items: center; gap: 8px; }
/* 菜单默认是 28×28 的图标按钮，这里换成带文字的按钮 */
.dataset-actions :deep(.menu-trigger) { width: auto; height: auto; padding: 0; background: transparent; color: inherit; }
.dataset-actions :deep(.menu-trigger:hover) { background: transparent; }
.split-trigger { display: inline-flex; align-items: center; gap: 4px; }
.dataset-tabs { margin-top: 2px; }
.overview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px 18px; margin: 0; }
.overview-grid div.wide { grid-column: 1 / -1; }
.overview-grid dt { font-size: 11.5px; color: var(--muted-fg); margin-bottom: 4px; }
.overview-grid dd { margin: 0; font-size: 13px; }
.field-table .col-idx, .rows-table .col-idx { width: 48px; text-align: right; color: var(--muted-fg); }
.field-table td { height: 34px; padding: 4px 12px; }
.name-cell { white-space: nowrap; }
/* 字段名平时看着就是文本：鼠标移上去出现输入框，点进去直接改 */
.field-name-input {
  width: 100%; height: 26px; padding: 0 8px; font: 11.5px var(--mono); color: var(--fg);
  background: transparent; border: 1px solid transparent; border-radius: 4px;
}
.field-name-input:hover:not([readonly]) { border-color: var(--border); background: var(--card); }
/* hover 的选择器比 :focus 更具体，这里要带上 :hover 才能压回蓝色边框 */
.field-name-input:focus, .field-name-input:hover:focus {
  outline: none; border-color: var(--primary); background: var(--card);
  box-shadow: 0 0 0 2px var(--brand-subtle);
}
.field-name-input[readonly] { cursor: default; }
.name-head-inner { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.name-head-actions { display: inline-flex; gap: 4px; }
.name-head-actions button {
  display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px;
  padding: 0; border: 1px solid var(--border); border-radius: 4px; cursor: pointer;
  background: var(--card); color: var(--muted-fg);
}
.name-head-actions button:hover:not(:disabled) { color: var(--primary); border-color: var(--primary); }
.name-head-actions button:disabled { opacity: .4; cursor: not-allowed; }
.rows-table { max-height: 56vh; }
.rows-table.is-loading { opacity: .55; transition: opacity .15s; }
.rows-footer { padding: 10px 0 2px; }
.reload-body { display: flex; flex-direction: column; gap: 12px; }
.reload-config { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.reload-hint { font-size: 12px; color: var(--muted-fg); }
.reload-hint .warn { color: var(--warning); }
@media (max-width: 900px) {
  .dataset-head { flex-wrap: wrap; }
  .overview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .reload-config { grid-template-columns: 1fr; }
}
</style>
