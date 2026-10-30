import {
  ArrowUpDown, Calculator, Code2, Columns3, Copy, Database, Eraser, ListFilter,
  Merge, Replace, Sigma, SlidersHorizontal, Upload
} from 'lucide-vue-next'

export const OPERATOR_LABELS = {
  PROJECT: '字段选择',
  FILTER: '行过滤',
  SORT: '排序',
  DEDUP: '去重',
  AGGREGATE: '分组聚合',
  CALCULATE: '添加计算列',
  REPLACE_VALUE: '值替换',
  REPLACE_NULL: 'Null 值替换',
  MERGE_COLUMNS: '合并列'
}

export const OPERATOR_OPTIONS = Object.entries(OPERATOR_LABELS).map(([value, label]) => ({ value, label }))

/** 每个算子一个图标：九个算子长得一模一样时，画布上根本认不出哪张卡在做什么。 */
export const OPERATOR_ICONS = {
  PROJECT: Columns3,
  FILTER: ListFilter,
  SORT: ArrowUpDown,
  DEDUP: Copy,
  AGGREGATE: Sigma,
  CALCULATE: Calculator,
  REPLACE_VALUE: Replace,
  REPLACE_NULL: Eraser,
  MERGE_COLUMNS: Merge
}

/**
 * 每个算子独立的默认配置。
 *
 * <p>以前 SORT 和 DEDUP 共用一个 `keys` 字段：前者存 `[{column,direction}]`，后者存 `[string]`。
 * 从 SORT 切到 DEDUP 时残留对象会被 String.valueOf 成 `{column=amount, direction=DESC}`，
 * 然后报「上游没有列 {...}」；反向切换更阴——对象被静默跳过，退化成「不排序」，运行成功但数据是错的。
 * 所以这里按算子各给一份，切换时整份替换。
 */
export const OPERATOR_DEFAULTS = {
  PROJECT: () => ({ operator: 'PROJECT', items: [] }),
  FILTER: () => ({ operator: 'FILTER', join: 'AND', conditions: [] }),
  SORT: () => ({ operator: 'SORT', keys: [] }),
  DEDUP: () => ({ operator: 'DEDUP', keys: [] }),
  AGGREGATE: () => ({ operator: 'AGGREGATE', groupBy: [], aggregates: [] }),
  CALCULATE: () => ({ operator: 'CALCULATE', alias: '', expression: '', type: 'DECIMAL(38,10)' }),
  REPLACE_VALUE: () => ({ operator: 'REPLACE_VALUE', rules: [] }),
  REPLACE_NULL: () => ({ operator: 'REPLACE_NULL', fills: [] }),
  MERGE_COLUMNS: () => ({
    operator: 'MERGE_COLUMNS', columns: [], separator: '-', alias: '', dropSources: false
  })
}

export function operatorDefaults(operator) {
  const factory = OPERATOR_DEFAULTS[operator] || OPERATOR_DEFAULTS.PROJECT
  return factory()
}

export const MODE_LABELS = {
  OVERWRITE: '覆盖',
  APPEND: '追加',
  UPSERT: '按主键 upsert'
}

export const MODE_OPTIONS = Object.entries(MODE_LABELS).map(([value, label]) => ({ value, label }))

export const COMPARATORS = [
  '=', '<>', '>', '>=', '<', '<=', 'LIKE', 'NOT LIKE', 'IS NULL', 'IS NOT NULL', 'IN', 'BETWEEN'
]

export const AGG_FUNCTIONS = ['SUM', 'COUNT', 'COUNT_DISTINCT', 'AVG', 'MIN', 'MAX']

export const CAST_TYPES = [
  'INTEGER', 'BIGINT', 'DECIMAL(38,10)', 'DOUBLE', 'VARCHAR', 'DATE', 'TIME', 'TIMESTAMP', 'BOOLEAN'
]

export const NODE_TYPES = {
  DB_INPUT: {
    label: '数据库输入',
    icon: Database,
    tone: 'src',
    defaults: () => ({ sourceId: null, database: '', table: '', columns: [], where: '' }),
    // 带库名前缀：标题已经显示节点名，副标题再重复表名就没有信息量了
    subtitle: config => (config.table
      ? `${config.database ? config.database + '.' : ''}${config.table}`
      : '未选择表')
  },
  SQL_INPUT: {
    label: 'SQL 输入',
    icon: Code2,
    tone: 'sql',
    defaults: () => ({ sourceId: null, database: '', sql: '' }),
    subtitle: config => (config.sql ? '自定义查询' : '未填写 SQL')
  },
  OPERATOR: {
    label: '算子',
    icon: SlidersHorizontal,
    tone: 'op',
    defaults: () => operatorDefaults('PROJECT'),
    subtitle: config => OPERATOR_LABELS[config.operator] || '算子'
  },
  DB_OUTPUT: {
    label: '数据库输出',
    icon: Upload,
    tone: 'sink',
    defaults: () => ({ targetId: null, table: '', mode: 'OVERWRITE', targetColumns: [], keyColumns: [] }),
    subtitle: config => `${config.database ? config.database + '.' : ''}${config.table || '未选择表'} · ${MODE_LABELS[config.mode] || '覆盖'}`
  }
}

export const NODE_TYPE_OPTIONS = Object.entries(NODE_TYPES).map(([value, meta]) => ({
  value,
  label: meta.label
}))

/**
 * 调色板：输入 2 项、算子 9 项、输出 1 项。
 *
 * <p>节点类型仍是 OPERATOR，算子只体现在 config.operator 上——图模型与执行器都不用改。
 */
export const PALETTE_GROUPS = [
  {
    label: '输入',
    items: [
      { type: 'DB_INPUT', label: NODE_TYPES.DB_INPUT.label, icon: Database, tone: 'src' },
      { type: 'SQL_INPUT', label: NODE_TYPES.SQL_INPUT.label, icon: Code2, tone: 'sql' }
    ]
  },
  {
    label: '算子',
    items: Object.entries(OPERATOR_LABELS).map(([operator, label]) => ({
      type: 'OPERATOR', operator, label, icon: OPERATOR_ICONS[operator], tone: 'op'
    }))
  },
  {
    label: '输出',
    items: [
      { type: 'DB_OUTPUT', label: NODE_TYPES.DB_OUTPUT.label, icon: Upload, tone: 'sink' }
    ]
  }
]

export function metaOf(type) {
  return NODE_TYPES[type] || { label: type, icon: Database, tone: 'op', defaults: () => ({}), subtitle: () => '' }
}

/** 节点卡片/抽屉的图标：算子节点按具体算子取，其余按类型取。 */
export function iconOf(node) {
  if (node?.type === 'OPERATOR') {
    return OPERATOR_ICONS[node.config?.operator] || SlidersHorizontal
  }
  return metaOf(node?.type).icon
}

export function createNode(type, id, x, y, operator) {
  const meta = metaOf(type)
  const config = type === 'OPERATOR' ? operatorDefaults(operator) : meta.defaults()
  const name = type === 'OPERATOR' ? (OPERATOR_LABELS[config.operator] || meta.label) : meta.label
  return { id, type, x, y, name, config }
}
