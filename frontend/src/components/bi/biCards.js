/**
 * 卡片类型注册表：面板、默认配置、默认尺寸、校验规则都集中在这里。
 *
 * <p>校验只是「让用户在编辑器里少犯错」的引导，真正的权威在后端
 * （BiDashboardService.validateCards）——两边规则必须一致，否则会出现
 * 「编辑器放行、保存被拒」。
 */
import { BarChart3, Hash, LineChart, PieChart, Table2, Type } from 'lucide-vue-next'

export const CARD_TYPES = [
  { type: 'bar', label: '柱状图', icon: BarChart3, w: 6, h: 8 },
  { type: 'line', label: '折线图', icon: LineChart, w: 6, h: 8 },
  { type: 'pie', label: '饼图', icon: PieChart, w: 4, h: 8 },
  { type: 'table', label: '数据表', icon: Table2, w: 8, h: 8 },
  { type: 'kpi', label: '指标卡', icon: Hash, w: 3, h: 6 },
  { type: 'text', label: '文本', icon: Type, w: 6, h: 4 }
]

export const CARD_LABELS = Object.fromEntries(CARD_TYPES.map(item => [item.type, item.label]))

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

export const DATASET_LABELS = { TABLE: '库表', SQL: 'SQL', EXCEL: 'Excel', EXTRACT: '离线' }

export const COMPARATORS = ['=', '<>', '>', '>=', '<', '<=', 'LIKE', 'NOT LIKE', 'IS NULL', 'IS NOT NULL']

export function cardMeta(type) {
  return CARD_TYPES.find(item => item.type === type) || CARD_TYPES[0]
}

/** 单卡最多取多少行，和后端 cdc.bi.max-rows 保持一致。 */
export const MAX_LIMIT = 20000

/** 新建卡片时的配置骨架：各类型字段不同，但结构统一，后端按缺省值解析。 */
export function blankConfig(type) {
  const base = { dimensions: [], metrics: [], filters: [], sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100, text: '' }
  if (type === 'text') return { ...base, limit: 1 }
  if (type === 'kpi') return { ...base, limit: 1 }
  // 明细表默认要全部数据：留 100 行会让人以为数据只有这么多
  if (type === 'table') return { ...base, limit: MAX_LIMIT }
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
      return dimensions.length === 1 && metrics.length === 1 ? '' : '饼图需要 1 个维度和 1 个度量'
    case 'bar':
    case 'line':
      if (dimensions.length !== 1) return '需要一个维度'
      if (metrics.length < 1 || metrics.length > 4) return '需要 1-4 个度量'
      return ''
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
