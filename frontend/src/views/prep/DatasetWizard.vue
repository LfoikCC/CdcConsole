<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Code2, FileSpreadsheet, Table2 } from 'lucide-vue-next'
import { api, upload } from '../../api'
import { CdcMessage } from '../../components/ui/feedback'
import ExcelUploader from '../../components/prep/ExcelUploader.vue'

const router = useRouter()
const step = ref(1)
const type = ref('TABLE')
const saving = ref(false)
const accounts = ref([])
const databases = ref([])
const tables = ref([])
const loadingTables = ref(false)

const form = reactive({
  accountId: null, databaseName: '', tableName: '', sqlText: '', name: '', description: ''
})
const excel = reactive({
  file: null, fileName: '', token: '', progress: 0, parsing: false,
  sheets: [], sheetIndex: 0, headerRow: 1, columns: []
})

const TYPES = [
  { value: 'BIGINT', label: '整数 BIGINT' },
  { value: 'DECIMAL(38,10)', label: '小数 DECIMAL' },
  { value: 'DOUBLE', label: '浮点 DOUBLE' },
  { value: 'VARCHAR', label: '文本 VARCHAR' },
  { value: 'TIMESTAMP', label: '时间 TIMESTAMP' },
  { value: 'BOOLEAN', label: '布尔 BOOLEAN' }
]
const SOURCES = [
  { value: 'TABLE', title: '数据库表', desc: '直连已有表，字段随源表变化，预览实时读取', icon: Table2 },
  { value: 'SQL', title: '自定义 SQL', desc: '写一条 SELECT，把查询结果当数据集', icon: Code2 },
  { value: 'EXCEL', title: 'Excel 文件', desc: '上传后物化到平台，可选择 sheet、表头与字段类型', icon: FileSpreadsheet }
]

const selectedSheet = computed(() => excel.sheets[excel.sheetIndex] || null)

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
  form.databaseName = account?.databaseName || ''
  form.tableName = ''
  tables.value = []
  if (!form.accountId) return
  try {
    const body = await api(`/accounts/${form.accountId}/databases`)
    databases.value = (body || []).map(item => (typeof item === 'string' ? item : item.name)).filter(Boolean)
  } catch {
    databases.value = []
  }
  if (form.databaseName) await loadTables()
}

async function loadTables() {
  if (!form.accountId) return
  loadingTables.value = true
  try {
    const query = form.databaseName ? `?database=${encodeURIComponent(form.databaseName)}` : ''
    const body = await api(`/accounts/${form.accountId}/tables${query}`)
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
    selectSheet(0)
    CdcMessage.success(`解析完成，共 ${excel.sheets.length} 个 sheet`)
  } catch (e) {
    CdcMessage.error(e.message)
    excel.token = ''
    excel.sheets = []
  } finally {
    excel.parsing = false
  }
}

function selectSheet(index) {
  excel.sheetIndex = index
  const sheet = excel.sheets[index]
  excel.headerRow = sheet?.headerRow || 1
  excel.columns = (sheet?.columns || []).map(column => ({ ...column }))
}

function removeColumn(index) {
  excel.columns.splice(index, 1)
}

function canNext() {
  if (step.value === 1) return true
  if (type.value === 'TABLE') return !!(form.accountId && form.tableName)
  if (type.value === 'SQL') return !!(form.accountId && form.sqlText.trim())
  return !!(excel.token && excel.columns.length)
}

function next() {
  if (!canNext()) {
    CdcMessage.warning(type.value === 'TABLE' ? '请选择账户和数据表' : type.value === 'SQL' ? '请填写账户和 SQL' : '请先上传并确认字段')
    return
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
          sheetIndex: excel.sheetIndex, headerRow: excel.headerRow, columns: excel.columns
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
  <div class="wizard-shell">
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
                  :class="{ active: type === item.value }" @click="type = item.value">
            <component :is="item.icon" :size="22"/>
            <strong>{{ item.title }}</strong>
            <span>{{ item.desc }}</span>
          </button>
        </div>
      </section>

      <section v-else-if="step === 2" class="wizard-section">
        <h3>配置数据</h3>
        <template v-if="type === 'TABLE'">
          <div class="two-cols">
            <cdc-form-item label="数据账户" required>
              <cdc-select v-model="form.accountId" filterable placeholder="选择可查询的账户" style="width:100%" @change="accountChanged">
                <cdc-option v-for="item in accounts" :key="item.id" :label="item.name" :value="item.id" :brand="item.accountType"/>
              </cdc-select>
            </cdc-form-item>
            <cdc-form-item label="数据库">
              <cdc-input v-model="form.databaseName" placeholder="默认使用账户里的数据库" @blur="loadTables"/>
            </cdc-form-item>
          </div>
          <cdc-form-item label="数据表" required>
            <cdc-select v-model="form.tableName" filterable placeholder="选择数据表" style="width:100%" :loading="loadingTables">
              <cdc-option v-for="name in tables" :key="name" :label="name" :value="name"/>
            </cdc-select>
          </cdc-form-item>
        </template>

        <template v-else-if="type === 'SQL'">
          <div class="two-cols">
            <cdc-form-item label="数据账户" required>
              <cdc-select v-model="form.accountId" filterable placeholder="选择可查询的账户" style="width:100%" @change="accountChanged">
                <cdc-option v-for="item in accounts" :key="item.id" :label="item.name" :value="item.id" :brand="item.accountType"/>
              </cdc-select>
            </cdc-form-item>
            <cdc-form-item label="数据库">
              <cdc-input v-model="form.databaseName" placeholder="默认使用账户里的数据库"/>
            </cdc-form-item>
          </div>
          <cdc-form-item label="查询 SQL" required>
            <textarea v-model="form.sqlText" class="dataset-sql" rows="10"
                      placeholder="SELECT id, amount, status FROM orders WHERE amount > 0"/>
          </cdc-form-item>
        </template>

        <template v-else>
          <ExcelUploader :file-name="excel.fileName" @file="onFile"/>
          <div v-if="excel.parsing" class="loading-line">正在解析文件… {{ excel.progress }}%</div>
          <template v-if="excel.sheets.length">
            <div class="two-cols excel-config">
              <cdc-form-item label="Sheet">
                <cdc-select :model-value="excel.sheetIndex" style="width:100%"
                            @update:model-value="value => selectSheet(value)">
                  <cdc-option v-for="sheet in excel.sheets" :key="sheet.index"
                              :label="`${sheet.name}（${sheet.rowCount} 行）`" :value="sheet.index"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="表头行">
                <cdc-input-number v-model="excel.headerRow" :min="1" style="width:100%"/>
              </cdc-form-item>
            </div>
            <div class="column-editor">
              <div class="column-editor-head"><span>字段</span><span>类型</span><span/></div>
              <div v-for="(column, index) in excel.columns" :key="index" class="column-editor-row">
                <cdc-input v-model="column.name"/>
                <cdc-select v-model="column.type">
                  <cdc-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value"/>
                </cdc-select>
                <button class="icon-btn" aria-label="移除字段" @click="removeColumn(index)">✕</button>
              </div>
            </div>
            <div v-if="selectedSheet?.sampleRows?.length" class="table-wrap sample-table">
              <table>
                <thead><tr><th v-for="column in excel.columns" :key="column.name">{{ column.name }}</th></tr></thead>
                <tbody>
                  <tr v-for="(row, index) in selectedSheet.sampleRows" :key="index">
                    <td v-for="(value, cell) in row" :key="cell" class="mono">{{ value }}</td>
                  </tr>
                </tbody>
              </table>
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
.excel-config { margin-top: 14px; }
.column-editor { margin-top: 10px; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.column-editor-head, .column-editor-row {
  display: grid; grid-template-columns: minmax(0, 1fr) 190px 32px; gap: 8px; align-items: center;
  padding: 6px 10px;
}
.column-editor-head { background: var(--muted); font-size: 11.5px; color: var(--muted-fg); }
.column-editor-row { border-top: 1px solid var(--border); }
.sample-table { margin-top: 12px; max-height: 220px; overflow: auto; }
@media (max-width: 800px) { .source-cards { grid-template-columns: 1fr; } }
</style>
