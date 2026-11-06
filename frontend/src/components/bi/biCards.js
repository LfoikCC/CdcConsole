/**
 * 卡片类型注册表：面板、默认配置、默认尺寸、校验规则都集中在这里。
 *
 * <p>校验只是「让用户在编辑器里少犯错」的引导，真正的权威在后端
 * （BiDashboardService.validateCards）——两边规则必须一致，否则会出现
 * 「编辑器放行、保存被拒」。
 */
import {
  BarChart3, ChartArea, ChartBar, ChartNoAxesCombined, ChartScatter, Filter,
  Hash, Layers, LineChart, PieChart, Table2, Type
} from 'lucide-vue-next'

/**
 * 卡片类型注册表。顺序就是可视化面板里的排列顺序：
 * 表格 / 指标卡这类最常用的放前面，同族图形挨在一起。
 */
export const CARD_TYPES = [
  { type: 'table', label: '数据表', icon: Table2, w: 8, h: 8 },
  { type: 'kpi', label: '指标卡', icon: Hash, w: 3, h: 6 },
  { type: 'bar', label: '柱状图', icon: BarChart3, w: 6, h: 8 },
  { type: 'hbar', label: '条形图', icon: ChartBar, w: 6, h: 8 },
  { type: 'line', label: '折线图', icon: LineChart, w: 6, h: 8 },
  { type: 'area', label: '面积图', icon: ChartArea, w: 6, h: 8 },
  { type: 'pie', label: '饼图', icon: PieChart, w: 4, h: 8 },
  { type: 'funnel', label: '漏斗图', icon: Filter, w: 4, h: 8 },
  { type: 'combo', label: '柱线组合', icon: ChartNoAxesCombined, w: 6, h: 8 },
  { type: 'stack', label: '堆积柱状', icon: Layers, w: 6, h: 8 },
  { type: 'scatter', label: '散点图', icon: ChartScatter, w: 6, h: 8 },
  { type: 'text', label: '文本', icon: Type, w: 6, h: 4 }
]

export const CARD_LABELS = Object.fromEntries(CARD_TYPES.map(item => [item.type, item.label]))

/** 直角坐标系图形：一个维度 + 若干度量，画在 x/y 轴上。 */
export const AXIS_TYPES = ['bar', 'hbar', 'stack', 'line', 'area', 'combo']
/** 按占比渲染的图形：一个维度 + 一个度量。 */
export const SHARE_TYPES = ['pie', 'funnel']
/** 所有走 ECharts 的类型，其余由表格 / 指标卡 / 文本组件自己渲染。 */
export const CHART_TYPES = [...AXIS_TYPES, 'scatter', ...SHARE_TYPES]

/** 这张图最多能放几个度量：柱线类 4 个，散点必须正好 2 个，占比图 1 个。 */
export function metricLimit(type) {
  if (AXIS_TYPES.includes(type)) return 4
  if (type === 'scatter') return 2
  return type === 'text' ? 0 : 1
}

/** 计算字段的输出类型：只决定它出现在「维度」还是「数值」里。 */
export const COMPUTED_TYPES = [
  { value: 'NUMBER', label: '数值' },
  { value: 'TEXT', label: '文本' },
  { value: 'DATE', label: '日期' }
]

/** 卡片自带的计算字段；老卡片没有这个键，统一当空数组。 */
export function computedFields(config) {
  return Array.isArray(config?.computed) ? config.computed : []
}

/**
 * 汇总方式。NONE 表示不聚合——维度拖进数值、或用户显式选「无处理」时用，
 * 后端见到 NONE 就原样取列，不再包 SUM/COUNT。
 */
export const AGG_FUNCTIONS = [
  { value: 'NONE', label: '无处理' },
  { value: 'SUM', label: '求和' },
  { value: 'COUNT', label: '计数' },
  { value: 'COUNT_DISTINCT', label: '去重计数' },
  { value: 'AVG', label: '平均值' },
  { value: 'MIN', label: '最小值' },
  { value: 'MAX', label: '最大值' }
]

export const NO_AGG = 'NONE'

/**
 * 数值字段的数据格式。
 *
 * <p>「默认」就是不做处理：不加千分位、不加单位、不加前后缀。
 * 只有选了数值/货币/百分比才按这里的规则渲染，老看板不会因为这次改动突然变样。
 */
export const FORMAT_TYPES = [
  { value: 'default', label: '默认' },
  { value: 'number', label: '数值' },
  { value: 'currency', label: '货币' },
  { value: 'percent', label: '百分比' }
]

export const FORMAT_PRECISIONS = [0, 1, 2, 3, 4, 5, 6]

/** 计数单位：K/M 按 1000 进位，万/亿按中文习惯；自动按数值大小挑一个。 */
export const COUNT_UNITS = [
  { value: 'none', label: '无' },
  { value: 'k', label: 'K' },
  { value: 'm', label: 'M' },
  { value: 'wan', label: '万' },
  { value: 'yi', label: '亿' },
  { value: 'auto', label: '自动' }
]

export const CURRENCIES = [
  { value: '¥', label: '¥（人民币）' },
  { value: '$', label: '$（美元）' },
  { value: '€', label: '€（欧元）' },
  { value: '£', label: '£（英镑）' },
  { value: 'JP¥', label: 'JP¥（日元）' }
]

const clampPrecision = value => {
  const number = Number(value)
  return Number.isInteger(number) ? Math.min(6, Math.max(0, number)) : 2
}

export function metricFormat(metric) {
  const format = metric?.format || {}
  return {
    type: FORMAT_TYPES.some(item => item.value === format.type) ? format.type : 'default',
    precision: clampPrecision(format.precision),
    thousands: format.thousands !== false,
    unit: COUNT_UNITS.some(item => item.value === format.unit) ? format.unit : 'none',
    currency: CURRENCIES.some(item => item.value === format.currency) ? format.currency : '¥',
    prefix: format.prefix || '',
    suffix: format.suffix || ''
  }
}

/** 有没有设过格式：默认就是不做处理，各处沿用原来的显示。 */
export function hasMetricFormat(metric) {
  return metricFormat(metric).type !== 'default'
}

/** 计数单位换算：返回缩放后的数值和要拼在后面的单位。 */
function scaleByUnit(number, unit) {
  const abs = Math.abs(number)
  switch (unit) {
    case 'k': return { value: number / 1e3, label: 'K' }
    case 'm': return { value: number / 1e6, label: 'M' }
    case 'wan': return { value: number / 1e4, label: '万' }
    case 'yi': return { value: number / 1e8, label: '亿' }
    case 'auto':
      if (abs >= 1e8) return { value: number / 1e8, label: '亿' }
      if (abs >= 1e4) return { value: number / 1e4, label: '万' }
      return { value: number, label: '' }
    default: return { value: number, label: '' }
  }
}

/**
 * 数值 → 展示文本：精度、千分位、计数单位、前后缀。
 *
 * <p>百分比只补「%」，不乘 100——数据集里的比率有的已经是 0-100，有的是 0-1，
 * 自动缩放反而会把一半人的数字弄错。
 */
export function formatMetricValue(value, metric) {
  if (value === null || value === undefined || value === '') return '—'
  const format = metricFormat(metric)
  if (format.type === 'default') return String(value)
  const number = Number(value)
  if (!Number.isFinite(number)) return `${format.prefix}${value}${format.suffix}`
  const scaled = scaleByUnit(number, format.unit)
  let text = scaled.value.toLocaleString('zh-CN', {
    minimumFractionDigits: format.precision,
    maximumFractionDigits: format.precision,
    useGrouping: format.thousands
  })
  if (format.type === 'percent') text += '%'
  return `${format.prefix}${text}${scaled.label}${format.suffix}`
}

/** 菜单里那行小字：数值 · 2 位 · 万 */
export function metricFormatLabel(metric) {
  const format = metricFormat(metric)
  const type = FORMAT_TYPES.find(item => item.value === format.type)?.label || '默认'
  if (format.type === 'default') return type
  const unit = COUNT_UNITS.find(item => item.value === format.unit)?.label || ''
  return [type, `${format.precision} 位`, format.unit !== 'none' ? unit : ''].filter(Boolean).join(' · ')
}

export const DATASET_LABELS = { TABLE: '库表', SQL: 'SQL', EXCEL: 'Excel', EXTRACT: '离线' }

export const COMPARATORS = ['=', '<>', '>', '>=', '<', '<=', 'LIKE', 'NOT LIKE', 'IS NULL', 'IS NOT NULL']

export function cardMeta(type) {
  return CARD_TYPES.find(item => item.type === type) || CARD_TYPES[0]
}

/** 单卡最多取多少行，和后端 cdc.bi.max-rows 保持一致。 */
export const MAX_LIMIT = 20000

/** 新建卡片时的配置骨架：各类型字段不同，但结构统一，后端按缺省值解析。 */
export function blankConfig(type) {
  // 默认不限制：以前给 100 行，看图的人会以为数据只有这么多
  const base = { dimensions: [], metrics: [], filters: [], sort: { by: 'none', index: 0, dir: 'desc' }, limit: MAX_LIMIT, text: '' }
  if (type === 'text') return { ...base, limit: 1 }
  if (type === 'kpi') return { ...base, limit: 1 }
  if (type === 'table') return { ...base, pagination: false, pageSize: 50, showRowIndex: false }
  return base
}

/** 卡片没起名时的兜底标题。 */
export function cardTitle(card) {
  return card.title && card.title.trim() ? card.title.trim() : cardMeta(card.type).label
}

/** 一条筛选的短描述，茶壶和空态提示共用。 */
export function filterLabel(filter) {
  if (!filter?.column) return ''
  if (filter.op === 'IN' || filter.op === 'NOT IN') {
    const values = Array.isArray(filter.value) ? filter.value : []
    const prefix = filter.op === 'NOT IN' ? '排除 ' : ''
    if (!values.length) return `${prefix}${filter.column}`
    return `${prefix}${filter.column}：${values.slice(0, 2).join('、')}${values.length > 2 ? ` 等 ${values.length} 项` : ''}`
  }
  if (filter.op === 'BETWEEN') {
    const range = Array.isArray(filter.value) ? filter.value : []
    if (range[0] == null && range[1] == null) return filter.column
    return `${filter.column} ${range[0] ?? '…'} ~ ${range[1] ?? '…'}`
  }
  if (filter.op === 'IS NULL' || filter.op === 'IS NOT NULL') return `${filter.column} ${filter.op}`
  if (filter.value === '' || filter.value == null) return filter.column
  return `${filter.column} ${filter.op} ${filter.value}`
}

/** 卡片上是否有真正生效的筛选（没填完的条件不算）。 */
export function hasActiveFilter(card) {
  const filters = card?.config?.filters || []
  return filters.some(filter => {
    if (!filter?.column) return false
    if (filter.op === 'IS NULL' || filter.op === 'IS NOT NULL') return true
    if (filter.op === 'IN' || filter.op === 'NOT IN') return Array.isArray(filter.value) && filter.value.length > 0
    if (filter.op === 'BETWEEN') {
      return Array.isArray(filter.value) && filter.value.some(item => item !== null && item !== '' && item !== undefined)
    }
    return filter.value !== '' && filter.value != null
  })
}

/**
 * 卡片还差什么才能出图。编辑器预览和画布都用它：
 * 不满足时**不取数、不渲染图形**，只在卡片中间提示缺什么。
 *
 * <p>和 validateCard 的区别：这里只在「画不出来」时返回未完成，
 * 比如柱状图有 1 个维度 0 个数值、散点图只有 1 个数值。
 */
export function cardRequirement(card) {
  const type = card?.type
  if (!type || type === 'text') return { complete: true, text: '' }
  if (card.datasetId == null) return { complete: false, text: '请先选择数据集' }
  const config = card.config || {}
  const dimensions = (config.dimensions || []).length
  const metrics = (config.metrics || []).length

  if (type === 'table') {
    return dimensions + metrics > 0
      ? { complete: true, text: '' }
      : { complete: false, text: '建议至少 1 个字段' }
  }
  if (type === 'kpi') {
    return metrics > 0 ? { complete: true, text: '' } : { complete: false, text: '建议 1 个数值' }
  }
  if (type === 'scatter') {
    // 散点要两个数值分别当 x / y，只有维度画不出点
    if (dimensions > 0 && metrics >= 2) return { complete: true, text: '' }
    if (!dimensions && !metrics) return { complete: false, text: '建议 1 个维度，2 个数值' }
    if (!dimensions) return { complete: false, text: '还需要 1 个维度' }
    if (!metrics) return { complete: false, text: '还需要 2 个数值（X 轴 / Y 轴）' }
    return { complete: false, text: '还需要 1 个数值（X 轴 / Y 轴各一个）' }
  }
  // 其余图形：一个维度 + 至少一个数值
  if (dimensions > 0 && metrics > 0) return { complete: true, text: '' }
  const combo = type === 'combo'
  if (!dimensions && !metrics) {
    return { complete: false, text: combo ? '建议 1 个维度，2 个数值（柱 + 线）' : '建议 1 个维度，1 个数值' }
  }
  if (!dimensions) return { complete: false, text: '还需要 1 个维度' }
  return { complete: false, text: combo ? '还需要 1 个数值（叠加折线）' : '还需要 1 个数值' }
}

/** 与后端 validateCards 对齐的本地校验；返回第一条问题，没问题返回空字符串。 */
export function validateCard(card) {
  if (!card) return ''
  if (card.datasetId == null) return ''
  const config = card.config || {}
  const dimensions = config.dimensions || []
  const metrics = config.metrics || []
  switch (card.type) {
    case 'kpi':
      return metrics.length === 1 ? '' : '指标卡需要 1 个度量'
    case 'pie':
    case 'funnel':
      return dimensions.length === 1 && metrics.length === 1 ? '' : `${CARD_LABELS[card.type]}需要 1 个维度和 1 个度量`
    case 'bar':
    case 'hbar':
    case 'stack':
    case 'line':
    case 'area':
    case 'combo':
      if (dimensions.length !== 1) return '需要一个维度'
      if (metrics.length < 1 || metrics.length > 4) return '需要 1-4 个度量'
      return ''
    case 'scatter':
      // 散点要两个度量分别当 x / y，维度只用来给点上标签
      if (dimensions.length !== 1) return '需要一个维度'
      return metrics.length === 2 ? '' : '散点图需要 2 个度量'
    case 'table':
      if (dimensions.length + metrics.length === 0) return '至少要选一个字段'
      if (dimensions.length > 8 || metrics.length > 8) return '最多 8 个维度 + 8 个度量'
      return ''
    default:
      return ''
  }
}

/** 后端保存时只认这些字段，发请求前做一次瘦身，避免把 `_k` 之类的本地字段带上。 */
export function toCardPayload(card) {
  return {
    id: card.id ?? null,
    type: card.type,
    title: card.title || '',
    datasetId: card.datasetId ?? null,
    x: card.x, y: card.y, w: card.w, h: card.h,
    config: card.config || {}
  }
}
