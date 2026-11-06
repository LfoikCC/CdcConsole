<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Calendar, FunctionSquare, Hash, Search, Type } from 'lucide-vue-next'
import { CdcMessage } from '../ui/feedback'

/**
 * 计算字段编辑器。
 *
 * <p>表达式里用 <code>{字段名}</code> 引用数据集字段：保存的是展示名，后端在拼 SQL 时
 * 再换成物理列名（Excel / 离线数据集的物理列是 C1..Cn，写死物理名换数据集就废了）。
 * 允许在里面直接写聚合函数，例如 {@code SUM({金额}) + SUM({税额})}。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 数据集字段：[{ name, dataType, numeric }] */
  fields: { type: Array, default: () => [] },
  /** 已经存在的计算字段，用来查重名 */
  computed: { type: Array, default: () => [] },
  /** 编辑已有计算字段时传 { index, name, type, expr } */
  editing: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue', 'submit', 'remove'])

const FUNCTIONS = [
  { name: 'SUM', group: '聚合', signature: 'SUM(数值)', hint: '求和' },
  { name: 'AVG', group: '聚合', signature: 'AVG(数值)', hint: '平均值' },
  { name: 'COUNT', group: '聚合', signature: 'COUNT(字段)', hint: '计数' },
  { name: 'COUNT_DISTINCT', group: '聚合', signature: 'COUNT(DISTINCT 字段)', hint: '去重计数' },
  { name: 'MIN', group: '聚合', signature: 'MIN(字段)', hint: '最小值' },
  { name: 'MAX', group: '聚合', signature: 'MAX(字段)', hint: '最大值' },
  { name: 'ABS', group: '数学', signature: 'ABS(数值)', hint: '绝对值' },
  { name: 'CEIL', group: '数学', signature: 'CEIL(数值)', hint: '向上取整' },
  { name: 'FLOOR', group: '数学', signature: 'FLOOR(数值)', hint: '向下取整' },
  { name: 'ROUND', group: '数学', signature: 'ROUND(数值, 小数位)', hint: '四舍五入' },
  { name: 'MOD', group: '数学', signature: 'MOD(数值, 除数)', hint: '取余' },
  { name: 'CONCAT', group: '文本', signature: 'CONCAT(字段, 字段)', hint: '拼接文本' },
  { name: 'LENGTH', group: '文本', signature: 'LENGTH(字段)', hint: '字符长度' },
  { name: 'LOWER', group: '文本', signature: 'LOWER(字段)', hint: '转小写' },
  { name: 'UPPER', group: '文本', signature: 'UPPER(字段)', hint: '转大写' },
  { name: 'SUBSTR', group: '文本', signature: 'SUBSTR(字段, 起点, 长度)', hint: '截取文本' },
  { name: 'TRIM', group: '文本', signature: 'TRIM(字段)', hint: '去掉首尾空格' },
  { name: 'REPLACE', group: '文本', signature: 'REPLACE(字段, 原值, 新值)', hint: '替换文本' },
  { name: 'COALESCE', group: '文本', signature: 'COALESCE(字段, 默认值)', hint: '空值取默认' },
  { name: 'IFNULL', group: '文本', signature: 'IFNULL(字段, 默认值)', hint: '空值取默认（MySQL）' },
  { name: 'NULLIF', group: '文本', signature: 'NULLIF(表达式, 值)', hint: '相等时返回空' },
  { name: 'DATE', group: '日期', signature: 'DATE(字段)', hint: '截取日期' },
  { name: 'YEAR', group: '日期', signature: 'YEAR(字段)', hint: '取年份' },
  { name: 'MONTH', group: '日期', signature: 'MONTH(字段)', hint: '取月份' },
  { name: 'DAY', group: '日期', signature: 'DAY(字段)', hint: '取日' },
  { name: 'DATE_FORMAT', group: '日期', signature: 'DATE_FORMAT(字段, 格式)', hint: '格式化日期' },
  { name: 'DATEDIFF', group: '日期', signature: 'DATEDIFF(结束, 开始)', hint: '相差天数' },
  { name: 'CASE', group: '逻辑', signature: 'CASE WHEN 条件 THEN 值 ELSE 值 END', hint: '条件取值' },
  { name: 'CASE_WHEN', group: '逻辑', signature: 'CASE 字段 WHEN 值 THEN 值 ELSE 值 END', hint: '按值分支' }
]
const GROUPS = ['全部', '聚合', '数学', '文本', '日期', '逻辑']
const TYPES = [
  { value: 'NUMBER', label: '数值' },
  { value: 'TEXT', label: '文本' },
  { value: 'DATE', label: '日期' }
]

const name = ref('')
const type = ref('')
const expr = ref('')
const group = ref('全部')
const functionKeyword = ref('')
const fieldKeyword = ref('')
const tab = ref('fields')
const fnSearchOpen = ref(false)
const fnSearchInput = ref(null)
const editor = ref(null)
const gutter = ref(null)

const title = computed(() => (props.editing ? '编辑计算字段' : '计算字段编辑器'))

const visibleFunctions = computed(() => FUNCTIONS.filter(item => {
  const matchGroup = group.value === '全部' || item.group === group.value
  const key = functionKeyword.value.trim().toLowerCase()
  return matchGroup && (!key || item.name.toLowerCase().includes(key))
}))

const visibleFields = computed(() => props.fields.filter(item => {
  const key = fieldKeyword.value.trim().toLowerCase()
  return !key || item.name.toLowerCase().includes(key)
}))

const lineCount = computed(() => (expr.value || '').split('\n').length)

watch(() => props.modelValue, open => {
  if (!open) return
  name.value = props.editing?.name || ''
  type.value = props.editing?.type || ''
  expr.value = props.editing?.expr || ''
  group.value = '全部'
  functionKeyword.value = ''
  fieldKeyword.value = ''
  tab.value = 'fields'
  fnSearchOpen.value = false
  nextTick(() => editor.value?.focus())
}, { immediate: true })

/** 搜索框默认收起：点标题栏的放大镜才展开，展开后直接把焦点放进去。 */
function toggleFunctionSearch() {
  fnSearchOpen.value = !fnSearchOpen.value
  if (!fnSearchOpen.value) {
    functionKeyword.value = ''
    return
  }
  nextTick(() => fnSearchInput.value?.focus())
}

function fieldIcon(field) {
  if (field.numeric) return Hash
  if (/DATE|TIME/i.test(String(field.dataType || ''))) return Calendar
  return Type
}

/** 光标处插入：先记下选区，插完再还原到插入内容之后。 */
function insertText(text) {
  const el = editor.value
  if (!el) {
    expr.value += text
    return
  }
  const start = el.selectionStart ?? expr.value.length
  const end = el.selectionEnd ?? start
  expr.value = expr.value.slice(0, start) + text + expr.value.slice(end)
  nextTick(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + text.length
  })
}

function useFunction(item) {
  insertText(`${item.name}(`)
}

function useField(field) {
  insertText(`{${field.name}}`)
}

function formatSql() {
  let text = expr.value.trim()
  if (!text) return
  const keywords = ['CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'AND', 'OR', 'NOT', 'NULL',
    'SUM', 'COUNT', 'AVG', 'MIN', 'MAX', 'ABS', 'CEIL', 'FLOOR', 'ROUND', 'COALESCE', 'IFNULL',
    'CONCAT', 'REPLACE', 'SUBSTR', 'TRIM', 'UPPER', 'LOWER', 'DATE', 'YEAR', 'MONTH', 'DAY']
  for (const keyword of keywords) {
    text = text.replace(new RegExp(`\\b${keyword}\\b`, 'gi'), keyword)
  }
  text = text.replace(/\s+/g, ' ')
  text = text.replace(/\s*([+\-*/])\s*/g, ' $1 ')
  text = text.replace(/\s*,\s*/g, ', ')
  expr.value = text.trim()
}

function syncScroll() {
  if (gutter.value && editor.value) gutter.value.scrollTop = editor.value.scrollTop
}

/** 整个编辑框都是输入区：点到行号、下方空白都落到 textarea 末尾。 */
function focusEditor(event) {
  const el = editor.value
  if (!el || event?.target === el) return
  el.focus()
  const end = el.value.length
  el.selectionStart = el.selectionEnd = end
}

function close() {
  emit('update:modelValue', false)
}

function submit() {
  const fieldName = name.value.trim()
  if (!fieldName) {
    CdcMessage.warning('请填写字段名称')
    return
  }
  const clash = props.fields.some(item => item.name === fieldName)
    || props.computed.some((item, index) => item.name === fieldName && index !== props.editing?.index)
  if (clash) {
    CdcMessage.warning(`已经有同名字段：${fieldName}`)
    return
  }
  if (!type.value) {
    CdcMessage.warning('请选择字段类型')
    return
  }
  const expression = expr.value.trim()
  if (!expression) {
    CdcMessage.warning('请填写计算表达式')
    return
  }
  // 占位符必须能在数据集里找到，否则要等到预览才报错
  const missing = [...expression.matchAll(/\{([^{}]+)}/g)]
    .map(match => match[1].trim())
    .filter(reference => !props.fields.some(item => item.name === reference))
  if (missing.length) {
    CdcMessage.warning(`表达式里引用了不存在的字段：${[...new Set(missing)].join('、')}`)
    return
  }
  emit('submit', {
    index: props.editing?.index ?? null,
    name: fieldName,
    type: type.value,
    expr: expression
  })
  close()
}

function remove() {
  emit('remove', props.editing?.index)
  close()
}
</script>

<template>
  <cdc-modal :model-value="modelValue" :title="title" width="min(960px,96vw)"
             @update:model-value="emit('update:modelValue', $event)">
    <template #subtitle>请使用对应数据库的SQL语法</template>

    <div class="cf-dialog">
      <div class="cf-form">
        <label class="cf-form-item">
          <span class="cf-label">字段名称<span class="req">*</span></span>
          <input v-model="name" class="cf-input" placeholder="请输入名称" maxlength="60"/>
        </label>
        <label class="cf-form-item">
          <span class="cf-label">字段类型<span class="req">*</span></span>
          <cdc-select v-model="type" placeholder="请选择">
            <cdc-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value"/>
          </cdc-select>
        </label>
      </div>

      <div class="cf-panes">
        <section class="cf-pane">
          <header class="cf-pane-head">
            <span>函数</span>
            <button type="button" class="cf-pane-icon" :class="{ 'is-active': fnSearchOpen }"
                    title="搜索函数" aria-label="搜索函数" @click="toggleFunctionSearch">
              <Search :size="12"/>
            </button>
          </header>
          <div class="cf-pane-tools">
            <cdc-select v-model="group" size="small" class="cf-group">
              <cdc-option v-for="item in GROUPS" :key="item" :label="item" :value="item"/>
            </cdc-select>
          </div>
          <div v-if="fnSearchOpen" class="cf-pane-tools">
            <div class="cf-mini-search">
              <Search :size="11"/>
              <input ref="fnSearchInput" v-model="functionKeyword" placeholder="搜索函数" aria-label="搜索函数"/>
            </div>
          </div>
          <div class="cf-list">
            <button v-for="item in visibleFunctions" :key="item.name" type="button" class="cf-fn"
                    :title="`${item.signature} · ${item.hint}`" @click="useFunction(item)">
              {{ item.name }}
            </button>
            <div v-if="!visibleFunctions.length" class="cf-empty">没有匹配的函数</div>
          </div>
        </section>

        <section class="cf-pane">
          <header class="cf-pane-head cf-tabs">
            <button type="button" :class="{ active: tab === 'fields' }" @click="tab = 'fields'">字段</button>
            <button type="button" :class="{ active: tab === 'params' }" @click="tab = 'params'">参数</button>
          </header>

          <template v-if="tab === 'fields'">
            <div class="cf-pane-tools">
              <div class="cf-mini-search cf-wide-search">
                <Search :size="11"/>
                <input v-model="fieldKeyword" placeholder="搜索" aria-label="搜索字段"/>
              </div>
            </div>
            <div class="cf-list">
              <button v-for="field in visibleFields" :key="field.name" type="button" class="cf-field"
                      :title="field.dataType || ''" @click="useField(field)">
                <span class="cf-field-icon" :class="{ 'is-num': field.numeric }">
                  <component :is="fieldIcon(field)" :size="11"/>
                </span>
                <span>{{ field.name }}</span>
              </button>
              <div v-if="!visibleFields.length" class="cf-empty">没有匹配的字段</div>
            </div>
          </template>

          <template v-else>
            <!-- 参数留给以后系统级的全局参数（如 ${日期}、${用户}），现在没有内容 -->
            <div class="cf-list">
              <div class="cf-empty">暂无全局参数</div>
            </div>
          </template>
        </section>

        <section class="cf-pane cf-editor-pane">
          <header class="cf-pane-head">
            <span>参数</span>
            <button type="button" class="cf-format" @click="formatSql">
              <FunctionSquare :size="11"/>格式化
            </button>
          </header>
          <div class="cf-editor" @click="focusEditor">
            <pre ref="gutter" class="cf-gutter"><span v-for="n in lineCount" :key="n">{{ n }}</span></pre>
            <textarea ref="editor" v-model="expr" class="cf-code" spellcheck="false"
                      placeholder="字段用 {字段名} 引用，请使用英文标点符号" @scroll="syncScroll"/>
          </div>
        </section>
      </div>
    </div>

    <template #footer>
      <button v-if="editing" type="button" class="btn btn-ghost danger-text cf-remove" @click="remove">删除</button>
      <cdc-button @click="close">取消</cdc-button>
      <cdc-button type="primary" @click="submit">确定</cdc-button>
    </template>
  </cdc-modal>
</template>
