<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeftRight, ArrowUpDown, ChevronDown, ChevronRight, Filter, Hash, Search, Type } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage } from '../ui/feedback'
import { AGG_FUNCTIONS, CARD_TYPES, COMPARATORS, DATASET_LABELS, MAX_LIMIT, NO_AGG, filterLabel } from './biCards'
import BiCardView from './BiCardView.vue'

/**
 * 卡片编辑器：数据集 → 绘制 → 预览 → 可视化 四栏。
 *
 * <p>交互参照观远：字段在左栏，拖到「维度 / 数值 / 筛选 / 排序」四个落点里；
 * 中间实时预览，右栏换图形和调属性。点击字段也能加入（拖拽在触屏上不一定好使）。
 */
const props = defineProps({
  card: { type: Object, required: true },
  datasets: { type: Array, default: () => [] }
})

const emit = defineEmits(['change'])

const detail = ref(null)
const loadingDetail = ref(false)
/** 数据集真实行数：库表数据集的元数据里是 0，打开编辑器时现算一次 */
const rowCount = ref(null)
const switchOpen = ref(false)
const switchTarget = ref(null)
const keyword = ref('')
const dragging = ref(null)
const hoverZone = ref('')

// 字段菜单：点茶壶展开，别名/汇总方式/移除都收在这里
const menu = ref({ open: false, kind: '', index: -1, left: 0, top: 0 })
/** 菜单里的二级面板：'' | 'agg' | 'sort' */
const submenu = ref('')
/** 列取值缓存：筛选的可选值、自定义排序的候选值都来自它 */
const valueCache = ref({})
const valueLoading = ref(false)

const FILTER_KINDS = [
  { value: 'select', label: '选择' },
  { value: 'range', label: '范围' },
  { value: 'compare', label: '条件' }
]

const preview = ref(null)
const previewLoading = ref(false)

// 绘制栏宽度：默认和左侧字段栏一致，可以拖右边调整，记在本机下次打开还是这么宽
const DRAW_MIN = 200
const DRAW_MAX = 520
const DRAW_DEFAULT = 236
const drawWidth = ref(DRAW_DEFAULT)
const resizing = ref(false)
let resizeOrigin = null
let previewTimer = null
let previewSeq = 0

const dataset = computed(() => props.datasets.find(item => item.id === props.card.datasetId) || null)
const config = computed(() => props.card.config || {})
const dimensions = computed(() => config.value.dimensions || [])
const metrics = computed(() => config.value.metrics || [])
const filters = computed(() => config.value.filters || [])
const dimensionLabels = computed(() => dimensions.value.map(item => item.column))
const singleDimension = computed(() => props.card.type !== 'table')
const maxMetrics = computed(() => {
  if (props.card.type === 'table') return 8
  return (props.card.type === 'bar' || props.card.type === 'line') ? 4 : 1
})
const supportsAxis = computed(() => props.card.type === 'bar' || props.card.type === 'line')

const fields = computed(() => (detail.value?.columns || [])
  .filter(item => item.name)
  .filter(item => !keyword.value.trim() || item.name.toLowerCase().includes(keyword.value.trim().toLowerCase()))
  .map(item => ({ name: item.name, dataType: item.dataType, numeric: isNumeric(item.dataType) })))
const dimensionFields = computed(() => fields.value.filter(item => !item.numeric))
const measureFields = computed(() => fields.value.filter(item => item.numeric))

watch(() => props.card.datasetId, loadDetail, { immediate: true })
watch(() => [props.card.type, props.card.datasetId, JSON.stringify(props.card.config)], schedulePreview)

onMounted(() => {
  const saved = Number(localStorage.getItem('cdc.bi.drawWidth'))
  if (saved >= DRAW_MIN && saved <= DRAW_MAX) drawWidth.value = saved
  // 打开已有卡片时配置没有变化，watch 不会触发，得主动取一次数，否则预览一直是空的
  if (props.card.datasetId != null) runPreview()
  document.addEventListener('pointerdown', onDocPointerDown)
  document.addEventListener('keydown', onDocKeydown)
  window.addEventListener('scroll', onWindowScroll, true)
})
function startResize(event) {
  event.preventDefault()
  resizeOrigin = { x: event.clientX, width: drawWidth.value }
  resizing.value = true
  // 指针捕获只是让拖动更稳，某些环境（或合成事件）拿不到有效 pointerId，忽略即可
  try {
    event.currentTarget?.setPointerCapture?.(event.pointerId)
  } catch {
    // ignore
  }
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', stopResize, { once: true })
}

function onResizeMove(event) {
  if (!resizeOrigin) return
  drawWidth.value = Math.max(DRAW_MIN, Math.min(DRAW_MAX, resizeOrigin.width + event.clientX - resizeOrigin.x))
}

function stopResize() {
  resizeOrigin = null
  resizing.value = false
  window.removeEventListener('pointermove', onResizeMove)
  try {
    localStorage.setItem('cdc.bi.drawWidth', String(drawWidth.value))
  } catch {
    // 隐私模式下 localStorage 可能不可写，宽度不记住即可
  }
}

onBeforeUnmount(() => {
  clearTimeout(previewTimer)
  window.removeEventListener('pointermove', onResizeMove)
  document.removeEventListener('pointerdown', onDocPointerDown)
  document.removeEventListener('keydown', onDocKeydown)
  window.removeEventListener('scroll', onWindowScroll, true)
})

const menuDimension = computed(() => (menu.value.open && menu.value.kind === 'dimension'
  ? dimensions.value[menu.value.index] : null))
const menuMetric = computed(() => (menu.value.open && menu.value.kind === 'metric'
  ? metrics.value[menu.value.index] : null))
const menuFilter = computed(() => (menu.value.open && menu.value.kind === 'filter'
  ? filters.value[menu.value.index] : null))

function isMenu(kind, index) {
  return menu.value.open && menu.value.kind === kind && menu.value.index === index
}

function toggleMenu(event, kind, index) {
  if (isMenu(kind, index)) {
    menu.value.open = false
    return
  }
  const rect = event.currentTarget.getBoundingClientRect()
  menu.value = {
    open: true, kind, index,
    left: Math.max(8, Math.min(rect.left, window.innerWidth - 212)),
    // 菜单比茶壶高得多，底部留出空间，免得贴着屏幕下沿被截断
    top: Math.min(rect.bottom + 4, Math.max(8, window.innerHeight - 400))
  }
  submenu.value = ''
  if (kind === 'filter') {
    filterSearch.value = ''
    loadValues(filters.value[index]?.column)
  }
  if (kind === 'sort') loadValues(sortDimension.value?.column)
}

/** 拉一列的候选值，按列缓存（同一张卡片里反复开菜单不重复查库）。 */
async function loadValues(column) {
  if (!column || props.card.datasetId == null || valueCache.value[column]) return
  valueLoading.value = true
  try {
    const result = await api(`/bi/datasets/${props.card.datasetId}/values`
      + `?column=${encodeURIComponent(column)}&limit=200`)
    valueCache.value = { ...valueCache.value, [column]: result.values || [] }
  } catch {
    valueCache.value = { ...valueCache.value, [column]: [] }
  } finally {
    valueLoading.value = false
  }
}

function closeMenu() {
  menu.value.open = false
}

// 菜单里可能还开着下拉弹层：滚动弹层不能把整个菜单关掉（否则滚一下就消失）
function onWindowScroll(event) {
  if (!menu.value.open) return
  const target = event.target
  if (target?.closest?.('.select-popover, .bi-pill-menu')) return
  closeMenu()
}

function onDocKeydown(event) {
  if (event.key === 'Escape' && menu.value.open) {
    event.stopPropagation()
    closeMenu()
  }
}

function onDocPointerDown(event) {
  if (!menu.value.open) return
  // 下拉弹层也 teleport 到 body：点它的选项不能把整个字段菜单关掉，否则选不中
  if (event.target?.closest?.('.bi-pill-menu, .bi-pill, .select-popover')) return
  menu.value.open = false
}

function aggLabel(value) {
  return AGG_FUNCTIONS.find(item => item.value === value)?.label || ''
}

function setMetricAgg(value) {
  if (!menuMetric.value) return
  menuMetric.value.agg = value
  changed()
}

// -------------------------------------------------------------- 筛选类型

function filterKind(filter) {
  if (filter?.op === 'IN' || filter?.op === 'NOT IN') return 'select'
  if (filter?.op === 'BETWEEN') return 'range'
  return 'compare'
}

/** 切换筛选类型时把 op / value 调整成对应的形状。 */
function setFilterKind(kind) {
  const filter = menuFilter.value
  if (!filter) return
  if (kind === 'select') {
    // 从「条件」切过来时 op 是 =/LIKE，统一换成包含（排除由开关控制）
    filter.op = filter.op === 'NOT IN' ? 'NOT IN' : 'IN'
    filter.value = Array.isArray(filter.value) ? filter.value : []
    loadValues(filter.column)
  } else if (kind === 'range') {
    const current = Array.isArray(filter.value) ? filter.value : []
    filter.op = 'BETWEEN'
    filter.value = [current[0] ?? null, current[1] ?? null]
  } else {
    filter.op = '='
    filter.value = ''
  }
  changed()
}

const filterValues = computed(() => (Array.isArray(menuFilter.value?.value) ? menuFilter.value.value : []))
const selectValues = computed(() => valueCache.value[menuFilter.value?.column] || [])
const filterSearch = ref('')
const visibleSelectValues = computed(() => {
  const keywordText = filterSearch.value.trim().toLowerCase()
  if (!keywordText) return selectValues.value
  return selectValues.value.filter(value => String(value).toLowerCase().includes(keywordText))
})
const allVisibleSelected = computed(() => visibleSelectValues.value.length > 0
  && visibleSelectValues.value.every(value => isPicked(value)))
const isExclude = computed(() => menuFilter.value?.op === 'NOT IN')

const sameValue = (a, b) => String(a) === String(b)

function isPicked(value) {
  return filterValues.value.some(item => sameValue(item, value))
}

/** 数值列传数字：StarRocks / H2 拿字符串跟数值列比会直接报类型错误。 */
function coerceValue(value) {
  if (!menuFilter.value || !numericOf(menuFilter.value.column)) return value
  const parsed = Number(value)
  return Number.isNaN(parsed) ? value : parsed
}

/** 全选只作用于当前搜索结果：搜完再全选，勾上的就是筛出来的那批。 */
function toggleSelectAll() {
  if (!menuFilter.value) return
  const current = filterValues.value
  const visible = visibleSelectValues.value
  if (allVisibleSelected.value) {
    menuFilter.value.value = current.filter(item => !visible.some(value => sameValue(item, value)))
  } else {
    const merged = [...current]
    visible.forEach(value => {
      if (!merged.some(item => sameValue(item, value))) merged.push(coerceValue(value))
    })
    menuFilter.value.value = merged
  }
  changed()
}

function toggleExclude() {
  if (!menuFilter.value) return
  menuFilter.value.op = isExclude.value ? 'IN' : 'NOT IN'
  changed()
}

function toggleFilterValue(value) {
  if (!menuFilter.value) return
  const current = filterValues.value
  menuFilter.value.value = isPicked(value)
    ? current.filter(item => !sameValue(item, value))
    : [...current, coerceValue(value)]
  changed()
}

function rangeValue(index) {
  const list = Array.isArray(menuFilter.value?.value) ? menuFilter.value.value : []
  return list[index] ?? ''
}

function setRange(index, value) {
  if (!menuFilter.value) return
  const list = Array.isArray(menuFilter.value.value) ? [...menuFilter.value.value] : [null, null]
  // 数值列要传数字：H2 拿字符串和 DECIMAL 比会报类型错误
  const numeric = numericOf(menuFilter.value.column)
  const parsed = value === '' || value == null ? null : (numeric ? Number(value) : value)
  list[index] = Number.isNaN(parsed) ? value : parsed
  menuFilter.value.value = list
  changed()
}

async function loadDetail() {
  detail.value = null
  valueCache.value = {}
  rowCount.value = null
  if (props.card.datasetId == null) return
  loadingDetail.value = true
  const datasetId = props.card.datasetId
  try {
    detail.value = await api(`/datasets/${datasetId}`)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loadingDetail.value = false
  }
  // 行数单独取：库表数据集在元数据里没有行数，得现算
  try {
    const stats = await api(`/bi/datasets/${datasetId}/stats`)
    if (props.card.datasetId === datasetId) rowCount.value = stats.rows ?? null
  } catch {
    rowCount.value = null
  }
}

function openDatasetSwitch() {
  switchTarget.value = props.card.datasetId
  switchOpen.value = true
}

/** 换数据集后旧字段全对不上，维度/度量/筛选/排序一起清掉。 */
function applyDatasetSwitch() {
  if (switchTarget.value == null || switchTarget.value === props.card.datasetId) return
  props.card.datasetId = switchTarget.value
  const next = config.value
  next.dimensions = []
  next.metrics = []
  next.filters = []
  next.sort = { by: 'none', index: 0, dir: 'desc' }
  switchOpen.value = false
  closeMenu()
  changed()
}

function isNumeric(type) {
  return /INT|DECIMAL|DOUBLE|NUMERIC|NUMBER|FLOAT|REAL/i.test(String(type || ''))
}

/** 列的类型从数据集原始列里查：左侧搜索框会过滤 fields，不能拿它判断。 */
function numericOf(name) {
  const column = (detail.value?.columns || []).find(item => item.name === name)
  return column ? isNumeric(column.dataType) : true
}

function iconOf(name) {
  return numericOf(name) ? Hash : Type
}

/** 茶壶上的字段名：改过别名就显示别名，数值再带上汇总方式。 */
function metricPill(metric) {
  const name = metric.alias || metric.column
  if (!metric.agg || metric.agg === NO_AGG) return name
  const agg = AGG_FUNCTIONS.find(item => item.value === metric.agg)
  return agg ? `${name}（${agg.label}）` : name
}

function filterPill(filter) {
  return filterLabel(filter)
}

const rowCountText = computed(() => {
  if (rowCount.value != null) return rowCount.value.toLocaleString('zh-CN')
  if (dataset.value?.rowCount) return Number(dataset.value.rowCount).toLocaleString('zh-CN')
  return '—'
})

function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}

function changed() {
  emit('change')
}

// ------------------------------------------------------------------ 拖拽

function startDrag(event, field) {
  dragging.value = field
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
    // 必须写点东西，否则 Firefox 不触发 drop
    event.dataTransfer.setData('text/plain', field.name)
  }
}

function endDrag() {
  dragging.value = null
  hoverZone.value = ''
}

function onDragOver(zone) {
  if (!dragging.value) return
  hoverZone.value = zone
}

function onDrop(zone) {
  const field = dragging.value
  hoverZone.value = ''
  dragging.value = null
  if (!field) return
  // 拖到哪个区就按哪个区处理，不再看字段类型：
  // 维度可以当数值（计数 / 去重计数 / 最大 / 最小），数值也可以不聚合直接当维度
  if (zone === 'dimension') {
    addDimension(field.name)
    return
  }
  if (zone === 'measure') {
    addMetric(field.name)
    return
  }
  if (zone === 'filter') {
    addFilter(field.name)
    return
  }
  if (zone === 'sort') {
    sortByField(field.name)
  }
}

/** 拖进排序的字段：已用在图里就直接排序，还没用过就先加进维度/数值再排。 */
function sortByField(name) {
  const dimensionIndex = dimensionLabels.value.indexOf(name)
  if (dimensionIndex >= 0) {
    setSort({ by: 'dimension', index: dimensionIndex, dir: 'asc' })
    return
  }
  const metricIndex = metrics.value.findIndex(item => item.column === name)
  if (metricIndex >= 0) {
    setSort({ by: 'metric', index: metricIndex, dir: 'desc' })
    return
  }
  if (numericOf(name)) {
    addMetric(name)
    if (metrics.value.some(item => item.column === name)) {
      setSort({ by: 'metric', index: metrics.value.findIndex(item => item.column === name), dir: 'desc' })
    }
  } else {
    addDimension(name)
    if (dimensionLabels.value.includes(name)) {
      setSort({ by: 'dimension', index: dimensionLabels.value.indexOf(name), dir: 'asc' })
    }
  }
}

// ------------------------------------------------------------------ 绘制

function addDimension(name) {
  if (props.card.type === 'kpi') return
  if (dimensionLabels.value.includes(name)) return
  const next = { column: name, alias: '' }
  config.value.dimensions = singleDimension.value
    ? [next]
    : [...dimensions.value.map(item => ({ ...item })), next].slice(0, 8)
  changed()
}

function removeDimension(index) {
  closeMenu()
  config.value.dimensions = dimensions.value.filter((_, position) => position !== index)
  shiftSort('dimension', index)
  changed()
}

/** 删掉一个字段后，排序要么取消、要么把序号往前挪。 */
function shiftSort(by, removed) {
  const sort = config.value.sort
  if (!sort || sort.by !== by) return
  if (sort.index === removed) {
    config.value.sort = { by: 'none', index: 0, dir: 'desc' }
  } else if (sort.index > removed) {
    config.value.sort = { ...sort, index: sort.index - 1 }
  }
}

function addMetric(name) {
  if (metrics.value.length >= maxMetrics.value) return
  const column = name || measureFields.value[0]?.name || fields.value[0]?.name || ''
  // 文本字段求和没有意义，默认按计数；数值才默认求和
  const agg = numericOf(column) ? 'SUM' : 'COUNT'
  config.value.metrics = [...metrics.value, { column, agg, alias: '', axis: 'left' }]
  changed()
}

/**
 * 文本字段没有求和/平均的意义，汇总方式只给 无处理 / 计数 / 去重计数 / 最小值 / 最大值。
 */
function aggOptions(metric) {
  if (!numericOf(metric.column)) {
    return AGG_FUNCTIONS.filter(item => ['NONE', 'COUNT', 'COUNT_DISTINCT', 'MIN', 'MAX'].includes(item.value))
  }
  return AGG_FUNCTIONS
}

function removeMetric(index) {
  closeMenu()
  config.value.metrics = metrics.value.filter((_, position) => position !== index)
  shiftSort('metric', index)
  changed()
}

function toggleAxis(metric) {
  metric.axis = metric.axis === 'right' ? 'left' : 'right'
  changed()
}

function addFilter(column) {
  config.value.filters = [...filters.value, { column: column || fields.value[0]?.name || '', op: '=', value: '' }]
  changed()
}

function removeFilter(index) {
  closeMenu()
  config.value.filters = filters.value.filter((_, position) => position !== index)
  changed()
}

function setSort(patch) {
  config.value.sort = { ...(config.value.sort || { by: 'none', index: 0, dir: 'desc' }), ...patch }
  changed()
}

const sortState = computed(() => config.value.sort || { by: 'none', index: 0, dir: 'desc' })
const sortDimension = computed(() => (sortState.value.by === 'dimension'
  ? dimensions.value[sortState.value.index] : null))
const menuSort = computed(() => menu.value.open && menu.value.kind === 'sort')
const hasSort = computed(() => {
  const sort = sortState.value
  const list = sort.by === 'dimension' ? dimensions.value : sort.by === 'metric' ? metrics.value : []
  return Boolean(list[sort.index])
})
const canCustomSort = computed(() => Boolean(sortDimension.value))
const isCustomSort = computed(() => (sortState.value.custom || []).length > 0)

const sortFieldName = computed(() => {
  const sort = sortState.value
  const list = sort.by === 'dimension' ? dimensions.value : metrics.value
  const item = list[sort.index]
  if (!item) return ''
  return sort.by === 'metric' ? metricPill(item) : (item.alias || item.column)
})

/** 自定义排序面板里的顺序：用户存过的顺序在前，剩下没排的补在后面。 */
const customValues = computed(() => {
  const column = sortDimension.value?.column
  const cached = valueCache.value[column] || []
  const custom = sortState.value.custom || []
  if (!custom.length) return cached
  return [...custom, ...cached.filter(value => !custom.includes(value))]
})

function openCustomSort() {
  submenu.value = 'sort'
  if (!canCustomSort.value) return
  if (!isCustomSort.value && customValues.value.length) {
    config.value.sort = { ...sortState.value, custom: [...customValues.value] }
    changed()
  }
}

function moveCustom(index, delta) {
  const list = [...customValues.value]
  const target = index + delta
  if (target < 0 || target >= list.length) return
  const [item] = list.splice(index, 1)
  list.splice(target, 0, item)
  config.value.sort = { ...sortState.value, custom: list }
  changed()
}

const sortLabel = computed(() => {
  const sort = sortState.value
  const list = sort.by === 'dimension' ? dimensions.value : metrics.value
  const item = list[sort.index]
  if (!item) return ''
  const name = sort.by === 'metric' ? metricPill(item) : (item.alias || item.column)
  if ((sort.custom || []).length) return `${name} 自定义`
  return `${name} ${sort.dir === 'asc' ? '升序' : '降序'}`
})

function setSortDir(dir) {
  // 切回普通升/降序时把自定义顺序清掉
  setSort({ dir, custom: [] })
}

function clearSort() {
  closeMenu()
  setSort({ by: 'none', index: 0, custom: [] })
}

function toggleOption(key, defaultValue) {
  const current = config.value[key] === undefined ? defaultValue : config.value[key]
  config.value[key] = !current
  changed()
}

/** 换算图表类型：把维度/度量收敛到目标类型允许的形状，不用手动删一遍。 */
function switchType(type) {
  if (type === props.card.type) return
  props.card.type = type
  if (type !== 'text') {
    if (type === 'kpi') {
      config.value.dimensions = []
      config.value.metrics = metrics.value.slice(0, 1)
    } else if (type === 'pie') {
      config.value.dimensions = dimensions.value.slice(0, 1).map(item => ({ ...item }))
      config.value.metrics = metrics.value.slice(0, 1)
    } else if (type === 'bar' || type === 'line') {
      config.value.dimensions = dimensions.value.slice(0, 1).map(item => ({ ...item }))
      config.value.metrics = metrics.value.slice(0, 4)
    } else {
      config.value.dimensions = dimensions.value.slice(0, 8).map(item => ({ ...item }))
      config.value.metrics = metrics.value.slice(0, 8)
    }
    const sort = config.value.sort
    const list = sort?.by === 'dimension' ? config.value.dimensions
      : sort?.by === 'metric' ? config.value.metrics : null
    if (list && !list[sort.index]) {
      config.value.sort = { by: 'none', index: 0, dir: 'desc' }
    }
  }
  changed()
}

// ------------------------------------------------------------------ 预览

function schedulePreview() {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 260)
}

async function runPreview() {
  if (props.card.type === 'text' || props.card.datasetId == null) {
    preview.value = null
    return
  }
  const current = props.card.config || {}
  if (!(current.metrics || []).length && !(current.dimensions || []).length) {
    preview.value = null
    return
  }
  const seq = ++previewSeq
  previewLoading.value = true
  try {
    const result = await api('/bi/query', {
      method: 'POST',
      body: JSON.stringify({ datasetId: props.card.datasetId, config: current })
    })
    if (seq !== previewSeq) return
    preview.value = { ok: true, ...result }
  } catch (e) {
    if (seq === previewSeq) preview.value = { ok: false, error: e.message, columns: [], rows: [] }
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}
</script>

<template>
  <div class="bi-card-editor" :style="{ '--draw-col': `${drawWidth}px` }">
    <!-- 拖拽手柄放在栅格容器上：放在列里会被列的滚动条/overflow 挡住 -->
    <div class="bi-resizer" :class="{ 'is-dragging': resizing }" role="separator" aria-orientation="vertical"
         title="拖动调整宽度" @pointerdown="startResize"></div>
    <!-- ① 数据集与字段 -->
    <section class="bi-ce-col bi-ce-dataset" aria-label="数据集与字段">
      <div class="bi-ce-ds">
        <div class="bi-ce-ds-head">
          <span class="bi-ce-ds-name">{{ dataset?.name || '未选择数据集' }}</span>
          <button class="icon-btn" title="切换数据集" aria-label="切换数据集" @click="openDatasetSwitch">
            <ArrowLeftRight :size="14"/>
          </button>
        </div>
        <div class="bi-ce-ds-meta">
          <span class="badge muted">{{ DATASET_LABELS[dataset?.datasetType] || dataset?.datasetType || '—' }}</span>
          <span class="mono muted">{{ [dataset?.databaseName, dataset?.tableName].filter(Boolean).join('.') || '平台存储' }}</span>
        </div>
        <div class="bi-ce-ds-line">最近更新 {{ stamp(dataset?.updatedAt) }}</div>
        <div class="bi-ce-ds-line">
          {{ dataset?.columnCount ?? detail?.columns?.length ?? '—' }} 个字段 · {{ rowCountText }} 行
        </div>
      </div>

      <div class="bi-field-search">
        <Search :size="12" class="bi-field-search-icon"/>
        <input v-model="keyword" placeholder="搜索字段" aria-label="搜索字段"/>
      </div>

      <div class="bi-ce-fields">
        <div v-if="loadingDetail" class="form-help">加载字段…</div>
        <template v-else>
          <template v-if="dimensionFields.length">
            <div class="bi-field-group">维度</div>
            <div v-for="field in dimensionFields" :key="field.name" class="bi-field is-draggable"
                 :class="{ 'is-used': dimensionLabels.includes(field.name) }"
                 draggable="true" :title="field.dataType"
                 @dragstart="startDrag($event, field)" @dragend="endDrag"
                 @click="addDimension(field.name)">
              <Type :size="12"/>
              <span>{{ field.name }}</span>
            </div>
          </template>
          <template v-if="measureFields.length">
            <div class="bi-field-group">数值</div>
            <div v-for="field in measureFields" :key="field.name" class="bi-field is-draggable"
                 draggable="true" :title="field.dataType"
                 @dragstart="startDrag($event, field)" @dragend="endDrag"
                 @click="addMetric(field.name)">
              <Hash :size="12"/>
              <span>{{ field.name }}</span>
            </div>
          </template>
          <div v-if="!fields.length" class="form-help">没有匹配的字段</div>
        </template>
      </div>
    </section>

    <!-- ② 绘制 -->
    <section class="bi-ce-col bi-ce-draw" aria-label="绘制">
      <div class="bi-ce-title">绘制</div>

      <div class="bi-section">
        <div class="bi-section-head">维度</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'dimension' }"
             @dragover.prevent="onDragOver('dimension')" @dragleave="hoverZone = ''" @drop.prevent="onDrop('dimension')">
          <button v-for="(dimension, index) in dimensions" :key="index" type="button"
                  class="bi-pill tone-dim" :class="{ active: isMenu('dimension', index) }"
                  @click.stop="toggleMenu($event, 'dimension', index)">
            <component :is="iconOf(dimension.column)" :size="12"/>
            <span>{{ dimension.alias || dimension.column }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-section">
        <div class="bi-section-head">数值</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'measure' }"
             @dragover.prevent="onDragOver('measure')" @dragleave="hoverZone = ''" @drop.prevent="onDrop('measure')">
          <button v-for="(metric, index) in metrics" :key="index" type="button"
                  class="bi-pill tone-measure" :class="{ active: isMenu('metric', index) }"
                  @click.stop="toggleMenu($event, 'metric', index)">
            <component :is="iconOf(metric.column)" :size="12"/>
            <span>{{ metricPill(metric) }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-ce-title" style="margin-top:4px">分析</div>

      <div class="bi-section">
        <div class="bi-section-head">筛选</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'filter' }"
             @dragover.prevent="onDragOver('filter')" @dragleave="hoverZone = ''" @drop.prevent="onDrop('filter')">
          <button v-for="(filter, index) in filters" :key="index" type="button"
                  class="bi-pill tone-measure" :class="{ active: isMenu('filter', index) }"
                  @click.stop="toggleMenu($event, 'filter', index)">
            <Filter :size="12"/>
            <span>{{ filterPill(filter) }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-section">
        <div class="bi-section-head">排序</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'sort' }"
             @dragover.prevent="onDragOver('sort')" @dragleave="hoverZone = ''" @drop.prevent="onDrop('sort')">
          <button v-if="hasSort" type="button" class="bi-pill tone-sort"
                  :class="{ active: isMenu('sort', sortState.index) }"
                  @click.stop="toggleMenu($event, 'sort', sortState.index)">
            <ArrowUpDown :size="12"/>
            <span>{{ sortLabel }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="menu.open" class="bi-pill-menu" :style="{ left: `${menu.left}px`, top: `${menu.top}px` }"
           @pointerdown.stop>
        <template v-if="menuDimension">
          <div class="bi-menu-head">{{ menuDimension.column }}</div>
          <div class="bi-menu-section">别名</div>
          <cdc-input v-model="menuDimension.alias" @update:model-value="changed"/>
          <button type="button" class="bi-menu-item danger" @click="removeDimension(menu.index)">移除字段</button>
        </template>

        <template v-else-if="menuMetric">
          <div class="bi-menu-head">{{ menuMetric.column }}</div>
          <div class="bi-menu-sub" @mouseenter="submenu = 'agg'">
            <button type="button" class="bi-menu-item has-sub"
                    @click="submenu = submenu === 'agg' ? '' : 'agg'">
              <span>聚合方式</span>
              <span class="bi-menu-value">{{ aggLabel(menuMetric.agg) }}</span>
              <ChevronRight :size="12"/>
            </button>
            <div v-if="submenu === 'agg'" class="bi-submenu">
              <button v-for="item in aggOptions(menuMetric)" :key="item.value" type="button"
                      class="bi-submenu-item" :class="{ active: menuMetric.agg === item.value }"
                      @click="setMetricAgg(item.value)">{{ item.label }}</button>
            </div>
          </div>
          <div class="bi-menu-section">别名</div>
          <cdc-input v-model="menuMetric.alias" @update:model-value="changed"/>
          <button v-if="supportsAxis && metrics.length > 1" type="button" class="bi-menu-item"
                  @click="toggleAxis(menuMetric)">
            {{ menuMetric.axis === 'right' ? '置于左轴' : '置于右轴' }}
          </button>
          <button type="button" class="bi-menu-item danger" @click="removeMetric(menu.index)">移除字段</button>
        </template>

        <template v-else-if="menuSort">
          <div class="bi-menu-head">{{ sortFieldName }}</div>
          <button type="button" class="bi-menu-item" :class="{ 'is-active': !isCustomSort && sortState.dir === 'asc' }"
                  @click="setSortDir('asc')">升序</button>
          <button type="button" class="bi-menu-item" :class="{ 'is-active': !isCustomSort && sortState.dir !== 'asc' }"
                  @click="setSortDir('desc')">降序</button>
          <div v-if="canCustomSort" class="bi-menu-sub" @mouseenter="submenu = 'sort'">
            <button type="button" class="bi-menu-item has-sub" :class="{ 'is-active': isCustomSort }"
                    @click="openCustomSort">
              <span>自定义排序</span>
              <ChevronRight :size="12"/>
            </button>
            <div v-if="submenu === 'sort'" class="bi-submenu wide">
              <div v-if="valueLoading" class="bi-submenu-hint">加载中…</div>
              <div v-else-if="!customValues.length" class="bi-submenu-hint">没有可选值</div>
              <div v-else class="bi-custom-list">
                <div v-for="(value, index) in customValues" :key="value" class="bi-custom-row">
                  <span class="bi-custom-name" :title="value">{{ value }}</span>
                  <button type="button" class="bi-custom-move" aria-label="上移" title="上移"
                          :disabled="index === 0" @click="moveCustom(index, -1)">↑</button>
                  <button type="button" class="bi-custom-move" aria-label="下移" title="下移"
                          :disabled="index === customValues.length - 1" @click="moveCustom(index, 1)">↓</button>
                </div>
              </div>
            </div>
          </div>
          <button type="button" class="bi-menu-item danger" @click="clearSort">取消排序</button>
        </template>

        <template v-else-if="menuFilter">
          <div class="bi-menu-head">{{ menuFilter.column }}</div>
          <div class="bi-menu-section">类型</div>
          <div class="bi-menu-grid three">
            <button v-for="item in FILTER_KINDS" :key="item.value" type="button"
                    class="bi-menu-option" :class="{ active: filterKind(menuFilter) === item.value }"
                    @click="setFilterKind(item.value)">{{ item.label }}</button>
          </div>

          <template v-if="filterKind(menuFilter) === 'select'">
            <div class="bi-menu-section">可选值</div>
            <div class="bi-check-tools">
              <input v-model="filterSearch" class="bi-check-search" placeholder="搜索" aria-label="搜索取值"/>
              <div class="bi-check-actions">
                <button type="button" class="bi-link" @click="toggleSelectAll">
                  {{ allVisibleSelected ? '取消全选' : '全选' }}
                </button>
                <label class="bi-exclude">
                  <input type="checkbox" :checked="isExclude" @change="toggleExclude"/>
                  <span>排除</span>
                </label>
              </div>
            </div>
            <div v-if="valueLoading" class="bi-submenu-hint">加载中…</div>
            <div v-else-if="!visibleSelectValues.length" class="bi-submenu-hint">没有可选值</div>
            <div v-else class="bi-check-list">
              <label v-for="value in visibleSelectValues" :key="value" class="bi-check">
                <input type="checkbox" :checked="isPicked(value)" @change="toggleFilterValue(value)"/>
                <span :title="value">{{ value }}</span>
              </label>
            </div>
          </template>

          <template v-else-if="filterKind(menuFilter) === 'range'">
            <div class="bi-range">
              <cdc-input :model-value="rangeValue(0)" @update:model-value="setRange(0, $event)"/>
              <span>~</span>
              <cdc-input :model-value="rangeValue(1)" @update:model-value="setRange(1, $event)"/>
            </div>
          </template>

          <template v-else>
            <div class="bi-menu-section">操作符</div>
            <cdc-select v-model="menuFilter.op" style="width:100%" @update:model-value="changed">
              <cdc-option v-for="op in COMPARATORS" :key="op" :label="op" :value="op"/>
            </cdc-select>
            <div class="bi-menu-section">值</div>
            <cdc-input v-model="menuFilter.value" :disabled="['IS NULL', 'IS NOT NULL'].includes(menuFilter.op)"
                       @update:model-value="changed"/>
          </template>
          <button type="button" class="bi-menu-item danger" @click="removeFilter(menu.index)">移除筛选</button>
        </template>
      </div>
    </Teleport>

    <cdc-modal v-model="switchOpen" title="切换数据集" width="440px">
      <cdc-select v-model="switchTarget" filterable style="width:100%" aria-label="选择数据集">
        <cdc-option v-for="item in datasets" :key="item.id"
                    :label="`${item.name}（${DATASET_LABELS[item.datasetType] || item.datasetType}）`"
                    :value="item.id"/>
      </cdc-select>
      <template #footer>
        <button class="btn btn-ghost" @click="switchOpen = false">取消</button>
        <span style="flex:1"/>
        <button class="btn btn-primary" :disabled="switchTarget == null || switchTarget === card.datasetId"
                @click="applyDatasetSwitch">切换</button>
      </template>
    </cdc-modal>

    <!-- ③ 预览 -->
    <section class="bi-ce-col bi-ce-preview" aria-label="预览">
      <div class="bi-ce-preview-head">
        <span>{{ card.title || '未命名卡片' }}</span>
      </div>
      <div class="bi-ce-preview-body">
        <BiCardView :card="card" :data="preview" :loading="previewLoading"/>
      </div>
    </section>

    <!-- ④ 可视化 -->
    <section class="bi-ce-col bi-ce-visual" aria-label="可视化">
      <div class="bi-ce-title">可视化</div>
      <div class="bi-type-grid">
        <button v-for="item in CARD_TYPES" :key="item.type" class="bi-type-btn"
                :class="{ active: card.type === item.type }" :title="item.label" :aria-label="item.label"
                @click="switchType(item.type)">
          <component :is="item.icon" :size="16"/>
          <span>{{ item.label }}</span>
        </button>
      </div>

      <div class="bi-ce-title" style="margin-top:14px">属性</div>
      <template v-if="card.type === 'text'">
        <cdc-form-item label="文本内容">
          <cdc-input v-model="config.text" type="textarea" :rows="10" @update:model-value="changed"/>
        </cdc-form-item>
      </template>
      <template v-else>
        <div v-if="['bar', 'line', 'pie'].includes(card.type)">
          <div class="bi-toggle-row" @click="toggleOption('showLegend', true)">
            <span>显示图例</span>
            <cdc-switch :model-value="config.showLegend !== false"/>
          </div>
          <div class="bi-toggle-row" @click="toggleOption('showLabel', false)">
            <span>数据标签</span>
            <cdc-switch :model-value="!!config.showLabel"/>
          </div>
        </div>
        <cdc-form-item label="最多显示行数">
          <cdc-input-number v-model="config.limit" :min="1" :max="MAX_LIMIT" style="width:100%"
                            @update:model-value="changed"/>
        </cdc-form-item>
      </template>
    </section>
  </div>
</template>
