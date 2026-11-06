<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Database, FileSpreadsheet } from 'lucide-vue-next'
import { api, upload } from '../../api'
import { CdcMessage } from '../../components/ui/feedback'
import DatabaseBrand from '../../components/DatabaseBrand.vue'
import ExcelUploader from '../../components/prep/ExcelUploader.vue'

const router = useRouter()
const step = ref(1)
const source = ref('DB')      // DB | EXCEL
const dbMode = ref('TABLE')   // TABLE | SQL：同一个「数据库」来源下的两种取数方式
// type 仍要区分 TABLE/SQL 落库，但对用户来说只有一个「数据库」入口
const type = computed(() => (source.value === 'EXCEL' ? 'EXCEL' : dbMode.value))
const saving = ref(false)
const accounts = ref([])
const tables = ref([])
const loadingTables = ref(false)

const form = reactive({
  accountId: null, databaseName: '', tableName: '', sqlText: '', name: '', description: ''
})
const excel = reactive({
  file: null, fileName: '', token: '', progress: 0, parsing: false, inspecting: false,
  sheets: [], sheetIndex: 0, headerRow: 1, columns: [], sample: [], rowCount: 0,
  rangeMode: 'auto', startColumn: 0, endColumn: null, maxColumns: 0, dedupeKey: ''
})

const TYPES = [
  { value: 'BIGINT', label: 'BIGINT' },
  { value: 'DECIMAL(38,10)', label: 'DECIMAL(38,10)' },
  { value: 'DOUBLE', label: 'DOUBLE' },
  { value: 'VARCHAR', label: 'VARCHAR' },
  { value: 'TIMESTAMP', label: 'TIMESTAMP' },
  { value: 'BOOLEAN', label: 'BOOLEAN' }
]
const SOURCES = [
  { value: 'DB', title: '数据库', desc: '选一张表或写一条 SELECT，两种方式随时切换', icon: Database },
  { value: 'EXCEL', title: '本地文件', desc: '上传 Excel 后物化到平台，可设置表头、列范围与字段类型', icon: FileSpreadsheet }
]

/** 列号用 A、B、C 表示，和表格软件里的叫法一致。 */
const columnLabel = index => {
  let value = index + 1
  let label = ''
  while (value > 0) {
    const rest = (value - 1) % 26
    label = String.fromCharCode(65 + rest) + label
    value = Math.floor((value - 1) / 26)
  }
  return label
}
const columnChoices = computed(() => {
  const count = Math.max(excel.maxColumns, excel.columns.length, 1)
  return Array.from({ length: count }, (_, index) => ({ value: index, label: columnLabel(index) }))
})

/**
 * 字段表格的宽度。
 *
 * <p>133 列挤在 100% 宽度里会被压成一条条细缝，表头和样例数据全糊在一起；
 * 所以表格按「列数 × 固定列宽」撑开，横向滚动交给外层的 field-grid-wrap。
 */
const FIELD_WIDTH = 150
const gridWidth = computed(() => `${42 + Math.max(excel.columns.length, 1) * FIELD_WIDTH}px`)

async function loadAccounts() {
  try {
    const body = await api('/accounts?size=500')
    accounts.value = (body.items || []).filter(item => (item.capabilities || []).includes('QUERY') && item.enabled)
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function accountChanged() {
  const account = accounts.value.find(item => item.id === form.accountId)
  // 数据库跟着账户走，这里只是把账户上的库名记下来，向导里不再单独选库
  form.databaseName = account?.databaseName || ''
  form.tableName = ''
  tables.value = []
  if (!form.accountId) return
  await loadTables()
}

async function loadTables() {
  if (!form.accountId) return
  loadingTables.value = true
  try {
    const body = await api(`/accounts/${form.accountId}/tables`)
    tables.value = (body || []).map(item => item.table || item.name).filter(Boolean)
  } catch (e) {
    tables.value = []
    CdcMessage.warning(e.message)
  } finally {
    loadingTables.value = false
  }
}

async function onFile(file) {
  excel.file = file
  excel.fileName = file.name
  excel.parsing = true
  excel.progress = 0
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await upload('/datasets/excel/parse', data, value => { excel.progress = value })
    excel.token = result.token
    excel.sheets = result.sheets || []
    excel.sheetIndex = 0
    await selectSheet(0)
    CdcMessage.success(`解析完成，共 ${excel.sheets.length} 个 sheet`)
  } catch (e) {
    CdcMessage.error(e.message)
    excel.token = ''
    excel.sheets = []
    excel.columns = []
    excel.sample = []
  } finally {
    excel.parsing = false
  }
}

async function selectSheet(index) {
  excel.sheetIndex = index
  const sheet = excel.sheets[index]
  excel.headerRow = sheet?.headerRow || 1
  excel.maxColumns = sheet?.columns?.length || 0
  excel.rangeMode = 'auto'
  excel.startColumn = 0
  excel.endColumn = Math.max(0, excel.maxColumns - 1)
  excel.dedupeKey = ''
  await loadInspect()
}

let inspectSeq = 0
/**
 * 表头行、列范围改了都要重新推导字段。
 *
 * <p>先用序号占位，慢的那次回来时直接丢掉，避免连续改表头时列表跳回旧结果。
 */
async function loadInspect() {
  if (!excel.token || !excel.sheets.length) return
  const seq = ++inspectSeq
  excel.inspecting = true
  try {
    const body = await api('/datasets/excel/inspect', {
      method: 'POST',
      body: JSON.stringify({
        token: excel.token, sheetIndex: excel.sheetIndex, headerRow: excel.headerRow,
        startColumn: excel.rangeMode === 'custom' ? excel.startColumn : null,
        endColumn: excel.rangeMode === 'custom' ? excel.endColumn : null
      })
    })
    if (seq !== inspectSeq) return
    excel.headerRow = body.headerRow
    excel.startColumn = body.startColumn
    excel.endColumn = body.endColumn
    excel.rowCount = body.rowCount
    excel.columns = (body.columns || []).map(column => ({ ...column }))
    excel.sample = body.sampleRows || []
    if (!excel.columns.some(column => column.name === excel.dedupeKey)) excel.dedupeKey = ''
  } catch (e) {
    if (seq === inspectSeq) CdcMessage.error(e.message)
  } finally {
    if (seq === inspectSeq) excel.inspecting = false
  }
}

function setRangeMode(mode) {
  excel.rangeMode = mode
  if (mode === 'auto') {
    excel.startColumn = 0
    excel.endColumn = Math.max(0, excel.columns.length - 1)
  }
  loadInspect()
}

function onRangeChange() {
  if (excel.endColumn < excel.startColumn) excel.endColumn = excel.startColumn
  loadInspect()
}

function canNext() {
  if (step.value === 1) return true
  if (type.value === 'TABLE') return !!(form.accountId && form.tableName)
  if (type.value === 'SQL') return !!(form.accountId && form.sqlText.trim())
  return !!(excel.token && excel.columns.length && excel.columns.every(column => column.name.trim()))
}

function next() {
  if (!canNext()) {
    CdcMessage.warning(type.value === 'TABLE' ? '请选择账户和数据表' : type.value === 'SQL' ? '请填写账户和 SQL' : '请先上传文件并填写字段名称')
    return
  }
  if (type.value === 'EXCEL') {
    const names = excel.columns.map(column => column.name.trim())
    if (new Set(names).size !== names.length) {
      CdcMessage.warning('字段名称不能重复')
      return
    }
  }
  if (step.value < 3) step.value++
}

async function save() {
  if (!form.name.trim()) {
    CdcMessage.warning('请填写数据集名称')
    return
  }
  saving.value = true
  try {
    if (type.value === 'EXCEL') {
      await api('/datasets/excel', {
        method: 'POST',
        body: JSON.stringify({
          name: form.name, description: form.description, token: excel.token, fileName: excel.fileName,
          sheetIndex: excel.sheetIndex, headerRow: excel.headerRow,
          startColumn: excel.rangeMode === 'custom' ? excel.startColumn : null,
          endColumn: excel.rangeMode === 'custom' ? excel.endColumn : null,
          dedupeKey: excel.dedupeKey || null,
          columns: excel.columns.map(column => ({
            name: column.name.trim(), type: column.type, originalName: column.originalName || column.name.trim()
          }))
        })
      })
    } else {
      await api('/datasets', {
        method: 'POST',
        body: JSON.stringify({
          name: form.name, description: form.description, type: type.value,
          accountId: form.accountId, databaseName: form.databaseName,
          tableName: type.value === 'TABLE' ? form.tableName : null,
          sqlText: type.value === 'SQL' ? form.sqlText : null
        })
      })
    }
    CdcMessage.success('数据集已创建')
    router.push('/prep/datasets')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(loadAccounts)
</script>

<template>
  <div class="wizard-shell dataset-wizard" :class="{ 'is-wide': step === 2 && source === 'EXCEL' && excel.sheets.length }">
    <div class="wizard-heading">
      <button class="btn btn-ghost btn-sm" @click="router.push('/prep/datasets')"><ArrowLeft :size="14"/>返回数据集</button>
      <h1>新建数据集</h1>
    </div>

    <cdc-steps :active="step - 1" align-center>
      <cdc-step title="选择来源"/>
      <cdc-step title="配置数据"/>
      <cdc-step title="命名保存"/>
    </cdc-steps>

    <div class="wizard-body">
      <section v-if="step === 1" class="wizard-section">
        <h3>数据集从哪里来</h3>
        <div class="source-cards">
          <button v-for="item in SOURCES" :key="item.value" class="source-card"
                  :class="{ active: source === item.value }" @click="source = item.value">
            <component :is="item.icon" :size="22"/>
            <strong>{{ item.title }}</strong>
            <span>{{ item.desc }}</span>
          </button>
        </div>
      </section>

      <section v-else-if="step === 2" class="wizard-section">
        <h3>配置数据</h3>
        <template v-if="source === 'DB'">
          <cdc-radio-group v-model="dbMode" style="margin-bottom:14px">
            <cdc-radio-button value="TABLE">选择数据表</cdc-radio-button>
            <cdc-radio-button value="SQL">自定义 SQL</cdc-radio-button>
          </cdc-radio-group>
          <cdc-form-item label="数据账户" required>
            <cdc-select v-model="form.accountId" filterable placeholder="选择可查询的账户" style="width:100%" @change="accountChanged">
              <cdc-option v-for="item in accounts" :key="item.id" :label="item.name" :value="item.id" :brand="item.accountType"><DatabaseBrand :type="item.accountType" :label="item.name"/></cdc-option>
            </cdc-select>
            <div class="form-help">表清单来自账户上配置的数据库</div>
          </cdc-form-item>
          <cdc-form-item v-if="dbMode === 'TABLE'" label="数据表" required>
            <cdc-select v-model="form.tableName" filterable placeholder="选择数据表" style="width:100%" :loading="loadingTables">
              <cdc-option v-for="name in tables" :key="name" :label="name" :value="name"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item v-else label="查询 SQL" required>
            <textarea v-model="form.sqlText" class="dataset-sql" rows="10"
                      placeholder="SELECT id, amount, status FROM orders WHERE amount > 0"/>
          </cdc-form-item>
        </template>

        <template v-else>
          <ExcelUploader :file-name="excel.fileName" @file="onFile"/>
          <div v-if="excel.parsing" class="loading-line">正在解析文件… {{ excel.progress }}%</div>
          <template v-if="excel.sheets.length">
            <div class="file-config">
              <div class="config-line">
                <span class="config-label">设置表头</span>
                <div class="header-row-pick">
                  <span>第</span>
                  <cdc-input-number v-model="excel.headerRow" :min="1" class="tiny-number" @change="loadInspect"/>
                  <span>行</span>
                </div>
                <cdc-select :model-value="excel.sheetIndex" class="sheet-pick" aria-label="Sheet"
                            @update:model-value="value => selectSheet(value)">
                  <cdc-option v-for="sheet in excel.sheets" :key="sheet.index"
                              :label="`${sheet.name}（${sheet.rowCount} 行）`" :value="sheet.index"/>
                </cdc-select>
                <div class="config-summary">
                  共 {{ excel.rowCount }} 行数据 · {{ excel.columns.length }} 列
                </div>
              </div>

              <div class="config-line">
                <span class="config-label">选取列数据</span>
                <cdc-radio-group :model-value="excel.rangeMode" @update:model-value="setRangeMode">
                  <cdc-radio-button value="auto">默认（第 A 列 ~ 表头最后非空列）</cdc-radio-button>
                  <cdc-radio-button value="custom">自定义</cdc-radio-button>
                </cdc-radio-group>
                <div v-if="excel.rangeMode === 'custom'" class="range-pick">
                  <span>第</span>
                  <cdc-select v-model="excel.startColumn" class="range-select" aria-label="起始列" @change="onRangeChange">
                    <cdc-option v-for="item in columnChoices" :key="item.value" :label="item.label" :value="item.value"/>
                  </cdc-select>
                  <span>列 至 第</span>
                  <cdc-select v-model="excel.endColumn" class="range-select" aria-label="结束列" @change="onRangeChange">
                    <cdc-option v-for="item in columnChoices" :key="item.value" :label="item.label" :value="item.value"/>
                  </cdc-select>
                  <span>列</span>
                </div>
              </div>

              <div v-if="excel.inspecting" class="loading-line">正在读取字段…</div>
              <div v-else class="field-grid-wrap">
                <table class="field-grid" :style="{ width: gridWidth }">
                  <thead>
                    <tr class="name-row">
                      <th class="idx-cell"/>
                      <th v-for="(column, index) in excel.columns" :key="'n' + index">
                        <input v-model="column.name" class="field-name" :title="`原始字段：${column.originalName}`"
                               :aria-label="`第 ${columnLabel(index)} 列字段名称`">
                      </th>
                    </tr>
                    <tr class="type-row">
                      <th class="idx-cell"/>
                      <th v-for="(column, index) in excel.columns" :key="'t' + index">
                        <cdc-select v-model="column.type" class="field-type" :aria-label="`第 ${columnLabel(index)} 列字段类型`">
                          <cdc-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value"/>
                        </cdc-select>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, index) in excel.sample" :key="'r' + index">
                      <td class="idx-cell">{{ index + 1 }}</td>
                      <td v-for="(cell, cellIndex) in row" :key="cellIndex" class="mono preview-cell">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div class="config-line dedupe-line">
                <span class="config-label">去重主键</span>
                <cdc-select v-model="excel.dedupeKey" class="dedupe-pick" placeholder="请选择" aria-label="去重主键">
                  <cdc-option value="" label="不设置"/>
                  <cdc-option v-for="column in excel.columns" :key="column.name" :label="column.name" :value="column.name"/>
                </cdc-select>
                <span class="config-hint">追加数据时按该字段跳过已存在的行</span>
              </div>
            </div>
          </template>
        </template>
      </section>

      <section v-else class="wizard-section">
        <h3>命名保存</h3>
        <cdc-form-item label="数据集名称" required><cdc-input v-model="form.name" maxlength="160"/></cdc-form-item>
        <cdc-form-item label="描述"><cdc-input v-model="form.description" type="textarea" :rows="3" maxlength="1000"/></cdc-form-item>
        <dl class="detail-grid">
          <div><dt>来源类型</dt><dd>{{ SOURCES.find(item => item.value === type)?.title }}</dd></div>
          <div v-if="type === 'TABLE'"><dt>数据表</dt><dd class="mono">{{ form.databaseName }}.{{ form.tableName }}</dd></div>
          <div v-if="type === 'SQL'"><dt>查询</dt><dd class="mono">{{ form.sqlText.slice(0, 80) }}</dd></div>
          <div v-if="type === 'EXCEL'"><dt>文件</dt><dd>{{ excel.fileName }} · {{ excel.columns.length }} 个字段</dd></div>
        </dl>
      </section>
    </div>

    <div class="wizard-footer">
      <span/>
      <cdc-button v-if="step > 1" @click="step--">上一步</cdc-button>
      <cdc-button v-if="step < 3" type="primary" @click="next">下一步<ArrowRight :size="14"/></cdc-button>
      <cdc-button v-else type="primary" :loading="saving" @click="save">创建数据集</cdc-button>
    </div>
  </div>
</template>

<style scoped>
.wizard-shell.is-wide { max-width: 1080px; }
/* 内容不满一屏时页脚也压在最底下，滚动时靠 sticky 吸住，不会跟着内容上下漂 */
.dataset-wizard { display: flex; flex-direction: column; min-height: 100%; }
.dataset-wizard .wizard-body { flex: 1; }
.dataset-wizard .wizard-footer { margin-top: auto; }
.source-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.source-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 6px;
  padding: 16px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--card); text-align: left; color: var(--fg); cursor: pointer;
}
.source-card span { color: var(--muted-fg); font-size: 11.5px; line-height: 1.5; }
.source-card.active { border-color: var(--primary); background: var(--brand-subtle); }
.dataset-sql {
  width: 100%; font: 12px var(--mono); padding: 10px; border: 1px solid var(--border);
  border-radius: 6px; background: var(--card); color: var(--fg); resize: vertical;
}
.file-config { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.config-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; min-height: 30px; }
.config-label { flex: 0 0 auto; width: 72px; font-size: 12px; color: var(--muted-fg); }
.config-summary { margin-left: auto; font-size: 11.5px; color: var(--muted-fg); }
.header-row-pick, .range-pick { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.tiny-number { width: 64px; }
.sheet-pick { width: 200px; }
.range-select { width: 72px; }
.dedupe-pick { width: 220px; }
.config-hint { font-size: 11.5px; color: var(--muted-fg); }
.field-grid-wrap {
  border: 1px solid var(--border); border-radius: 8px; overflow: auto;
  max-height: 380px; background: var(--card);
}
.field-grid { border-collapse: separate; border-spacing: 0; table-layout: fixed; }
.field-grid th, .field-grid td {
  padding: 0; border-right: 1px solid var(--border-subtle); border-bottom: 1px solid var(--border-subtle);
  background: var(--card);
}
/* 表头两行一起吸顶：只给 th 加 sticky 时，两行表头在 Chrome 里会整块跟着滚走 */
.field-grid thead { position: sticky; top: 0; z-index: 3; background: var(--card); }
.field-grid thead th { background: var(--card); }
.field-grid th.idx-cell, .field-grid td.idx-cell {
  position: sticky; left: 0; z-index: 1; width: 42px; min-width: 42px; max-width: 42px;
  padding: 0 8px; text-align: right; font: 11px var(--mono); color: var(--muted-fg); background: var(--muted);
}
/* 左上角那格要同时压住表头其它列和数据行的序号列 */
.field-grid thead .idx-cell { z-index: 4; }
/* 表头两行也按 32px 走，和数据行一样高，否则上面两行厚、下面的数据又薄又挤 */
.field-grid .name-row th:not(.idx-cell) {
  width: 150px; min-width: 150px; max-width: 150px; height: 32px; padding: 0 4px;
}
.field-name {
  width: 100%; height: 28px; padding: 0 6px; font-size: 12px; line-height: 26px;
  color: var(--fg); background: transparent; border: 1px solid transparent; border-radius: 5px;
}
.field-name:hover { border-color: var(--border); }
.field-name:focus { outline: none; border-color: var(--primary); background: var(--card); }
/* 第二行只放数据库类型，高度和名称行对齐；子组件内部的 trigger 要用 :deep 才够得着 */
.field-grid .type-row th:not(.idx-cell) { height: 32px; padding: 0 4px; }
.field-type { width: 100%; }
.field-type :deep(.select-trigger) {
  height: 28px; min-height: 0; padding: 0 6px; border-radius: 5px; font-size: 12px;
}
.field-grid tbody td { height: 32px; }
.field-grid td.preview-cell {
  padding: 0 8px; line-height: 31px; font-size: 12px; color: var(--muted-fg);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.field-grid tbody tr:hover td { background: var(--muted); }
@media (max-width: 800px) { .source-cards { grid-template-columns: 1fr; } }
</style>
