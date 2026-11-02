<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../../api'
import { CdcMessage } from '../ui/feedback'
import {
  AGG_FUNCTIONS, CAST_TYPES, COMPARATORS, MODE_OPTIONS, OPERATOR_LABELS,
  iconOf, metaOf,
} from './nodeTypes'

const props = defineProps({
  node: { type: Object, required: true },
  sources: { type: Array, default: () => [] },
  targets: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  upstreamColumns: { type: Array, default: () => [] },
  secondUpstreamColumns: { type: Array, default: () => [] },
  preview: { type: Object, default: null },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['apply', 'preview', 'close', 'need-upstream', 'delete', 'dataset-created'])

const meta = computed(() => metaOf(props.node.type))
const icon = computed(() => iconOf(props.node))
// structuredClone 无法克隆 Vue 的响应式 Proxy；配置本身是纯 JSON，用 JSON 深拷贝即可
const clone = value => JSON.parse(JSON.stringify(value ?? {}))
const draft = ref(clone(props.node.config || {}))
const nodeName = ref(props.node.name || meta.value.label)
const databases = ref([])
const tables = ref([])
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
const tableMeta = ref(null)
const targetMeta = ref(null)
const loading = ref(false)
const error = ref(null)

watch(() => props.node, () => {
  draft.value = clone(props.node.config || {})
  nodeName.value = props.node.name || metaOf(props.node.type).label
  tableMeta.value = null
  targetMeta.value = null
  databases.value = []
  tables.value = []
  targetTables.value = []
  if (props.node.type === 'DB_INPUT' || props.node.type === 'SQL_INPUT') {
    if (draft.value.sourceId) loadDatabases()
    if (draft.value.table) loadTables()
  }
  if (props.node.type === 'DB_OUTPUT' && draft.value.targetId) loadTargetTables()
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

async function loadDatabases() {
  if (!draft.value.sourceId) return
  databases.value = await guard(() => api(`/sources/${draft.value.sourceId}/databases`)) || []
}

async function loadTables() {
  if (!draft.value.sourceId) return
  const database = draft.value.database || ''
  const query = database ? `?database=${encodeURIComponent(database)}` : ''
  tables.value = await guard(() => api(`/sources/${draft.value.sourceId}/tables${query}`)) || []
  syncTableMeta()
}

async function loadTargetTables() {
  if (!draft.value.targetId) return
  targetTables.value = await guard(() => api(`/targets/${draft.value.targetId}/tables`)) || []
  syncTargetMeta()
}

function syncTableMeta() {
  const found = tables.value.find(item => item.table === draft.value.table)
  tableMeta.value = found || null
  if (found) {
    if (!Array.isArray(draft.value.columns) || draft.value.columns.length === 0) {
      draft.value.columns = [...(found.columns || [])]
    }
    if (!draft.value.targetColumns || draft.value.targetColumns.length === 0) {
      draft.value.targetColumns = [...(found.columns || [])]
    }
  }
}

function syncTargetMeta() {
  const found = targetTables.value.find(item => item.table === draft.value.table)
  targetMeta.value = found || null
  if (found) {
    if (!draft.value.keyColumns || draft.value.keyColumns.length === 0) {
      draft.value.keyColumns = [...(found.primaryKeys || [])]
    }
    if (!draft.value.targetColumns || draft.value.targetColumns.length === 0) {
      draft.value.targetColumns = [...(found.columns || [])]
    }
  }
}

async function onSourceChange(value) {
  draft.value.sourceId = value
  draft.value.table = ''
  tableMeta.value = null
  tables.value = []
  await loadDatabases()
}

async function onDatabaseChange(value) {
  draft.value.database = value
  draft.value.table = ''
  tableMeta.value = null
  await loadTables()
}

async function onTableChange(value) {
  draft.value.table = value
  draft.value.columns = []
  draft.value.targetColumns = []
  syncTableMeta()
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

function apply() {
  const fallback = draft.value.operator
    ? (OPERATOR_LABELS[draft.value.operator] || meta.value.label)
    : meta.value.label
  emit('apply', {
    config: clone(draft.value),
    name: nodeName.value.trim() || fallback
  })
}
</script>

<template>
  <aside class="node-drawer" aria-label="节点配置">
    <header class="nd-head">
      <span class="nd-icon"><component :is="icon" :size="15"/></span>
      <div>
        <strong>{{ node.type === 'OPERATOR' ? (OPERATOR_LABELS[draft.operator] || meta.label) : meta.label }}</strong>
        <small>{{ node.id }}</small>
      </div>
      <button class="icon-btn" aria-label="关闭" @click="emit('close')">✕</button>
    </header>

    <div class="nd-body">
      <cdc-form>
        <cdc-form-item label="节点名称">
          <cdc-input v-model="nodeName" :placeholder="meta.label"/>
          <div class="form-help">画布卡片上显示的名字，留空则用默认名称</div>
        </cdc-form-item>

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

        <!-- 数据库输入 -->
        <template v-else-if="node.type === 'DB_INPUT'">
          <cdc-form-item label="源连接" required>
            <cdc-select :model-value="draft.sourceId" filterable placeholder="选择源连接"
                        @update:model-value="onSourceChange">
              <cdc-option v-for="item in sources" :key="item.id" :label="item.name" :value="item.id"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="数据库">
            <cdc-select :model-value="draft.database" filterable clearable placeholder="默认库"
                        @update:model-value="onDatabaseChange">
              <cdc-option v-for="name in databases" :key="name" :label="name" :value="name"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="表" required>
            <cdc-select :model-value="draft.table" filterable placeholder="选择表"
                        @update:model-value="onTableChange">
              <cdc-option v-for="item in tables" :key="item.table" :label="`${item.schema || ''}${item.schema ? '.' : ''}${item.table}`" :value="item.table"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item v-if="tableMeta" label="读取字段">
            <cdc-select :model-value="draft.columns" multiple filterable placeholder="全部字段"
                        @update:model-value="value => (draft.columns = value)">
              <cdc-option v-for="column in tableMeta.columns" :key="column" :label="column" :value="column"/>
            </cdc-select>
            <div class="form-help">留空表示读取全部字段</div>
          </cdc-form-item>
          <cdc-form-item label="过滤条件">
            <cdc-input v-model="draft.where" placeholder="例如 amount > 100"/>
            <div class="form-help">直接拼进 WHERE，不要写 WHERE 关键字</div>
          </cdc-form-item>
        </template>

        <!-- SQL 输入 -->
        <template v-else-if="node.type === 'SQL_INPUT'">
          <cdc-form-item label="源连接" required>
            <cdc-select :model-value="draft.sourceId" filterable placeholder="选择源连接"
                        @update:model-value="onSourceChange">
              <cdc-option v-for="item in sources" :key="item.id" :label="item.name" :value="item.id"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="数据库">
            <cdc-select :model-value="draft.database" filterable clearable placeholder="默认库"
                        @update:model-value="onDatabaseChange">
              <cdc-option v-for="name in databases" :key="name" :label="name" :value="name"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="查询 SQL" required>
            <textarea v-model="draft.sql" class="nd-sql" rows="10"
                      placeholder="SELECT id, amount FROM orders WHERE amount > 100"/>
            <div class="form-help">只支持单条 SELECT / WITH 查询</div>
          </cdc-form-item>
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
                <cdc-input v-model="draft.alias" placeholder="例如 含税金额"/>
              </cdc-form-item>
              <cdc-form-item label="表达式" required>
                <cdc-input v-model="draft.expression" placeholder="例如 amount * 1.13"/>
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
                <cdc-input v-model="draft.separator" placeholder="例如 -"/>
              </cdc-form-item>
              <cdc-form-item label="新列名" required>
                <cdc-input v-model="draft.alias" placeholder="例如 省市"/>
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
              <div v-for="(key, index) in joinKeys()" :key="index" class="nd-join-key">
                <cdc-select v-model="key.left" filterable placeholder="左表字段">
                  <cdc-option v-for="column in upstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <cdc-select v-model="key.right" filterable placeholder="右表字段">
                  <cdc-option v-for="column in secondUpstreamColumns" :key="column.label"
                              :label="column.label" :value="column.label"/>
                </cdc-select>
                <button class="icon-btn" aria-label="删除关联键" @click="removeJoinKey(index)">✕</button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="addJoinKey">+ 添加关联键</button>
              <div class="form-help">
                按连线顺序：先连的是左表，后连的是右表；右表的关联键不会重复输出。
              </div>
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
                           placeholder="例如 已支付, 未支付"
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
          <cdc-form-item label="目标连接" required>
            <cdc-select :model-value="draft.targetId" filterable placeholder="选择目标连接"
                        @update:model-value="onTargetChange">
              <cdc-option v-for="item in targets" :key="item.id" :label="item.name" :value="item.id"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="目标表" required>
            <cdc-select :model-value="draft.table" filterable placeholder="选择目标表"
                        @update:model-value="onTargetTableChange">
              <cdc-option v-for="item in targetTables" :key="item.table" :label="item.table" :value="item.table"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="写入模式">
            <cdc-select :model-value="draft.mode || 'OVERWRITE'"
                        @update:model-value="value => (draft.mode = value)">
              <cdc-option v-for="item in MODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item v-if="draft.mode === 'UPSERT'" label="主键列">
            <cdc-select :model-value="draft.keyColumns" multiple filterable placeholder="选择主键列"
                        @update:model-value="value => (draft.keyColumns = value)">
              <cdc-option v-for="column in (targetMeta?.columns || [])" :key="column"
                          :label="column" :value="column"/>
            </cdc-select>
          </cdc-form-item>
          <cdc-form-item label="目标列">
            <cdc-select :model-value="draft.targetColumns" multiple filterable placeholder="与上游列一一对应">
              <cdc-option v-for="column in (targetMeta?.columns || [])" :key="column"
                          :label="column" :value="column"/>
            </cdc-select>
            <div class="form-help">顺序必须与上游列一致；留空则按上游列名写入</div>
          </cdc-form-item>
        </template>
      </cdc-form>

      <p v-if="error" class="nd-error">{{ error }}</p>
      <p v-if="loading" class="nd-loading">加载中…</p>

      <section v-if="preview" class="nd-preview">
        <header>
          <strong>预览</strong>
          <span class="dim">{{ preview.rows.length }} 行{{ preview.truncated ? `（共 ${preview.rowsRead} 行，仅显示前 100 行）` : '' }}</span>
        </header>
        <p v-if="preview.note" class="form-help">{{ preview.note }}</p>
        <div class="nd-preview-table">
          <table>
            <thead><tr><th v-for="column in preview.columns" :key="column">{{ column }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in preview.rows" :key="index">
                <td v-for="(cell, columnIndex) in row" :key="columnIndex" class="mono">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <footer class="nd-foot">
      <button class="btn btn-ghost danger-text nd-delete" :disabled="busy" @click="emit('delete')">删除节点</button>
      <button class="btn btn-ghost" :disabled="busy" @click="emit('preview', { config: draft, name: nodeName })">预览</button>
      <button class="btn btn-primary" :disabled="busy" @click="apply">应用</button>
    </footer>
  </aside>
</template>
