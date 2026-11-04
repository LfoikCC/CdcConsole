<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage } from '../ui/feedback'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  sql: { type: String, default: '' },
  sourceId: { type: [Number, String], default: null },
  dbType: { type: String, default: 'MYSQL' }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const localSql = ref('')
const tables = ref([])
const loadingTables = ref(false)
const tableQuery = ref('')
const category = ref('全部')
const expanded = ref(false)
const editor = ref(null)
const gutter = ref(null)

const FUNCTIONS = {
  SQLSERVER: ['GETDATE', 'DATEADD', 'DATEDIFF', 'DATEPART', 'CONVERT', 'CAST', 'ISNULL', 'COALESCE', 'LEN', 'SUBSTRING', 'REPLACE', 'ROUND', 'ABS', 'COUNT', 'SUM', 'AVG', 'MAX', 'MIN', 'ROW_NUMBER', 'FORMAT'],
  MYSQL: ['NOW', 'CURDATE', 'DATE_ADD', 'DATE_SUB', 'DATEDIFF', 'DATE_FORMAT', 'IFNULL', 'COALESCE', 'CONCAT', 'SUBSTRING', 'REPLACE', 'ROUND', 'ABS', 'COUNT', 'SUM', 'AVG', 'MAX', 'MIN', 'ROW_NUMBER'],
  STARROCKS: ['NOW', 'CURRENT_DATE', 'DATE_ADD', 'DATE_SUB', 'DATEDIFF', 'DATE_FORMAT', 'IFNULL', 'COALESCE', 'CONCAT', 'SUBSTRING', 'REPLACE', 'ROUND', 'ABS', 'COUNT', 'SUM', 'AVG', 'MAX', 'MIN', 'ROW_NUMBER']
}
const functions = computed(() => FUNCTIONS[props.dbType] || FUNCTIONS.MYSQL)
const categories = computed(() => ['全部', ...new Set(functions.value.map(name => name.startsWith('DATE') ? '日期' : ['COUNT', 'SUM', 'AVG', 'MAX', 'MIN'].includes(name) ? '聚合' : ['COALESCE', 'IFNULL', 'ISNULL'].includes(name) ? '空值' : ['SUBSTRING', 'REPLACE', 'CONCAT', 'LEN', 'ROUND', 'ABS', 'FORMAT', 'CAST', 'CONVERT'].includes(name) ? '字符串/数值' : '其他'))])
const visibleFunctions = computed(() => category.value === '全部' ? functions.value : functions.value.filter(name => {
  const cat = name.startsWith('DATE') ? '日期' : ['COUNT', 'SUM', 'AVG', 'MAX', 'MIN'].includes(name) ? '聚合' : ['COALESCE', 'IFNULL', 'ISNULL'].includes(name) ? '空值' : ['SUBSTRING', 'REPLACE', 'CONCAT', 'LEN', 'ROUND', 'ABS', 'FORMAT', 'CAST', 'CONVERT'].includes(name) ? '字符串/数值' : '其他'
  return cat === category.value
}))
const visibleTables = computed(() => {
  const q = tableQuery.value.trim().toLowerCase()
  return tables.value.filter(item => !q || `${item.schema || ''}.${item.table}`.toLowerCase().includes(q))
})
const lines = computed(() => (localSql.value || '').split('\n').length)

watch(() => props.modelValue, open => {
  if (!open) return
  localSql.value = props.sql || ''
  expanded.value = false
  category.value = '全部'
  tableQuery.value = ''
  loadTables()
  nextTick(() => {
    editor.value?.focus()
    if (editor.value) editor.value.selectionStart = editor.value.selectionEnd = localSql.value.length
  })
})
watch(() => props.sourceId, () => { if (props.modelValue) loadTables() })

async function loadTables() {
  tables.value = []
  if (!props.sourceId) return
  loadingTables.value = true
  try {
    tables.value = await api(`/sources/${props.sourceId}/tables`) || []
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loadingTables.value = false
  }
}

function close() { emit('update:modelValue', false) }
function confirm() { emit('confirm', localSql.value); close() }

function insertText(text, spaced = true) {
  const el = editor.value
  if (!el) { localSql.value += text; return }
  const start = el.selectionStart ?? localSql.value.length
  const end = el.selectionEnd ?? start
  const prefix = spaced && start > 0 && !/\s/.test(localSql.value[start - 1]) ? ' ' : ''
  const suffix = spaced && end < localSql.value.length && !/\s/.test(localSql.value[end]) ? ' ' : ''
  const inserted = prefix + text + suffix
  localSql.value = localSql.value.slice(0, start) + inserted + localSql.value.slice(end)
  nextTick(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + inserted.length
  })
}

function useFunction(name) { insertText(`${name}(`) }
function useTable(item) {
  const qualified = item.schema ? `${item.schema}.${item.table}` : item.table
  if (!localSql.value.trim()) {
    localSql.value = `SELECT *\nFROM ${qualified}`
    nextTick(() => editor.value?.focus())
  } else {
    insertText(qualified)
  }
}

function formatSql() {
  let text = localSql.value
  const keywords = ['SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'JOIN', 'ON', 'WITH', 'UNION ALL', 'UNION', 'AS', 'AND', 'OR', 'LIMIT', 'OFFSET', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END']
  for (const keyword of keywords) {
    text = text.replace(new RegExp(`\\b${keyword.replace(' ', '\\s+')}\\b`, 'gi'), keyword)
  }
  text = text.replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|HAVING|INNER JOIN|LEFT JOIN|RIGHT JOIN|JOIN|UNION ALL|UNION|LIMIT)\s+/gi,
    (_, keyword) => `\n${keyword.toUpperCase()} `)
  localSql.value = text.trim()
}

function syncScroll() { if (gutter.value) gutter.value.scrollTop = editor.value.scrollTop }
</script>

<template>
  <cdc-modal :model-value="modelValue" title="SQL 查询" width="min(1180px,97vw)" @update:model-value="emit('update:modelValue', $event)">
    <div class="sql-dialog" :class="{ expanded }">
      <section class="sql-pane">
        <div class="pane-title">函数</div>
        <cdc-select v-model="category" size="small" class="fn-category">
          <cdc-option v-for="item in categories" :key="item" :label="item" :value="item"/>
        </cdc-select>
        <div class="pane-list">
          <button v-for="name in visibleFunctions" :key="name" class="fn-item" @click="useFunction(name)">{{ name }}</button>
        </div>
      </section>

      <section class="sql-pane">
        <div class="pane-title">表目录<span class="required">*</span></div>
        <div class="table-search">
          <Search :size="13"/>
          <input v-model="tableQuery" placeholder="请输入"/>
        </div>
        <div class="pane-list">
          <div v-if="!sourceId" class="pane-empty">请先选择数据账户</div>
          <div v-else-if="loadingTables" class="pane-empty">加载表清单…</div>
          <div v-else-if="!visibleTables.length" class="pane-empty">没有匹配的表</div>
          <button v-for="item in visibleTables" :key="(item.schema || '') + item.table" class="table-item" @click="useTable(item)">
            <span class="table-icon">▦</span>
            <span>{{ item.table }}</span>
            <small v-if="item.schema">{{ item.schema }}</small>
          </button>
        </div>
      </section>

      <section class="sql-pane editor-pane">
        <div class="pane-title">SQL 查询<span class="required">*</span></div>
        <div class="editor-toolbar">
          <button class="tool" @click="formatSql">格式化</button>
          <button class="tool" @click="expanded = !expanded">{{ expanded ? '还原' : '放大' }}</button>
        </div>
        <div class="editor-wrap">
          <pre ref="gutter" class="gutter"><span v-for="n in lines" :key="n">{{ n }}</span></pre>
          <textarea ref="editor" v-model="localSql" class="editor" spellcheck="false"
                    placeholder="SELECT * FROM ..." @scroll="syncScroll"></textarea>
        </div>
        <div class="editor-help">点击左侧函数或中间的表，会插入到光标位置；只支持单条 SELECT / WITH 查询</div>
      </section>
    </div>

    <template #footer>
      <cdc-button @click="close">取消</cdc-button>
      <cdc-button type="primary" @click="confirm">确定</cdc-button>
    </template>
  </cdc-modal>
</template>

<style scoped>
.sql-dialog { display:grid; grid-template-columns:190px 250px minmax(0,1fr); gap:12px; height:min(60vh,520px); min-height:360px; }
.sql-dialog.expanded { height:78vh; }
.sql-pane { display:flex; flex-direction:column; min-width:0; border:1px solid var(--border); border-radius:8px; background:var(--card); overflow:hidden; }
.pane-title { padding:10px 12px 8px; font-size:12px; font-weight:600; color:var(--fg); }
.required { color:var(--danger); margin-left:2px; }
.fn-category { margin:0 10px 8px; }
.pane-list { flex:1; overflow:auto; padding:0 8px 8px; }
.fn-item, .table-item { display:flex; align-items:center; gap:8px; width:100%; padding:7px 8px; border:0; border-radius:6px; background:transparent; color:var(--fg); font-size:12.5px; text-align:left; cursor:pointer; }
.fn-item { font-family:var(--mono); }
.fn-item:hover, .table-item:hover { background:var(--muted); }
.table-item small { margin-left:auto; color:var(--muted-fg); font-size:10.5px; }
.table-icon { color:var(--muted-fg); }
.table-search { display:flex; align-items:center; gap:6px; margin:0 10px 8px; padding:5px 8px; border:1px solid var(--border); border-radius:6px; color:var(--muted-fg); }
.table-search input { flex:1; border:0; outline:0; background:transparent; color:var(--fg); font-size:12px; }
.pane-empty { padding:16px 8px; color:var(--muted-fg); font-size:12px; text-align:center; }
.editor-pane { position:relative; }
.editor-toolbar { display:flex; justify-content:flex-end; gap:6px; padding:0 10px 6px; }
.tool { border:0; background:transparent; color:var(--muted-fg); font-size:11.5px; cursor:pointer; padding:3px 6px; border-radius:4px; }
.tool:hover { background:var(--muted); color:var(--fg); }
.editor-wrap { flex:1; display:flex; min-height:0; border-top:1px solid var(--border); }
.gutter { width:40px; margin:0; padding:10px 6px; overflow:hidden; text-align:right; color:var(--muted-fg); font:12px/1.7 var(--mono); background:var(--muted); user-select:none; }
.gutter span { display:block; }
.editor { flex:1; min-width:0; padding:10px 12px; border:0; outline:0; resize:none; background:var(--card); color:var(--fg); font:12.5px/1.7 var(--mono); tab-size:2; }
.editor-help { padding:6px 10px; border-top:1px solid var(--border); color:var(--muted-fg); font-size:11px; }
</style>
