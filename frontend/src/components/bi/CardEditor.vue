<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeftRight, ArrowUpDown, ChevronDown, ChevronRight, Filter, FunctionSquare, Hash, Plus, Search, Type, X } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage } from '../ui/feedback'
import { dashboardConditions } from './biFilters'
import {
  AGG_FUNCTIONS, AXIS_TYPES, CARD_TYPES, CHART_TYPES, COMPARATORS, COMPUTED_TYPES, DATASET_LABELS,
  COUNT_UNITS, CURRENCIES, FORMAT_PRECISIONS, FORMAT_TYPES, MAX_LIMIT, NO_AGG, cardRequirement,
  computedFields, filterLabel, formatMetricValue, metricFormat, metricFormatLabel, metricLimit
} from './biCards'
import BiCardView from './BiCardView.vue'
import ComputedFieldDialog from './ComputedFieldDialog.vue'

/**
 * 卡片编辑器：数据集 → 绘制 → 预览 → 可视化 四栏。
 *
 * <p>交互参照观远：字段在左栏，拖到「维度 / 数值 / 筛选 / 排序」四个落点里；
 * 中间实时预览，右栏换图形和调属性。点击字段也能加入（拖拽在触屏上不一定好使）。
 */
const props = defineProps({
  card: { type: Object, required: true },
  datasets: { type: Array, default: () => [] },
  /** 看板级筛选器：预览也要跟着筛，否则编辑器里看到的数和画布对不上。 */
  dashboardFilters: { type: Array, default: () => [] }
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
/** 菜单 DOM：打开后量一次真实高度，决定放字段下面还是上面 */
const menuEl = ref(null)
/** 菜单里的二级面板：'' | 'agg' | 'sort' */
const submenu = ref('')
/** 列取值缓存：筛选的可选值、自定义排序的候选值都来自它 */
const valueCache = ref({})
const valueLoading = ref(false)

const PAGE_SIZES = [20, 50, 100, 200]

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
// 老卡片没存 limit：按「不限制」处理，否则后端会退回 200 行，图表看着像少了数据。
// 必须在 watcher 之前改完，否则挂载时还会因为这次修改再触发一次取数。
if (config.value.limit == null && !['text', 'kpi'].includes(props.card.type)) config.value.limit = MAX_LIMIT
const dimensions = computed(() => config.value.dimensions || [])
const metrics = computed(() => config.value.metrics || [])
const filters = computed(() => config.value.filters || [])
const dimensionLabels = computed(() => dimensions.value.map(item => item.column))
const singleDimension = computed(() => props.card.type !== 'table')
/** 数据表可以横向铺很宽，按「总字段数」限制；图形卡片仍然按维度和数值各自限。 */
const MAX_TABLE_FIELDS = 50
const maxMetrics = computed(() => {
  if (props.card.type === 'table') return MAX_TABLE_FIELDS
  // 各类图形的度量上限统一在注册表里：柱线 4 个、散点 2 个、占比图 1 个
  return metricLimit(props.card.type)
})
// 条形图的坐标轴是横过来的，左右双轴没有意义，这里不给它开关
const supportsAxis = computed(() => AXIS_TYPES.includes(props.card.type) && props.card.type !== 'hbar')

/** 数据集的原始字段（不受搜索词影响）：计算字段编辑器要用完整清单。 */
const baseFields = computed(() => (detail.value?.columns || [])
  .filter(item => item.name)
  .map(item => ({ name: item.name, dataType: item.dataType, numeric: isNumeric(item.dataType) })))

const fields = computed(() => [
  // 计算字段排在最前面：它们是这张卡自己算出来的，比原始列更常用
  ...computedFields(config.value).map((item, index) => ({
    name: item.name,
    dataType: COMPUTED_TYPES.find(type => type.value === item.type)?.label || item.type || '',
    numeric: item.type === 'NUMBER',
    computed: true,
    index
  })),
  ...baseFields.value
].filter(item => !keyword.value.trim() || item.name.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const dimensionFields = computed(() => fields.value.filter(item => !item.numeric))
const measureFields = computed(() => fields.value.filter(item => item.numeric))

/**
 * 影响 SQL 的配置签名：不含 type，也不含图例/标签/分页这类只影响显示的东西。
 * 切换图表类型只要字段没变就不用重新取数，预览只重画、不闪。
 */
const querySignature = computed(() => JSON.stringify({
  datasetId: props.card.datasetId,
  // 看板筛选器只对命中这张卡的条件生效，改别的数据集不该触发这张卡重取
  dashboardFilters: dashboardConditions(props.dashboardFilters, props.card.datasetId, props.card.id),
  dimensions: config.value.dimensions || [],
  metrics: config.value.metrics || [],
  filters: config.value.filters || [],
  sort: config.value.sort || null,
  limit: config.value.limit ?? null,
  computed: config.value.computed || []
}))

watch(() => props.card.datasetId, loadDetail, { immediate: true })
watch(querySignature, schedulePreview)
// 之前缺字段、没取过数；补上之后签名会变。但「换成另一种能画的类型」签名不变，这里补一次。
watch(() => cardRequirement(props.card).complete, complete => {
  if (complete && !preview.value && props.card.datasetId != null) schedulePreview()
})

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
  stopSubmenuObserver()
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
  // 刚拖完字段松手时会补一个 click，不该当成「打开菜单」
  if (Date.now() - lastDragAt < 250) return
  if (isMenu(kind, index)) {
    menu.value.open = false
    return
  }
  const rect = event.currentTarget.getBoundingClientRect()
  menu.value = {
    open: true, kind, index,
    left: Math.max(8, Math.min(rect.left, window.innerWidth - 212)),
    // 先按「贴在字段下方」估个位置，渲染完再按真实高度微调
    top: rect.bottom + 4,
    anchor: { left: rect.left, top: rect.top, bottom: rect.bottom }
  }
  submenu.value = ''
  nextTick(placeMenu)
  if (kind === 'filter') {
    filterSearch.value = ''
    loadValues(filters.value[index]?.column)
  }
  if (kind === 'sort') loadValues(sortDimension.value?.column)
}

/**
 * 菜单贴着触发它的字段：优先放下方，下方放不下就翻到上方。
 *
 * <p>之前用的是「底部留 400px」的粗暴夹取，字段在列表下半截时菜单会被顶到很上面，
 * 看起来像飘在别处，完全对不上点的是哪个字段。
 */
function placeMenu() {
  const el = menuEl.value
  if (!el || !menu.value.open) return
  const anchor = menu.value.anchor
  if (!anchor) return
  const height = el.offsetHeight
  const width = el.offsetWidth || 200
  const edge = 8
  const below = anchor.bottom + 4
  const top = below + height <= window.innerHeight - edge
    ? below
    : Math.max(edge, anchor.top - height - 4)
  const left = Math.max(edge, Math.min(anchor.left, window.innerWidth - width - edge))
  menu.value = { ...menu.value, top, left }
}

/**
 * 二级菜单（聚合方式 / 数据格式）贴着主菜单右侧展开。
 *
 * <p>主菜单会上下翻转，二级菜单以前不会：字段在下半屏时，数据格式面板
 * 直接顶出视口底部，预览和「确定」都点不到。这里按视口把面板往上顶，
 * 右边放不下就翻到左侧，实在比视口还高就让它内部滚动。
 */
let submenuObserver = null

function placeSubmenu() {
  nextTick(() => {
    const panel = menuEl.value?.querySelector('.bi-submenu')
    if (!panel) return
    const apply = () => {
      panel.style.top = '-6px'
      panel.style.left = 'calc(100% + 4px)'
      panel.style.maxHeight = ''
      const rect = panel.getBoundingClientRect()
      const edge = 8
      if (rect.bottom > window.innerHeight - edge) {
        panel.style.top = `-${Math.round(rect.bottom - (window.innerHeight - edge)) + 6}px`
      }
      if (rect.right > window.innerWidth - edge) {
        panel.style.left = `-${Math.round(rect.width) + 4}px`
      }
      const maxHeight = window.innerHeight - edge * 2
      if (panel.offsetHeight > maxHeight) panel.style.maxHeight = `${maxHeight}px`
    }
    apply()
    // 面板会随内容变高（比如把类型从「默认」切成「数值」多出五六个配置项），
    // 只在展开那一刻定位一次，变高之后底部又会顶出视口
    submenuObserver?.disconnect()
    submenuObserver = new ResizeObserver(apply)
    submenuObserver.observe(panel)
  })
}

function stopSubmenuObserver() {
  submenuObserver?.disconnect()
  submenuObserver = null
}

watch(submenu, () => {
  if (submenu.value) placeSubmenu()
  else stopSubmenuObserver()
})

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
  submenu.value = ''
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

// ------------------------------------------------------------ 数据格式

/** 二级菜单里正在编辑的格式；null 表示没展开。 */
const formatDraft = ref(null)

/**
 * 二级菜单统一「点开、再点关」。
 *
 * <p>原来是 hover 展开，鼠标一移开就收，想改个精度得一直悬着；
 * 而且从下拉弹层移回来会误判成「离开」。现在只认点击。
 */
function toggleSubmenu(name) {
  submenu.value = submenu.value === name ? '' : name
}

function toggleFormat(metric) {
  if (!metric) return
  if (submenu.value === 'format') {
    cancelFormat()
    return
  }
  formatDraft.value = metricFormat(metric)
  submenu.value = 'format'
}

function cancelFormat() {
  submenu.value = ''
  formatDraft.value = null
}

/** 切到货币时把符号带进前缀（用户已经填过就不动）。 */
function setFormatType(type) {
  const draft = formatDraft.value
  if (!draft) return
  draft.type = type
  if (type === 'currency' && !draft.prefix) draft.prefix = draft.currency
}

function setCurrency(symbol) {
  const draft = formatDraft.value
  if (!draft) return
  const previous = draft.currency
  draft.currency = symbol
  if (!draft.prefix || draft.prefix === previous) draft.prefix = symbol
}

const formatPreview = computed(() => formatMetricValue(1000000, { format: formatDraft.value }))

function applyFormat() {
  const metric = menuMetric.value
  if (metric && formatDraft.value) {
    metric.format = { ...formatDraft.value }
    changed()
  }
  cancelFormat()
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
  const computedField = computedFields(config.value).find(item => item.name === name)
  if (computedField) return computedField.type === 'NUMBER'
  const column = (detail.value?.columns || []).find(item => item.name === name)
  return column ? isNumeric(column.dataType) : true
}

function iconOf(name) {
  if (computedFields(config.value).some(item => item.name === name)) return FunctionSquare
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

/** 「最多显示行数」留空就是不限制：配置里存 MAX_LIMIT 表示不限（后端也有同一个上限）。 */
const limitValue = computed(() => {
  const value = Number(config.value.limit)
  return !Number.isFinite(value) || value >= MAX_LIMIT ? null : value
})

function setLimit(value) {
  config.value.limit = value == null || value <= 0
    ? MAX_LIMIT
    : Math.min(Math.round(value), MAX_LIMIT)
  changed()
}

/** 分页条上直接改每页条数：跟着存进配置，刷新后还是这个值。 */
function onPageSize(value) {
  config.value.pageSize = value
  changed()
}

// ------------------------------------------------------------ 计算字段

const computedOpen = ref(false)
const computedEditing = ref(null)

function openComputed(index = null) {
  const list = computedFields(config.value)
  const item = index == null ? null : list[index]
  computedEditing.value = item ? { index, name: item.name, type: item.type, expr: item.expr } : null
  computedOpen.value = true
}

function saveComputed(payload) {
  const list = [...computedFields(config.value)]
  const previous = payload.index == null ? null : list[payload.index]
  const next = { name: payload.name, type: payload.type, expr: payload.expr }
  if (payload.index == null) list.push(next)
  else list[payload.index] = next
  config.value.computed = list
  // 改名要把图里引用它的地方一起改，否则预览会报「数据集里没有字段」
  if (previous && previous.name !== payload.name) renameComputed(previous.name, payload.name)
  changed()
}

function renameComputed(from, to) {
  const remap = item => (item.column === from ? { ...item, column: to } : item)
  if (config.value.dimensions) config.value.dimensions = dimensions.value.map(remap)
  if (config.value.metrics) config.value.metrics = metrics.value.map(remap)
  if (config.value.filters) config.value.filters = filters.value.map(remap)
}

function removeComputed(index) {
  const list = [...computedFields(config.value)]
  const [removed] = list.splice(index, 1)
  if (!removed) return
  config.value.computed = list
  const keep = items => items.filter(item => item.column !== removed.name)
  if (config.value.dimensions) config.value.dimensions = keep(dimensions.value)
  if (config.value.metrics) config.value.metrics = keep(metrics.value)
  if (config.value.filters) config.value.filters = keep(filters.value)
  changed()
}

// ------------------------------------------------------------------ 拖拽

/** 拖绘制区里的字段茶壶时，记下落点在哪个位置（用来显示插入线、以及重排）。 */
const pillDrop = ref({ zone: '', index: -1 })
/** 刚拖完的这一次 click 不要当成「点开菜单」。 */
let lastDragAt = 0

function startDrag(event, field) {
  dragging.value = field
  setDragGhost(event, field.name, field.numeric ? 'measure' : 'dim')
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
    // 必须写点东西，否则 Firefox 不触发 drop
    event.dataTransfer.setData('text/plain', field.name)
  }
}

/**
 * 拖动时跟着鼠标的只放一小块标签。
 *
 * <p>浏览器默认的拖影是整个元素截图，字段条本来就占满整列，拖起来会把下面几行全糊住，
 * 根本看不清落点。这里临时建一个窄标签当拖影，用完立刻删掉。
 */
function setDragGhost(event, label, tone = 'dim') {
  if (!event.dataTransfer || typeof event.dataTransfer.setDragImage !== 'function') return
  const ghost = document.createElement('div')
  ghost.className = `bi-drag-ghost tone-${tone}`
  ghost.textContent = label || ''
  document.body.appendChild(ghost)
  event.dataTransfer.setDragImage(ghost, 10, 12)
  // setDragImage 是同步取快照，下一帧就可以移除节点
  setTimeout(() => ghost.remove(), 0)
}

function startPillDrag(event, zone, index) {
  // 计算字段的提示、跨区转换都要用到原始配置
  const item = zone === 'dimension' ? dimensions.value[index] : metrics.value[index]
  const label = zone === 'dimension' ? (item?.alias || item?.column || '') : metricPill(item || {})
  dragging.value = { kind: 'pill', zone, index, name: item?.column || '' }
  pillDrop.value = { zone, index }
  setDragGhost(event, label, zone === 'dimension' ? 'dim' : 'measure')
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', item?.column || '')
  }
}

function isPillDrag(zone, index) {
  return dragging.value?.kind === 'pill' && dragging.value.zone === zone && dragging.value.index === index
}

/** 落点槽位：指针停在这个区里、且落在第 index 个之前时显示一个「+」。 */
function showSlot(zone, index) {
  return hoverZone.value === zone && pillDrop.value.zone === zone && pillDrop.value.index === index
}

/** 正在拖的是左侧字段列表里的字段（不是绘制区里的茶壶）。 */
function isFieldDrag() {
  return !!dragging.value && dragging.value.kind !== 'pill'
}

/** 筛选 / 排序只有一个落点：悬停时在末尾显示「+」。 */
function showEndSlot(zone) {
  return hoverZone.value === zone && isFieldDrag()
}

function endDrag() {
  lastDragAt = Date.now()
  dragging.value = null
  hoverZone.value = ''
  pillDrop.value = { zone: '', index: -1 }
}

/** 指针落在这批茶壶的第几个之前（按中线判断），末尾返回 length。 */
function pillIndexAt(event, zone) {
  const pills = Array.from(event.currentTarget.querySelectorAll('.bi-pill'))
  for (let i = 0; i < pills.length; i++) {
    const rect = pills[i].getBoundingClientRect()
    if (event.clientY < rect.top + rect.height / 2) return i
  }
  return pills.length
}

function onDragOver(zone, event) {
  if (!dragging.value) return
  hoverZone.value = zone
  if ((zone === 'dimension' || zone === 'measure') && event) {
    pillDrop.value = { zone, index: pillIndexAt(event, zone) }
  }
}

/**
 * 离开落点区：把插入槽位一起收掉。
 *
 * <p>移到区内的子元素上也会触发 dragleave（relatedTarget 还在区里），
 * 这种不算离开，否则从茶壶移开手指时「+」会闪一下。
 */
function onDragLeave(zone, event) {
  const next = event?.relatedTarget
  if (next && event.currentTarget?.contains(next)) return
  if (hoverZone.value === zone) hoverZone.value = ''
  if (pillDrop.value.zone === zone) pillDrop.value = { zone: '', index: -1 }
}

function onDrop(zone) {
  const payload = dragging.value
  const drop = pillDrop.value
  hoverZone.value = ''
  dragging.value = null
  pillDrop.value = { zone: '', index: -1 }
  if (!payload) return

  // 绘制区里的字段：同区重排，跨区就是把字段挪过去
  if (payload.kind === 'pill') {
    if (zone === 'dimension' || zone === 'measure') {
      if (zone === payload.zone) reorderPills(zone, payload.index, drop.index)
      else movePill(payload.zone, payload.index, zone, drop.index)
    }
    return
  }

  // 拖到哪个区就按哪个区处理，不再看字段类型：
  // 维度可以当数值（计数 / 去重计数 / 最大 / 最小），数值也可以不聚合直接当维度
  if (zone === 'dimension') {
    addDimension(payload.name, drop.index)
    return
  }
  if (zone === 'measure') {
    addMetric(payload.name, drop.index)
    return
  }
  if (zone === 'filter') {
    // 计算字段的候选值要现算表达式，接口只认数据集列，暂不支持放进筛选
    if (payload.computed) {
      CdcMessage.warning('计算字段暂不支持筛选，可以先放进维度或数值')
      return
    }
    addFilter(payload.name)
    return
  }
  if (zone === 'sort') {
    sortByField(payload.name)
  }
}

function reorderPills(zone, from, to) {
  const list = (zone === 'dimension' ? dimensions.value : metrics.value).map(item => ({ ...item }))
  const [item] = list.splice(from, 1)
  if (!item) return
  // 插入位置是按「移动前」的下标算的，往下拖时要减掉自己
  let target = to < 0 ? list.length : to
  if (target > from) target -= 1
  target = Math.max(0, Math.min(target, list.length))
  if (target === from) return
  list.splice(target, 0, item)
  if (zone === 'dimension') config.value.dimensions = list
  else config.value.metrics = list
  retargetSort(zone, from, target)
  changed()
}

/** 排序按序号引用字段，重排后要跟着挪，否则排的还是原来那一列。 */
function retargetSort(zone, from, to) {
  const sort = config.value.sort
  const by = zone === 'dimension' ? 'dimension' : 'metric'
  if (!sort || sort.by !== by) return
  let index = sort.index
  if (index === from) index = to
  else if (from < index && to >= index) index -= 1
  else if (from > index && to <= index) index += 1
  config.value.sort = { ...sort, index }
}

/** 维度 ↔ 数值：维度转数值默认求和（文本列退化成计数），数值转维度去掉聚合。 */
function movePill(fromZone, fromIndex, toZone, toIndex) {
  const from = (fromZone === 'dimension' ? dimensions.value : metrics.value).map(item => ({ ...item }))
  const [item] = from.splice(fromIndex, 1)
  if (!item) return
  const target = (toZone === 'dimension' ? dimensions.value : metrics.value).map(entry => ({ ...entry }))
  const converted = toZone === 'dimension'
    ? { column: item.column, alias: item.alias || '' }
    : { column: item.column, agg: numericOf(item.column) ? 'SUM' : 'COUNT', alias: item.alias || '', axis: 'left' }
  const at = Math.max(0, Math.min(toIndex < 0 ? target.length : toIndex, target.length))
  target.splice(at, 0, converted)
  if (fromZone === 'dimension') config.value.dimensions = from
  else config.value.metrics = from
  if (toZone === 'dimension') config.value.dimensions = target
  else config.value.metrics = target
  config.value.sort = { by: 'none', index: 0, dir: 'desc' }
  changed()
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

/** 数据表的字段上限按「维度 + 数值」总数算，超过就不再加。 */
function overFieldLimit() {
  if (props.card.type !== 'table') return false
  if (dimensions.value.length + metrics.value.length >= MAX_TABLE_FIELDS) {
    CdcMessage.warning(`数据表最多 ${MAX_TABLE_FIELDS} 个字段`)
    return true
  }
  return false
}

function addDimension(name, at = -1) {
  if (props.card.type === 'kpi') return
  if (dimensionLabels.value.includes(name)) return
  if (overFieldLimit()) return
  const next = { column: name, alias: '' }
  if (singleDimension.value) {
    config.value.dimensions = [next]
    changed()
    return
  }
  // 从字段列表拖进来时按落点插入，不是无脑追加到末尾
  const list = dimensions.value.map(item => ({ ...item }))
  list.splice(at >= 0 && at <= list.length ? at : list.length, 0, next)
  config.value.dimensions = list.slice(0, MAX_TABLE_FIELDS)
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

function addMetric(name, at = -1) {
  if (metrics.value.length >= maxMetrics.value) return
  if (overFieldLimit()) return
  const column = name || measureFields.value[0]?.name || fields.value[0]?.name || ''
  // 文本字段求和没有意义，默认按计数；数值才默认求和
  const agg = numericOf(column) ? 'SUM' : 'COUNT'
  const list = metrics.value.map(item => ({ ...item }))
  list.splice(at >= 0 && at <= list.length ? at : list.length, 0, { column, agg, alias: '', axis: 'left' })
  config.value.metrics = list
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
  if (submenu.value === 'sort') {
    submenu.value = ''
    return
  }
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
    } else if (CHART_TYPES.includes(type)) {
      // 所有图形卡片都只吃 1 个维度；度量按类型上限截断（散点正好 2 个）
      config.value.dimensions = dimensions.value.slice(0, 1).map(item => ({ ...item }))
      config.value.metrics = metrics.value.slice(0, metricLimit(type))
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
  // 维度 / 数值没配齐就不发请求：预览区会显示「建议 1 个维度，1 个数值」，
  // 不用先撞一次后端报错再翻译成人话
  if (!cardRequirement(props.card).complete) {
    preview.value = null
    previewLoading.value = false
    return
  }
  // 卡片自己的筛选在前，看板筛选器追加在后；不直接改卡片配置
  const current = { ...(props.card.config || {}) }
  const extra = dashboardConditions(props.dashboardFilters, props.card.datasetId, props.card.id)
  if (extra.length) current.filters = [...(current.filters || []), ...extra]
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
        <button type="button" class="bi-field-add" title="新增计算字段" aria-label="新增计算字段"
                @click="openComputed()">
          <Plus :size="13"/>
        </button>
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
              <component :is="iconOf(field.name)" :size="12"/>
              <span>{{ field.name }}</span>
              <button v-if="field.computed" type="button" class="bi-field-edit" title="编辑计算字段"
                      aria-label="编辑计算字段" @click.stop="openComputed(field.index)">
                <FunctionSquare :size="11"/>
              </button>
            </div>
          </template>
          <template v-if="measureFields.length">
            <div class="bi-field-group">数值</div>
            <div v-for="field in measureFields" :key="field.name" class="bi-field is-draggable"
                 draggable="true" :title="field.dataType"
                 @dragstart="startDrag($event, field)" @dragend="endDrag"
                 @click="addMetric(field.name)">
              <component :is="iconOf(field.name)" :size="12"/>
              <span>{{ field.name }}</span>
              <button v-if="field.computed" type="button" class="bi-field-edit" title="编辑计算字段"
                      aria-label="编辑计算字段" @click.stop="openComputed(field.index)">
                <FunctionSquare :size="11"/>
              </button>
            </div>
          </template>
          <div v-if="!fields.length" class="form-help">没有匹配的字段</div>
        </template>
      </div>

      <ComputedFieldDialog v-model="computedOpen" :fields="baseFields"
                           :computed="computedFields(config)" :editing="computedEditing"
                           @submit="saveComputed" @remove="removeComputed"/>
    </section>

    <!-- ② 绘制 -->
    <section class="bi-ce-col bi-ce-draw" aria-label="绘制">
      <div class="bi-ce-title">绘制</div>

      <div class="bi-section">
        <div class="bi-section-head">维度</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'dimension' }"
             @dragover.prevent="onDragOver('dimension', $event)" @dragleave="onDragLeave('dimension', $event)" @drop.prevent="onDrop('dimension')">
          <template v-for="(dimension, index) in dimensions" :key="index">
            <span v-if="showSlot('dimension', index)" class="bi-drop-slot" aria-hidden="true">+</span>
            <button type="button" class="bi-pill tone-dim"
                    :class="{ active: isMenu('dimension', index), 'is-drag-source': isPillDrag('dimension', index) }"
                    draggable="true"
                    @dragstart="startPillDrag($event, 'dimension', index)" @dragend="endDrag"
                    @click.stop="toggleMenu($event, 'dimension', index)">
              <component :is="iconOf(dimension.column)" :size="12"/>
              <span>{{ dimension.alias || dimension.column }}</span>
              <!-- 悬停才出现的小叉：pill 本身是 button，这里只能用 span，避免按钮套按钮 -->
              <span class="bi-pill-x" role="button" tabindex="0" title="移除字段" aria-label="移除字段"
                    @click.stop="removeDimension(index)"
                    @keydown.enter.stop.prevent="removeDimension(index)">
                <X :size="11"/>
              </span>
            </button>
          </template>
          <span v-if="showSlot('dimension', dimensions.length)" class="bi-drop-slot" aria-hidden="true">+</span>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-section">
        <div class="bi-section-head">数值</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'measure' }"
             @dragover.prevent="onDragOver('measure', $event)" @dragleave="onDragLeave('measure', $event)" @drop.prevent="onDrop('measure')">
          <template v-for="(metric, index) in metrics" :key="index">
            <span v-if="showSlot('measure', index)" class="bi-drop-slot" aria-hidden="true">+</span>
            <button type="button" class="bi-pill tone-measure"
                    :class="{ active: isMenu('metric', index), 'is-drag-source': isPillDrag('measure', index) }"
                    draggable="true"
                    @dragstart="startPillDrag($event, 'measure', index)" @dragend="endDrag"
                    @click.stop="toggleMenu($event, 'metric', index)">
              <component :is="iconOf(metric.column)" :size="12"/>
              <span>{{ metricPill(metric) }}</span>
              <span class="bi-pill-x" role="button" tabindex="0" title="移除字段" aria-label="移除字段"
                    @click.stop="removeMetric(index)"
                    @keydown.enter.stop.prevent="removeMetric(index)">
                <X :size="11"/>
              </span>
            </button>
          </template>
          <span v-if="showSlot('measure', metrics.length)" class="bi-drop-slot" aria-hidden="true">+</span>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-ce-title" style="margin-top:4px">分析</div>

      <div class="bi-section">
        <div class="bi-section-head">筛选</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'filter' }"
             @dragover.prevent="onDragOver('filter', $event)" @dragleave="onDragLeave('filter', $event)" @drop.prevent="onDrop('filter')">
          <button v-for="(filter, index) in filters" :key="index" type="button"
                  class="bi-pill tone-measure" :class="{ active: isMenu('filter', index) }"
                  @click.stop="toggleMenu($event, 'filter', index)">
            <Filter :size="12"/>
            <span>{{ filterPill(filter) }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <span v-if="showEndSlot('filter')" class="bi-drop-slot" aria-hidden="true">+</span>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>

      <div class="bi-section">
        <div class="bi-section-head">排序</div>
        <div class="bi-zone" :class="{ 'is-hover': hoverZone === 'sort' }"
             @dragover.prevent="onDragOver('sort', $event)" @dragleave="onDragLeave('sort', $event)" @drop.prevent="onDrop('sort')">
          <button v-if="hasSort" type="button" class="bi-pill tone-sort"
                  :class="{ active: isMenu('sort', sortState.index) }"
                  @click.stop="toggleMenu($event, 'sort', sortState.index)">
            <ArrowUpDown :size="12"/>
            <span>{{ sortLabel }}</span>
            <ChevronDown :size="10" class="bi-pill-caret"/>
          </button>
          <span v-if="showEndSlot('sort')" class="bi-drop-slot" aria-hidden="true">+</span>
          <div class="bi-drop">拖到这里</div>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="menu.open" ref="menuEl" class="bi-pill-menu" :style="{ left: `${menu.left}px`, top: `${menu.top}px` }"
           @pointerdown.stop>
        <template v-if="menuDimension">
          <div class="bi-menu-head">{{ menuDimension.column }}</div>
          <div class="bi-menu-section">别名</div>
          <cdc-input v-model="menuDimension.alias" @update:model-value="changed"/>
          <button type="button" class="bi-menu-item danger" @click="removeDimension(menu.index)">移除字段</button>
        </template>

        <template v-else-if="menuMetric">
          <div class="bi-menu-head">{{ menuMetric.column }}</div>
          <div class="bi-menu-sub">
            <button type="button" class="bi-menu-item has-sub" :class="{ 'is-active': submenu === 'agg' }"
                    @click="toggleSubmenu('agg')">
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
          <div class="bi-menu-sub">
            <button type="button" class="bi-menu-item has-sub" :class="{ 'is-active': submenu === 'format' }"
                    @click="toggleFormat(menuMetric)">
              <span>数据格式</span>
              <span class="bi-menu-value">{{ metricFormatLabel(menuMetric) }}</span>
              <ChevronRight :size="12"/>
            </button>
            <div v-if="submenu === 'format' && formatDraft" class="bi-submenu bi-format-panel">
              <div class="bi-format-row">
                <span class="bi-format-label">类型</span>
                <cdc-select :model-value="formatDraft.type" @update:model-value="setFormatType">
                  <cdc-option v-for="item in FORMAT_TYPES" :key="item.value" :label="item.label" :value="item.value"/>
                </cdc-select>
              </div>
              <template v-if="formatDraft.type !== 'default'">
                <div v-if="formatDraft.type === 'currency'" class="bi-format-row">
                  <span class="bi-format-label">货币</span>
                  <cdc-select :model-value="formatDraft.currency" @update:model-value="setCurrency">
                    <cdc-option v-for="item in CURRENCIES" :key="item.value" :label="item.label" :value="item.value"/>
                  </cdc-select>
                </div>
                <div class="bi-format-row">
                  <span class="bi-format-label">精度</span>
                  <cdc-select :model-value="formatDraft.precision"
                              @update:model-value="value => (formatDraft.precision = Number(value))">
                    <cdc-option v-for="item in FORMAT_PRECISIONS" :key="item" :label="String(item)" :value="item"/>
                  </cdc-select>
                </div>
                <div class="bi-format-check">
                  <cdc-switch :model-value="formatDraft.thousands"
                              @update:model-value="value => (formatDraft.thousands = value)"/>
                  <span>千分位分隔符</span>
                </div>
                <div class="bi-format-row">
                  <span class="bi-format-label">计数单位</span>
                  <cdc-select :model-value="formatDraft.unit"
                              @update:model-value="value => (formatDraft.unit = value)">
                    <cdc-option v-for="item in COUNT_UNITS" :key="item.value" :label="item.label" :value="item.value"/>
                  </cdc-select>
                </div>
                <div class="bi-format-row">
                  <span class="bi-format-label">前缀</span>
                  <cdc-input v-model="formatDraft.prefix" placeholder="请输入…"/>
                </div>
                <div class="bi-format-row">
                  <span class="bi-format-label">后缀</span>
                  <cdc-input v-model="formatDraft.suffix" placeholder="请输入…"/>
                </div>
                <div class="bi-format-label">预览</div>
                <div class="bi-format-preview">{{ formatPreview }}</div>
              </template>
              <div class="bi-format-actions">
                <button type="button" class="btn btn-ghost btn-sm" @click="cancelFormat">取消</button>
                <button type="button" class="btn btn-primary btn-sm" @click="applyFormat">确定</button>
              </div>
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
          <div v-if="canCustomSort" class="bi-menu-sub">
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
        <BiCardView :card="card" :data="preview" :loading="previewLoading" @page-size="onPageSize"/>
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
        <div v-if="CHART_TYPES.includes(card.type)">
          <!-- 散点只有一个系列，图例没有信息量 -->
          <div v-if="card.type !== 'scatter'" class="bi-toggle-row" @click="toggleOption('showLegend', false)">
            <span>显示图例</span>
            <cdc-switch :model-value="config.showLegend === true"/>
          </div>
          <div class="bi-toggle-row" @click="toggleOption('showLabel', false)">
            <span>数据标签</span>
            <cdc-switch :model-value="!!config.showLabel"/>
          </div>
        </div>
        <template v-if="card.type === 'table'">
          <div class="bi-toggle-row" @click="toggleOption('pagination', false)">
            <span>表格分页</span>
            <cdc-switch :model-value="!!config.pagination"/>
          </div>
          <div v-if="config.pagination" class="bi-toggle-row bi-toggle-field">
            <span>每页行数</span>
            <cdc-select class="bi-page-size" :model-value="config.pageSize || 50"
                        @update:model-value="value => { config.pageSize = value; changed() }">
              <cdc-option v-for="size in PAGE_SIZES" :key="size" :label="String(size)" :value="size"/>
            </cdc-select>
          </div>
          <div class="bi-toggle-row" @click="toggleOption('showRowIndex', false)">
            <span>显示行序号</span>
            <cdc-switch :model-value="!!config.showRowIndex"/>
          </div>
        </template>
        <cdc-form-item label="最多显示行数">
          <cdc-input-number :model-value="limitValue" :min="1" :max="MAX_LIMIT" placeholder="不限制"
                            style="width:100%" @update:model-value="setLimit"/>
          <div class="form-help">留空表示不限制，服务端最多返回 {{ MAX_LIMIT }} 行</div>
        </cdc-form-item>
      </template>
    </section>
  </div>
</template>
