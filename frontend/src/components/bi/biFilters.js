/**
 * 看板筛选器：类型定义、条件翻译、文案。
 *
 * <p>结构参考观远 BI 的页面筛选器，但只保留我们查询层能落地的几种：
 * 选择、条件、范围、日期、组合条件。树状需要多级层级配置、参数需要参数体系，
 * 这两项目前没有对应能力，先不做半成品。
 *
 * <p>筛选器存成对象而不是 `{column, op, value}` 三元组：只有三元组的话
 * 前端没法把它渲染成一个控件（不知道是下拉、输入框还是日期区间）。
 */

export const FILTER_TYPES = [
  { value: 'select', label: '选择', hint: '从字段的取值列表里勾选' },
  { value: 'condition', label: '条件', hint: '输入关键词做模糊匹配' },
  { value: 'range', label: '范围', hint: '按数值区间过滤' },
  { value: 'date', label: '日期', hint: '快捷日期或自定义区间' },
  { value: 'combo', label: '组合条件', hint: '多个条件同时满足' }
]

/** 条件筛选器的匹配方式：全部翻译成 LIKE / = / <>。 */
export const CONDITION_OPS = [
  { value: 'CONTAINS', label: '包含' },
  { value: 'NOT_CONTAINS', label: '不包含' },
  { value: 'STARTS', label: '以…开始' },
  { value: 'ENDS', label: '以…结尾' },
  { value: '=', label: '等于' },
  { value: '<>', label: '不等于' }
]

/** 范围筛选器的比较方式，与后端 BiQueryBuilder.OPS 对齐。 */
export const RANGE_OPS = [
  { value: 'BETWEEN', label: '区间' },
  { value: '>', label: '大于' },
  { value: '>=', label: '大于等于' },
  { value: '<', label: '小于' },
  { value: '<=', label: '小于等于' },
  { value: '=', label: '等于' },
  { value: '<>', label: '不等于' },
  { value: 'IS NULL', label: '为空' },
  { value: 'IS NOT NULL', label: '不为空' }
]

/** 快捷日期：算出来就是一对起止日期，不用用户自己点日历。 */
export const QUICK_RANGES = [
  { value: 'today', label: '今天' },
  { value: 'yesterday', label: '昨天' },
  { value: 'last7', label: '最近 7 天' },
  { value: 'last30', label: '最近 30 天' },
  { value: 'thisMonth', label: '本月' },
  { value: 'lastMonth', label: '上月' },
  { value: 'thisQuarter', label: '本季度' },
  { value: 'thisYear', label: '今年' }
]

export const DATE_OPS = RANGE_OPS.filter(item => ['BETWEEN', '>=', '<=', '>', '<', '=', 'IS NULL', 'IS NOT NULL'].includes(item.value))

/** 作用范围：同数据集全部卡片，或者手动挑几张。 */
export const FILTER_SCOPES = [
  { value: 'dataset', label: '同数据集的全部卡片' },
  { value: 'cards', label: '指定的卡片' }
]

let seq = 0

/** 新建筛选器的骨架：默认值按类型给，切换类型时不会带上上一个类型的残留。 */
export function blankFilter(type = 'select') {
  seq += 1
  return {
    id: `f${Date.now().toString(36)}${seq}`,
    type,
    name: '',
    datasetId: null,
    column: '',
    // 选择
    multiple: true,
    values: [],
    exclude: false,
    // 条件
    op: 'CONTAINS',
    keyword: '',
    // 范围 / 日期
    valueOp: type === 'date' ? 'BETWEEN' : 'BETWEEN',
    from: '',
    to: '',
    quick: '',
    // 组合条件
    logic: 'AND',
    conditions: [],
    // 联动
    scope: 'dataset',
    cardIds: []
  }
}

/** 把历史数据（上一版的 {datasetId, column, op, value}）补成新结构，老看板打开不报错。 */
export function normalizeFilter(filter) {
  if (!filter || typeof filter !== 'object') return null
  if (filter.type) {
    const merged = { ...blankFilter(filter.type), ...filter }
    // 从多选改成单选后，旧值可能还留着好几个：单选只保留第一个
    if (merged.type === 'select' && merged.multiple === false) {
      merged.values = (merged.values || []).slice(0, 1)
    }
    return merged
  }
  const op = String(filter.op || '=').toUpperCase()
  const value = filter.value
  const base = blankFilter(['IN', 'NOT IN'].includes(op) ? 'select' : 'condition')
  base.datasetId = filter.datasetId ?? null
  base.column = filter.column || ''
  base.name = filter.column || ''
  if (base.type === 'select') {
    base.values = Array.isArray(value) ? value.map(String) : (value == null || value === '' ? [] : [String(value)])
    base.multiple = true
    base.exclude = op === 'NOT IN'
  } else {
    base.keyword = value == null ? '' : String(value)
    base.op = op === '<>' ? '<>' : (op === 'LIKE' ? 'CONTAINS' : '=')
  }
  return base
}

/** 快捷日期 → [起, 止]（含当天，YYYY-MM-DD）。 */
export function quickRange(value, today = new Date()) {
  const pad = number => String(number).padStart(2, '0')
  const text = date => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  const shift = days => {
    const next = new Date(today.getFullYear(), today.getMonth(), today.getDate())
    next.setDate(next.getDate() + days)
    return next
  }
  const firstOfMonth = offset => new Date(today.getFullYear(), today.getMonth() + offset, 1)
  const lastOfMonth = offset => new Date(today.getFullYear(), today.getMonth() + offset + 1, 0)
  switch (value) {
    case 'today': return [text(shift(0)), text(shift(0))]
    case 'yesterday': return [text(shift(-1)), text(shift(-1))]
    case 'last7': return [text(shift(-6)), text(shift(0))]
    case 'last30': return [text(shift(-29)), text(shift(0))]
    case 'thisMonth': return [text(firstOfMonth(0)), text(lastOfMonth(0))]
    case 'lastMonth': return [text(firstOfMonth(-1)), text(lastOfMonth(-1))]
    case 'thisQuarter': {
      const quarter = Math.floor(today.getMonth() / 3) * 3
      return [text(new Date(today.getFullYear(), quarter, 1)), text(new Date(today.getFullYear(), quarter + 3, 0))]
    }
    case 'thisYear': return [`${today.getFullYear()}-01-01`, `${today.getFullYear()}-12-31`]
    default: return ['', '']
  }
}

/** 条件筛选器 → 一个后端能吃的条件。关键词为空时返回 null（不参与过滤）。 */
function conditionOf(column, op, keyword) {
  const word = keyword == null ? '' : String(keyword)
  if (!word.trim()) return null
  switch (op) {
    case 'CONTAINS': return { column, op: 'LIKE', value: `%${word}%` }
    case 'NOT_CONTAINS': return { column, op: 'NOT LIKE', value: `%${word}%` }
    case 'STARTS': return { column, op: 'LIKE', value: `${word}%` }
    case 'ENDS': return { column, op: 'LIKE', value: `%${word}` }
    case '<>': return { column, op: '<>', value: word }
    default: return { column, op: '=', value: word }
  }
}

/** 区间类（范围 / 日期）→ 一个后端能吃的条件。 */
function rangeOf(column, op, from, to) {
  const start = from === '' || from == null ? null : from
  const end = to === '' || to == null ? null : to
  if (op === 'IS NULL' || op === 'IS NOT NULL') return { column, op, value: null }
  if (op === 'BETWEEN') {
    if (start == null && end == null) return null
    return { column, op: 'BETWEEN', value: [start, end] }
  }
  const single = start ?? end
  if (single == null) return null
  return { column, op, value: single }
}

/**
 * 一个筛选器 → 后端条件数组。
 *
 * <p>没填完的条件一律返回空数组：筛选器刚添加、值还空着的时候不能把卡片筛成空表。
 */
export function filterConditions(filter) {
  if (!filter) return []
  const column = filter.column
  switch (filter.type) {
    case 'select': {
      if (!column) return []
      const all = (filter.values || []).map(String).filter(item => item !== '')
      // 单选只取第一个：界面只显示一个值时，查询不能按好几个过滤
      const values = filter.multiple === false ? all.slice(0, 1) : all
      if (!values.length) return []
      const op = filter.exclude ? 'NOT IN' : 'IN'
      return [{ column, op, value: values }]
    }
    case 'condition': {
      if (!column) return []
      const single = conditionOf(column, filter.op, filter.keyword)
      return single ? [single] : []
    }
    case 'range':
    case 'date': {
      if (!column) return []
      const single = rangeOf(column, filter.valueOp, filter.from, filter.to)
      return single ? [single] : []
    }
    case 'combo': {
      const items = (filter.conditions || [])
        .map(item => conditionOf(item.column, item.op, item.value))
        .filter(Boolean)
      // 后端 where 只支持 AND 拼接；OR 目前只在单条件时等价
      return items
    }
    default: return []
  }
}

/** 这个筛选器会不会作用到某张卡：同数据集，且落在作用范围内。 */
export function filterAppliesTo(filter, datasetId, cardId) {
  if (!filter || Number(filter.datasetId) !== Number(datasetId)) return false
  if (filter.scope === 'cards') {
    if (cardId == null) return false
    return (filter.cardIds || []).some(id => Number(id) === Number(cardId))
  }
  return true
}

/** 某张卡该带上的全部筛选条件（卡片自己的筛选在外层拼）。 */
export function dashboardConditions(filters, datasetId, cardId) {
  return (filters || [])
    .filter(item => filterAppliesTo(item, datasetId, cardId))
    .flatMap(filterConditions)
}

/** 筛选器名字：没起名就用字段名。 */
export function filterName(filter) {
  return filter?.name || filter?.column || '筛选器'
}

/** 筛选栏上的一句话摘要，用于「已生效」的提示和只读页。 */
export function filterSummary(filter) {
  const name = filterName(filter)
  switch (filter?.type) {
    case 'select': {
      const values = filter.values || []
      if (!values.length) return `${name}：全部`
      const text = values.length > 3 ? `${values.slice(0, 3).join('、')} 等 ${values.length} 项` : values.join('、')
      return `${name}${filter.exclude ? ' 排除 ' : '：'}${text}`
    }
    case 'condition': {
      const op = CONDITION_OPS.find(item => item.value === filter.op)?.label || '包含'
      return `${name} ${op} ${filter.keyword || ''}`.trim()
    }
    case 'range':
    case 'date': {
      const op = RANGE_OPS.find(item => item.value === filter.valueOp)?.label || '区间'
      if (filter.valueOp === 'BETWEEN') {
        if (!filter.from && !filter.to) return `${name}：全部`
        return `${name}：${filter.from || '不限'} ~ ${filter.to || '不限'}`
      }
      if (filter.valueOp === 'IS NULL' || filter.valueOp === 'IS NOT NULL') return `${name} ${op}`
      return `${name} ${op} ${filter.from || filter.to || ''}`.trim()
    }
    case 'combo': {
      const count = (filter.conditions || []).filter(item => item.column && item.value).length
      return count ? `${name}：${count} 个条件` : `${name}：未配置`
    }
    default: return name
  }
}
