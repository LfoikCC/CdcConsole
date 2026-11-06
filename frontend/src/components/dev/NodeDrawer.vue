<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Info, Pencil } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage } from '../ui/feedback'
import SqlEditorDialog from './SqlEditorDialog.vue'
import {
  AGG_FUNCTIONS, CAST_TYPES, COMPARATORS, OPERATOR_LABELS,
  iconOf, metaOf,
} from './nodeTypes'

const props = defineProps({
  node: { type: Object, required: true },
  sources: { type: Array, default: () => [] },
  targets: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  upstreamColumns: { type: Array, default: () => [] },
  secondUpstreamColumns: { type: Array, default: () => [] },
  // 两个上游的显示名（含表名），关联数据靠它标清左表 / 右表
  upstreamLabel: { type: String, default: '' },
  secondUpstreamLabel: { type: String, default: '' },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['apply', 'preview', 'close', 'need-upstream', 'delete', 'dataset-created'])

const meta = computed(() => metaOf(props.node.type))
const icon = computed(() => iconOf(props.node))
// structuredClone 无法克隆 Vue 的响应式 Proxy；配置本身是纯 JSON，用 JSON 深拷贝即可
const clone = value => JSON.parse(JSON.stringify(value ?? {}))
const draft = ref(clone(props.node.config || {}))
const nodeName = ref(props.node.name || meta.value.label)
const targetTables = ref([])
const localDatasets = ref([])
const creatingDataset = ref(false)
const newDatasetName = ref('')
const DATASET_TYPE_LABELS = { TABLE: '库表', SQL: 'SQL', EXCEL: 'Excel', EXTRACT: '离线' }
const datasetOptions = computed(() => {
  const seen = new Set()
  return [...props.datasets, ...localDatasets.value].filter(item => {
    if (seen.has(item.id) || item.datasetType === 'SQL') return false
    seen.add(item.id)
    return true
  })
})
const targetMeta = ref(null)
const loading = ref(false)
const error = ref(null)
const dbType = ref('')
const sqlDialogOpen = ref(false)
const editingName = ref(false)
const nameDraft = ref('')
const nameInput = ref(null)
const targetDbType = ref('')
const newTableOpen = ref(false)
const newTableName = ref('')
const newTableInput = ref(null)
const mappingOpen = ref(false)
const mappingRows = ref([])
const preSqlOpen = ref(false)
const preSqlDraft = ref('')

/** 数据库输出的三种写法，顺序按「由轻到重」排：追加、全量、按主键更新。 */
const WRITE_MODES = [
  { value: 'APPEND', label: '直接追加', hint: '不清目标表，把本次结果直接插进去；重复运行会产生重复行' },
  { value: 'OVERWRITE', label: '全量更新', hint: '先清空目标表，再写入本次结果' },
  { value: 'UPSERT', label: '插入更新', hint: '按主键判断：已存在的行更新，不存在的行插入' }
]
const TARGET_DB_LABELS = { MYSQL: 'MySQL', SQLSERVER: 'SQL Server', STARROCKS_STREAM_LOAD: 'StarRocks' }
const headerTitle = computed(() => nodeName.value || (draft.value.operator
  ? (OPERATOR_LABELS[draft.value.operator] || meta.value.label)
  : meta.value.label))

function startNameEdit() {
  nameDraft.value = nodeName.value
  editingName.value = true
  nextTick(() => {
    nameInput.value?.focus()
    nameInput.value?.select()
  })
}

function commitName() {
  const fallback = draft.value.operator ? (OPERATOR_LABELS[draft.value.operator] || meta.value.label) : meta.value.label
  nodeName.value = nameDraft.value.trim() || fallback
  editingName.value = false
}

function cancelName() {
  editingName.value = false
}
const DB_TYPE_LABELS = { MYSQL: 'MySQL', SQLSERVER: 'SQL Server', STARROCKS: 'StarRocks' }
const dbTypes = computed(() => [...new Set(props.sources.map(item => item.dbType).filter(Boolean))])
const filteredSources = computed(() => props.sources.filter(item => !dbType.value || item.dbType === dbType.value))
const sqlLines = computed(() => (draft.value.sql || '').split('\n'))

// 数据库输出：Kafka 目标写不了关系表，直接不出现在「数据连接」里
const dbTargets = computed(() => props.targets.filter(item => item.targetType !== 'KAFKA'))
const targetDbTypes = computed(() => [...new Set(dbTargets.value.map(item => item.targetType).filter(Boolean))])
const filteredTargets = computed(() => dbTargets.value.filter(item => !targetDbType.value || item.targetType === targetDbType.value))
// 目标表下拉：已有表 + 当前手填的表名（否则选中后下拉里显示空白）
const tableOptions = computed(() => {
  const names = targetTables.value.map(item => item.table)
  if (draft.value.table && !names.includes(draft.value.table)) names.unshift(draft.value.table)
  return names
})
const preSqlLines = computed(() => (draft.value.preSql || '').split('\n'))

function initDbType() {
  const selected = props.sources.find(item => item.id === draft.value.sourceId)
  dbType.value = selected?.dbType || (dbTypes.value.length === 1 ? dbTypes.value[0] : '')
}

function onDbTypeChange(value) {
  dbType.value = value
  const selected = props.sources.find(item => item.id === draft.value.sourceId)
  if (selected && selected.dbType !== value) {
    draft.value.sourceId = null
    draft.value.table = ''
    draft.value.columns = []
  }
}

watch(() => props.node, () => {
  draft.value = clone(props.node.config || {})
  nodeName.value = props.node.name || metaOf(props.node.type).label
  targetMeta.value = null
  targetTables.value = []
  if (props.node.type === 'DB_INPUT' || props.node.type === 'SQL_INPUT') {
    if (draft.value.previewMode == null) draft.value.previewMode = 'LIMIT'
    if (draft.value.limit == null) draft.value.limit = 10000
    initDbType()
  }
  if (props.node.type === 'DB_OUTPUT') {
    if (draft.value.mode == null) draft.value.mode = 'APPEND'
    if (draft.value.highPerformance == null) draft.value.highPerformance = false
    newTableOpen.value = false
    newTableName.value = ''
    initTargetDbType()
    if (draft.value.targetId) loadTargetTables()
  }
  if (props.node.type === 'OPERATOR') emit('need-upstream')
}, { immediate: true })

async function guard(work) {
  loading.value = true
  error.value = null
  try {
    return await work()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadTargetTables() {
  if (!draft.value.targetId) return
  targetTables.value = await guard(() => api(`/targets/${draft.value.targetId}/tables`)) || []
  syncTargetMeta()
}

function syncTargetMeta() {
  const found = targetTables.value.find(item => item.table === draft.value.table)
  targetMeta.value = found || null
  if (found && (!draft.value.keyColumns || draft.value.keyColumns.length === 0)) {
    draft.value.keyColumns = [...(found.primaryKeys || [])]
  }
  // 目标列不再按整张表预填：列数一旦多于上游就会直接报「目标列数与上游列数不一致」，
  // 留空表示按上游列名一一写入，需要改名时用「字段映射」
}

async function onSourceChange(value) {
  const selected = props.sources.find(item => item.id === value)
  if (selected?.dbType) dbType.value = selected.dbType
  draft.value.sourceId = value
  draft.value.table = ''
  draft.value.columns = []
  draft.value.targetColumns = []
}

async function onTargetChange(value) {
  draft.value.targetId = value
  draft.value.table = ''
  draft.value.keyColumns = []
  draft.value.targetColumns = []
  targetMeta.value = null
  await loadTargetTables()
}

function onTargetTableChange(value) {
  draft.value.table = value
  draft.value.keyColumns = []
  draft.value.targetColumns = []
  syncTargetMeta()
}

/** 表名可以手填：库里还没有这张表时，运行会按上游结构自动建表。 */
function onTargetTableInput(value) {
  onTargetTableChange(String(value || '').trim())
}

function initTargetDbType() {
  const selected = props.targets.find(item => item.id === draft.value.targetId)
  targetDbType.value = selected?.targetType || (targetDbTypes.value.length === 1 ? targetDbTypes.value[0] : '')
}

function onTargetDbTypeChange(value) {
  targetDbType.value = value
  const selected = props.targets.find(item => item.id === draft.value.targetId)
  if (selected && selected.targetType !== value) {
    draft.value.targetId = null
    draft.value.table = ''
    draft.value.keyColumns = []
    draft.value.targetColumns = []
    targetTables.value = []
    targetMeta.value = null
  }
}

/** 目标表可以在「选已有表」和「填新表名」之间切换，按钮文案跟着变。 */
function toggleNewTable() {
  newTableOpen.value = !newTableOpen.value
  if (!newTableOpen.value) return
  newTableName.value = ''
  nextTick(() => newTableInput.value?.focus())
}

/** 字段映射：上游列按位置对应到目标列，确认后写回 targetColumns。 */
function buildMappingRows() {
  const targets = draft.value.targetColumns || []
  mappingRows.value = props.upstreamColumns.map((column, index) => ({
    source: column.label,
    target: targets[index] || column.label
  }))
}

function openMapping() {
  buildMappingRows()
  mappingOpen.value = true
}

// 上游字段是异步取的：弹窗开着时字段才到（刚预览完），补一次行，别让用户对着空列表发呆
watch(() => props.upstreamColumns, () => {
  if (mappingOpen.value && !mappingRows.value.length) buildMappingRows()
})

function confirmMapping() {
  if (!mappingRows.value.length) {
    mappingOpen.value = false
    return
  }
  draft.value.targetColumns = mappingRows.value.map(row => (row.target || row.source).trim())
  mappingOpen.value = false
}

/** 同名映射：每一行都写上游列名（留空本来就是这个含义，这里把它显式填出来）。 */
function mapByNameRows() {
  mappingRows.value = mappingRows.value.map(row => ({ ...row, target: row.source }))
}

/** 顺序映射：按目标表字段顺序依次对应，和新建同步任务里的行为一致。 */
function mapByOrderRows() {
  const columns = targetMeta.value?.columns || []
  if (!columns.length) {
    CdcMessage.warning('先选择目标表，才能按目标表字段顺序映射')
    return
  }
  mappingRows.value = mappingRows.value.map((row, index) => ({ ...row, target: columns[index] || '' }))
}

// 目标列里没有的字段写进去，运行时会报列不存在；先在弹窗里点出来
const mappingMissing = computed(() => {
  const columns = targetMeta.value?.columns || []
  if (!columns.length) return []
  return mappingRows.value
    .filter(row => row.target && !columns.includes(row.target))
    .map(row => row.target)
})

function openPreSql() {
  preSqlDraft.value = draft.value.preSql || ''
  preSqlOpen.value = true
}

function confirmPreSql() {
  draft.value.preSql = preSqlDraft.value
  preSqlOpen.value = false
}

// ------------------------------------------------------------- operators

function operatorItems() {
  if (!Array.isArray(draft.value.items)) draft.value.items = []
  return draft.value.items
}

function toggleProject(column) {
  const items = operatorItems()
  const index = items.findIndex(item => item.column === column)
  if (index >= 0) items.splice(index, 1)
  else items.push({ column, alias: '', type: '' })
}

function isProjected(column) {
  return operatorItems().some(item => item.column === column)
}

function aliasOf(column) {
  return operatorItems().find(item => item.column === column)?.alias || ''
}

function setAlias(column, alias) {
  const item = operatorItems().find(entry => entry.column === column)
  if (item) item.alias = alias
}

function conditions() {
  if (!Array.isArray(draft.value.conditions)) draft.value.conditions = []
  return draft.value.conditions
}

function addCondition() {
  conditions().push({ column: props.upstreamColumns[0]?.label || '', op: '=', value: '' })
}

function removeCondition(index) {
  conditions().splice(index, 1)
}

function sortKeys() {
  if (!Array.isArray(draft.value.keys)) draft.value.keys = []
  return draft.value.keys
}

function addSortKey() {
  sortKeys().push({ column: props.upstreamColumns[0]?.label || '', direction: 'ASC' })
}

function removeSortKey(index) {
  sortKeys().splice(index, 1)
}

function dedupKeys() {
  if (!Array.isArray(draft.value.keys)) draft.value.keys = []
  return draft.value.keys
}

async function createExtractDataset() {
  const name = newDatasetName.value.trim()
  if (!name) {
    CdcMessage.warning('请填写离线数据集名称')
    return
  }
  creatingDataset.value = true
  try {
    const created = await api('/datasets/extract', {
      method: 'POST',
      body: JSON.stringify({ name, description: '' })
    })
    localDatasets.value.push({ id: created.id, name, datasetType: 'EXTRACT' })
    draft.value.datasetId = created.id
    newDatasetName.value = ''
    emit('dataset-created', created)
    CdcMessage.success('离线数据集已创建，运行数据流后写入数据')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    creatingDataset.value = false
  }
}

function groupBy() {
  if (!Array.isArray(draft.value.groupBy)) draft.value.groupBy = []
  return draft.value.groupBy
}

function aggregates() {
  if (!Array.isArray(draft.value.aggregates)) draft.value.aggregates = []
  return draft.value.aggregates
}

function addAggregate() {
  aggregates().push({ function: 'SUM', column: props.upstreamColumns[0]?.label || '', alias: '' })
}

function removeAggregate(index) {
  aggregates().splice(index, 1)
}

function rules() {
  if (!Array.isArray(draft.value.rules)) draft.value.rules = []
  return draft.value.rules
}

function addRule() {
  rules().push({ column: props.upstreamColumns[0]?.label || '', from: '', to: '' })
}

function removeRule(index) {
  rules().splice(index, 1)
}

function fills() {
  if (!Array.isArray(draft.value.fills)) draft.value.fills = []
  return draft.value.fills
}
function addFill() {
  fills().push({ column: props.upstreamColumns[0]?.label || '', value: '' })
}

function removeFill(index) {
  fills().splice(index, 1)
}

function joinKeys() {
  if (!Array.isArray(draft.value.keys)) draft.value.keys = []
  return draft.value.keys
}

function addJoinKey() {
  joinKeys().push({
    left: props.upstreamColumns[0]?.label || '',
    right: props.secondUpstreamColumns[0]?.label || ''
  })
}

function removeJoinKey(index) {
  joinKeys().splice(index, 1)
}

/** 供外部（底部预览条）读取「还没点应用」的当前编辑值。 */
function snapshot() {
  return { config: clone(draft.value), name: nodeName.value }
}

function apply() {
  if (editingName.value) commitName()
  if (props.node.type === 'DB_INPUT' && String(draft.value.sql || '').trim()) {
    draft.value.mode = 'SQL'
  }
  const fallback = draft.value.operator
    ? (OPERATOR_LABELS[draft.value.operator] || meta.value.label)
    : meta.value.label
  emit('apply', {
    config: clone(draft.value),
    name: nodeName.value.trim() || fallback
  })
}
defineExpose({ snapshot })
</script>

<template>
  <aside class="node-drawer" aria-label="节点配置">
    <header class="nd-head">
      <span class="nd-icon"><component :is="icon" :size="15"/></span>
      <div class="nd-title">
        <strong v-if="!editingName" class="nd-name" title="点击修改节点名称" @click="startNameEdit">
          {{ headerTitle }}<Pencil :size="11"/>
        </strong>
        <input v-else ref="nameInput" v-model="nameDraft" class="nd-name-input"
               @keydown.enter.prevent="commitName" @keydown.esc.prevent="cancelName" @blur="editingName && commitName()"/>
        <small>{{ node.id }}</small>
      </div>
      <button class="icon-btn" aria-label="关闭" @click="emit('close')">✕</button>
    </header>

    <div class="nd-body">
      <cdc-form>
        <!-- 数据集输入 -->
        <template v-if="node.type === 'DATASET_INPUT'">
          <cdc-form-item label="数据集" required>
            <cdc-select :model-value="draft.datasetId" filterable placeholder="选择数据集"
                        @update:model-value="value => (draft.datasetId = value)">
              <cdc-option v-for="item in datasets" :key="item.id"
                          :label="`${item.name}（${item.datasetType}）`" :value="item.id"/>
            </cdc-select>
            <div class="form-help">数据集在「数据准备」里维护；直连表 / SQL 实时读取，Excel 读取平台内的副本</div>
          </cdc-form-item>
        </template>

        <!-- 数据库输入：数据库类型 → 数据账户 → SQL 查询 -->
        <template v-else-if="node.type === 'DB_INPUT' || node.type === 'SQL_INPUT'">
          <div class="nd-two-cols">
            <cdc-form-item label="数据库类型" required>
              <cdc-select v-model="dbType" placeholder="选择类型" @update:model-value="onDbTypeChange">
                <cdc-option v-for="type in dbTypes" :key="type" :label="DB_TYPE_LABELS[type] || type" :value="type"/>
              </cdc-select>
            </cdc-form-item>
            <cdc-form-item label="数据账户" required>
              <cdc-select :model-value="draft.sourceId" filterable placeholder="选择账户"
                          @update:model-value="onSourceChange">
                <cdc-option v-for="item in filteredSources" :key="item.id" :label="item.name" :value="item.id"/>
              </cdc-select>
            </cdc-form-item>
          </div>
          <div class="form-help nd-row-help">账户的数据库在「数据准备 → 连接账户」维护</div>

          <cdc-form-item label="SQL 查询" required>
            <div class="nd-sql-preview" @click="sqlDialogOpen = true">
              <div class="nd-sql-gutter">
                <span v-for="(line, index) in sqlLines" :key="index">{{ index + 1 }}</span>
              </div>
              <pre>{{ draft.sql || '点击编写 SQL 查询' }}</pre>
              <button type="button" class="nd-sql-edit">编辑</button>
            </div>
            <div class="form-help">支持 SELECT / WITH 查询；点击查询框打开函数与表目录</div>
          </cdc-form-item>

          <cdc-form-item label="预览设置">
            <cdc-select :model-value="draft.previewMode || 'LIMIT'"
                        @update:model-value="value => (draft.previewMode = value)">
              <cdc-option label="部分数据 - 限制数据行数" value="LIMIT"/>
              <cdc-option label="全部数据" value="ALL"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item v-if="(draft.previewMode || 'LIMIT') !== 'ALL'" label="缓存数据行数">
            <cdc-input-number v-model="draft.limit" :min="1" :max="2000000" style="width:100%"/>
            <div class="form-help">运行时最多读取的行数，默认 10000</div>
          </cdc-form-item>

          <SqlEditorDialog v-model="sqlDialogOpen" :sql="draft.sql || ''" :source-id="draft.sourceId"
                           :db-type="dbType || 'MYSQL'" @confirm="value => (draft.sql = value)"/>
        </template>

        <!-- 基础算子 -->
        <template v-else-if="node.type === 'OPERATOR'">
          <div v-if="!upstreamColumns.length" class="nd-empty">
            先配置并预览上游节点，才能选择字段
            <button class="btn btn-ghost btn-sm" @click="emit('need-upstream')">刷新上游字段</button>
          </div>

          <template v-else>
            <!-- 字段选择 -->
            <div v-if="draft.operator === 'PROJECT'" class="nd-list">
              <div v-for="column in upstreamColumns" :key="column.label" class="nd-row">
                <cdc-checkbox :model-value="isProjected(column.label)"
                              @update:model-value="toggleProject(column.label)">
                  <span class="mono">{{ column.label }}</span>
                </cdc-checkbox>
                <cdc-input v-if="isProjected(column.label)" :model-value="aliasOf(column.label)"
                           placeholder="重命名" @update:model-value="value => setAlias(column.label, value)"/>
              </div>
              <div class="form-help">不勾选任何字段表示原样透传</div>
            </div>

            <!-- 行过滤 -->
            <div v-else-if="draft.operator === 'FILTER'" class="nd-list">
              <div class="nd-join">
                <cdc-radio-group :model-value="draft.join || 'AND'"
                                 @update:model-value="value => (draft.join = value)">
                  <cdc-radio-button value="AND">且</cdc-radio-button>
                  <cdc-radio-button value="OR">或</cdc-radio-button>
                </cdc-radio-group>
              </div>
              <div v-for="(condition, index) in conditions()" :key="index" class="nd-cond">
                <cdc-select v-model="condition.column" filterable placeholder="字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-select v-model="condition.op" placeholder="操作符">
                  <cdc-option v-for="op in COMPARATORS" :key="op" :label="op" :value="op"/>
                </cdc-select>
                <cdc-input v-model="condition.value" placeholder="值"
                           :disabled="['IS NULL','IS NOT NULL'].includes(condition.op)"/>
                <button class="icon-btn" aria-label="删除条件" @click="removeCondition(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addCondition">+ 添加条件</button>
            </div>

            <!-- 排序 -->
            <div v-else-if="draft.operator === 'SORT'" class="nd-list">
              <div v-for="(key, index) in sortKeys()" :key="index" class="nd-cond">
                <cdc-select v-model="key.column" filterable placeholder="字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-select v-model="key.direction" placeholder="方向">
                  <cdc-option label="升序" value="ASC"/>
                  <cdc-option label="降序" value="DESC"/>
                </cdc-select>
                <button class="icon-btn" aria-label="删除排序键" @click="removeSortKey(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addSortKey">+ 添加排序键</button>
            </div>

            <!-- 去重 -->
            <div v-else-if="draft.operator === 'DEDUP'" class="nd-list">
              <cdc-form-item label="去重字段">
                <cdc-select :model-value="dedupKeys()" multiple filterable placeholder="留空表示整行去重"
                            @update:model-value="value => (draft.keys = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
            </div>

            <!-- 分组聚合 -->
            <div v-else-if="draft.operator === 'AGGREGATE'" class="nd-list">
              <cdc-form-item label="分组字段">
                <cdc-select :model-value="groupBy()" multiple filterable placeholder="不选表示全局聚合"
                            @update:model-value="value => (draft.groupBy = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <div v-for="(item, index) in aggregates()" :key="index" class="nd-agg">
                <cdc-select v-model="item.function" placeholder="函数">
                  <cdc-option v-for="fn in AGG_FUNCTIONS" :key="fn" :label="fn" :value="fn"/>
                </cdc-select>
                <cdc-select v-model="item.column" filterable clearable placeholder="字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-input v-model="item.alias" placeholder="输出列名"/>
                <button class="icon-btn" aria-label="删除聚合项" @click="removeAggregate(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addAggregate">+ 添加聚合项</button>
              <div class="form-help">COUNT 不选字段表示 COUNT(*)</div>
            </div>

            <!-- 添加计算列 -->
            <div v-else-if="draft.operator === 'CALCULATE'" class="nd-list">
              <cdc-form-item label="新列名" required>
                <cdc-input v-model="draft.alias" placeholder="含税金额"/>
              </cdc-form-item>
              <cdc-form-item label="表达式" required>
                <cdc-input v-model="draft.expression" placeholder="amount * 1.13"/>
                <div class="form-help">
                  可用列：<span class="mono">{{ upstreamColumns.map(item => item.label).join('、') }}</span>
                </div>
              </cdc-form-item>
              <cdc-form-item label="结果类型" required>
                <cdc-select :model-value="draft.type"
                            @update:model-value="value => (draft.type = value)">
                  <cdc-option v-for="type in CAST_TYPES" :key="type" :label="type" :value="type"/>
                </cdc-select>
              </cdc-form-item>
            </div>

            <!-- 值替换 -->
            <div v-else-if="draft.operator === 'REPLACE_VALUE'" class="nd-list">
              <div v-for="(rule, index) in rules()" :key="index" class="nd-rule">
                <cdc-select v-model="rule.column" filterable placeholder="字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-input v-model="rule.from" placeholder="原值"/>
                <cdc-input v-model="rule.to" placeholder="新值"/>
                <button class="icon-btn" aria-label="删除替换规则" @click="removeRule(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addRule">+ 添加规则</button>
              <div class="form-help">原值不能为空；空值请用「Null 值替换」算子</div>
            </div>

            <!-- Null 值替换 -->
            <div v-else-if="draft.operator === 'REPLACE_NULL'" class="nd-list">
              <div v-for="(fill, index) in fills()" :key="index" class="nd-fill">
                <cdc-select v-model="fill.column" filterable placeholder="字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-input v-model="fill.value" placeholder="替换为"/>
                <button class="icon-btn" aria-label="删除替换字段" @click="removeFill(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addFill">+ 添加字段</button>
            </div>

            <!-- 合并列 -->
            <div v-else-if="draft.operator === 'MERGE_COLUMNS'" class="nd-list">
              <cdc-form-item label="参与列" required>
                <cdc-select :model-value="draft.columns" multiple filterable placeholder="至少选择两个字段"
                            @update:model-value="value => (draft.columns = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="分隔符">
                <cdc-input v-model="draft.separator" placeholder="-"/>
              </cdc-form-item>
              <cdc-form-item label="新列名" required>
                <cdc-input v-model="draft.alias" placeholder="省市"/>
              </cdc-form-item>
              <cdc-checkbox :model-value="!!draft.dropSources"
                            @update:model-value="value => (draft.dropSources = value)">
                合并后丢弃参与列
              </cdc-checkbox>
              <div class="form-help">数值列转成文本后可能带尾零，需要时请配合计算列处理</div>
            </div>

            <!-- 行拼接 -->
            <div v-else-if="draft.operator === 'UNION'" class="nd-list">
              <cdc-form-item label="拼接方式">
                <cdc-radio-group :model-value="draft.mode || 'ALL'"
                                 @update:model-value="value => (draft.mode = value)">
                  <cdc-radio-button value="ALL">全部保留</cdc-radio-button>
                  <cdc-radio-button value="DISTINCT">去重</cdc-radio-button>
                </cdc-radio-group>
              </cdc-form-item>
              <div class="form-help">
                左表 {{ upstreamColumns.length }} 列、右表 {{ secondUpstreamColumns.length }} 列，
                按位置对齐；输出列名与类型沿用左表。
              </div>
              <p v-if="upstreamColumns.length !== secondUpstreamColumns.length" class="nd-error">
                两侧列数不一致，运行前需要调整到相同列数
              </p>
            </div>

            <!-- 关联数据 -->
            <div v-else-if="draft.operator === 'JOIN'" class="nd-list">
              <cdc-form-item label="关联类型">
                <cdc-select :model-value="draft.joinType || 'INNER'"
                            @update:model-value="value => (draft.joinType = value)">
                  <cdc-option label="内连接（只保留匹配行）" value="INNER"/>
                  <cdc-option label="左连接（保留左表全部）" value="LEFT"/>
                  <cdc-option label="右连接（保留右表全部）" value="RIGHT"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="关联键">
                <!-- 左右表名字对齐到下面的两个下拉框，避免「哪个是左表」靠猜 -->
                <div class="nd-join-head">
                  <div class="nd-join-side">
                    <span class="nd-join-tag">左表</span>
                    <span class="nd-join-name" :title="upstreamLabel">{{ upstreamLabel || '未连接' }}</span>
                  </div>
                  <div/>
                  <div class="nd-join-side">
                    <span class="nd-join-tag">右表</span>
                    <span class="nd-join-name" :title="secondUpstreamLabel">{{ secondUpstreamLabel || '未连接' }}</span>
                  </div>
                  <div/>
                </div>
                <div v-for="(key, index) in joinKeys()" :key="index" class="nd-join-key">
                  <cdc-select v-model="key.left" filterable placeholder="左表字段">
                    <cdc-option v-for="column in upstreamColumns" :key="column.label"
                                :label="column.label" :value="column.label"/>
                  </cdc-select>
                  <span class="nd-join-eq">=</span>
                  <cdc-select v-model="key.right" filterable placeholder="右表字段">
                    <cdc-option v-for="column in secondUpstreamColumns" :key="column.label"
                                :label="column.label" :value="column.label"/>
                  </cdc-select>
                  <button class="icon-btn" aria-label="删除关联键" @click="removeJoinKey(index)">✕</button>
                </div>
                <button class="btn btn-ghost btn-sm" @click="addJoinKey">+ 添加关联键</button>
                <div class="form-help">左表取第一条入边，右表取第二条；右表的关联键不重复输出</div>
              </cdc-form-item>
            </div>

            <!-- 行转列 -->
            <div v-else-if="draft.operator === 'PIVOT'" class="nd-list">
              <cdc-form-item label="分组字段">
                <cdc-select :model-value="groupBy()" multiple filterable placeholder="不选表示全局汇总"
                            @update:model-value="value => (draft.groupBy = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="转为列的字段" required>
                <cdc-select :model-value="draft.pivotColumn" filterable
                            @update:model-value="value => (draft.pivotColumn = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="取值字段" required>
                <cdc-select :model-value="draft.valueColumn" filterable
                            @update:model-value="value => (draft.valueColumn = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="聚合方式">
                <cdc-select :model-value="draft.aggregate || 'SUM'"
                            @update:model-value="value => (draft.aggregate = value)">
                  <cdc-option v-for="fn in ['SUM', 'COUNT', 'AVG', 'MIN', 'MAX']" :key="fn" :label="fn" :value="fn"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="列值" required>
                <cdc-input :model-value="(draft.values || []).join(', ')"
                           placeholder="已支付, 未支付"
                           @update:model-value="value => (draft.values = value.split(',').map(item => item.trim()).filter(Boolean))"/>
                <div class="form-help">这些值会变成输出列名，用逗号分隔</div>
              </cdc-form-item>
            </div>

            <!-- 列转行 -->
            <div v-else-if="draft.operator === 'UNPIVOT'" class="nd-list">
              <cdc-form-item label="转为行的字段" required>
                <cdc-select :model-value="draft.columns" multiple filterable placeholder="选择要展开的字段"
                            @update:model-value="value => (draft.columns = value)">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
              </cdc-form-item>
              <cdc-form-item label="字段名列名">
                <cdc-input v-model="draft.nameColumn" placeholder="字段"/>
              </cdc-form-item>
              <cdc-form-item label="值列名">
                <cdc-input v-model="draft.valueColumn" placeholder="值"/>
              </cdc-form-item>
              <div class="form-help">值统一转成文本；未选中的列原样保留</div>
            </div>

            <!-- SQL 算子 -->
            <div v-else-if="draft.operator === 'SQL'" class="nd-list">
              <cdc-form-item label="查询 SQL" required>
                <textarea v-model="draft.sql" class="nd-sql" rows="10"
                          :placeholder="'SELECT * FROM {{input}} WHERE amount > 100'"/>
                <div class="form-help">
                  用 <span class="mono" v-text="'{{input}}'"/> 引用上游临时表；只支持单条 SELECT / WITH 查询
                </div>
              </cdc-form-item>
              <div class="form-help">
                可用列：<span class="mono">{{ upstreamColumns.map(item => item.label).join('、') }}</span>
              </div>
            </div>
          </template>
        </template>

        <!-- 数据库输出 -->
        <!-- 数据集输出 -->
        <template v-else-if="node.type === 'DATASET_OUTPUT'">
          <cdc-form-item label="数据集" required>
            <cdc-select :model-value="draft.datasetId" filterable placeholder="选择数据集"
                        @update:model-value="value => (draft.datasetId = value)">
              <cdc-option v-for="item in datasetOptions" :key="item.id"
                          :label="`${item.name}（${DATASET_TYPE_LABELS[item.datasetType] || item.datasetType}）`" :value="item.id"/>
            </cdc-select>
            <div class="nd-new-dataset">
              <cdc-input v-model="newDatasetName" placeholder="新离线数据集名称"/>
              <button class="btn btn-ghost btn-sm" :disabled="creatingDataset" @click="createExtractDataset">
                {{ creatingDataset ? '创建中…' : '新建离线数据集' }}
              </button>
            </div>
            <div class="form-help">库表数据集写回源表；Excel / 离线数据集写回平台副本，离线数据集首次运行后才会有数据</div>
          </cdc-form-item>
          <cdc-form-item label="写入模式">
            <cdc-radio-group :model-value="draft.mode || 'OVERWRITE'"
                             @update:model-value="value => (draft.mode = value)">
              <cdc-radio-button value="OVERWRITE">覆盖</cdc-radio-button>
              <cdc-radio-button value="APPEND">追加</cdc-radio-button>
            </cdc-radio-group>
          </cdc-form-item>
        </template>

        <template v-else-if="node.type === 'DB_OUTPUT'">
          <cdc-form-item label="更新方式">
            <div class="nd-modes">
              <label v-for="item in WRITE_MODES" :key="item.value" class="nd-mode"
                     :class="{ 'is-active': (draft.mode || 'APPEND') === item.value }">
                <input type="radio" name="nd-write-mode" :value="item.value"
                       :checked="(draft.mode || 'APPEND') === item.value"
                       @change="draft.mode = item.value"/>
                <span>{{ item.label }}</span>
                <span class="nd-hint" :title="item.hint" :aria-label="item.hint"><Info :size="12"/></span>
              </label>
            </div>
          </cdc-form-item>

          <cdc-form-item label="数据连接" required>
            <div class="nd-two-cols">
              <cdc-select :model-value="targetDbType" placeholder="数据库类型"
                          @update:model-value="onTargetDbTypeChange">
                <cdc-option v-for="type in targetDbTypes" :key="type"
                            :label="TARGET_DB_LABELS[type] || type" :value="type"/>
              </cdc-select>
              <cdc-select :model-value="draft.targetId" filterable placeholder="数据账户"
                          @update:model-value="onTargetChange">
                <cdc-option v-for="item in filteredTargets" :key="item.id" :label="item.name" :value="item.id"/>
              </cdc-select>
            </div>
          </cdc-form-item>

          <div class="nd-switch-row">
            <span class="nd-switch-label">
              高性能模式
              <span class="nd-hint" title="调大批次、减少提交次数，适合一次写入几十万行以上"><Info :size="12"/></span>
            </span>
            <cdc-switch :model-value="!!draft.highPerformance"
                        @update:model-value="value => (draft.highPerformance = value)"/>
          </div>

          <div class="form-field" role="group">
            <div class="nd-field-head">
              <span class="field-label">目标表<span class="req"> *</span></span>
              <button type="button" class="nd-field-action" @click="toggleNewTable">
                {{ newTableOpen ? '选择表' : '新建表' }}
              </button>
            </div>
            <div class="nd-target-row">
              <input v-if="newTableOpen" ref="newTableInput" v-model="newTableName" class="nd-table-input"
                     placeholder="输入新表名，运行时自动建表"
                     @input="onTargetTableInput(newTableName)"
                     @keydown.enter.prevent="toggleNewTable"/>
              <cdc-select v-else :model-value="draft.table" filterable placeholder="选择表名"
                          @update:model-value="onTargetTableInput">
                <cdc-option v-for="name in tableOptions" :key="name" :label="name" :value="name"/>
              </cdc-select>
              <button type="button" class="btn btn-ghost btn-sm" @click="openMapping">字段映射</button>
            </div>
            <div class="form-help">表不存在时按上游结构自动建表</div>
          </div>

          <cdc-form-item v-if="draft.mode === 'UPSERT'" label="主键列" required>
            <cdc-select :model-value="draft.keyColumns" multiple filterable placeholder="选择主键列"
                        @update:model-value="value => (draft.keyColumns = value)">
              <cdc-option v-for="column in (targetMeta?.columns || [])" :key="column"
                          :label="column" :value="column"/>
            </cdc-select>
          </cdc-form-item>

          <cdc-form-item label="前置 SQL">
            <div class="nd-sql-preview" @click="openPreSql">
              <div class="nd-sql-gutter">
                <span v-for="(line, index) in preSqlLines" :key="index">{{ index + 1 }}</span>
              </div>
              <pre>{{ draft.preSql || '点击编写前置 SQL' }}</pre>
              <button type="button" class="nd-sql-edit">编辑</button>
            </div>
            <div class="form-help">写入前执行，可写多条、用分号分隔；与本次写入在同一事务里</div>
          </cdc-form-item>

          <cdc-modal v-model="mappingOpen" title="字段映射" width="min(620px,94vw)">
            <div v-if="!mappingRows.length" class="nd-empty">
              还没拿到上游字段，先打开上游节点预览一次
              <button type="button" class="btn btn-ghost btn-sm" @click="emit('need-upstream')">刷新上游字段</button>
            </div>
            <template v-else>
              <div class="nd-map-toolbar">
                <button type="button" class="btn btn-ghost btn-sm" @click="mapByNameRows">同名映射</button>
                <button type="button" class="btn btn-ghost btn-sm" @click="mapByOrderRows">顺序映射</button>
                <span class="form-help">上游 {{ mappingRows.length }} 列，按位置写入目标表</span>
              </div>
              <div class="nd-map-head">
                <span>上游字段</span><span/><span>目标字段</span>
              </div>
              <div class="nd-map-list">
                <div v-for="row in mappingRows" :key="row.source" class="nd-map-row">
                  <span class="mono">{{ row.source }}</span>
                  <span class="nd-map-arrow">→</span>
                  <cdc-select v-if="(targetMeta?.columns || []).length" v-model="row.target" filterable clearable>
                    <cdc-option v-for="column in targetMeta.columns" :key="column" :label="column" :value="column"/>
                  </cdc-select>
                  <cdc-input v-else v-model="row.target" :placeholder="row.source"/>
                </div>
              </div>
              <p v-if="mappingMissing.length" class="nd-error">
                目标表里没有这些字段：{{ mappingMissing.join('、') }}，运行时会报列不存在
              </p>
              <div class="form-help">留空表示沿用上游列名</div>
            </template>
            <template #footer>
              <cdc-button @click="mappingOpen = false">取消</cdc-button>
              <cdc-button type="primary" :disabled="!mappingRows.length" @click="confirmMapping">应用映射</cdc-button>
            </template>
          </cdc-modal>

          <cdc-modal v-model="preSqlOpen" title="前置 SQL" width="min(760px,95vw)">
            <div class="nd-sql-editor">
              <div class="nd-sql-gutter">
                <span v-for="(line, index) in preSqlDraft.split('\n')" :key="index">{{ index + 1 }}</span>
              </div>
              <textarea v-model="preSqlDraft" spellcheck="false"
                        placeholder="DELETE FROM ads.orders WHERE dt = '2026-11-01';"></textarea>
            </div>
            <div class="form-help">写入前按顺序执行，分号分隔；DDL 会隐式提交，请谨慎使用</div>
            <template #footer>
              <cdc-button @click="preSqlOpen = false">取消</cdc-button>
              <cdc-button type="primary" @click="confirmPreSql">确定</cdc-button>
            </template>
          </cdc-modal>
        </template>
      </cdc-form>

      <p v-if="error" class="nd-error">{{ error }}</p>
      <p v-if="loading" class="nd-loading">加载中…</p>

    </div>

    <footer class="nd-foot">
      <button class="btn btn-ghost danger-text nd-delete" :disabled="busy" @click="emit('delete')">删除节点</button>
      <button class="btn btn-primary" :disabled="busy" @click="apply">应用</button>
    </footer>
  </aside>
</template>
