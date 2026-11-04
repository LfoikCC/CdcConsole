import {
  ArrowUpDown, Calculator, Code2, Columns3, Combine, Copy, Database, DatabaseZap, Eraser, GitMerge,
  Layers, ListFilter, Merge, Replace, Save, Sigma, SlidersHorizontal, SquareCode, Table2, TableProperties, Upload
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
  MERGE_COLUMNS: '合并列',
  UNION: '行拼接',
  JOIN: '关联数据',
  PIVOT: '行转列',
  UNPIVOT: '列转行',
  SQL: 'SQL 算子'
}

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
  MERGE_COLUMNS: Merge,
  UNION: Combine,
  JOIN: GitMerge,
  PIVOT: TableProperties,
  UNPIVOT: Table2,
  SQL: SquareCode
}

/** 需要两个上游的算子：画布上要画第二个入端口，校验器要求恰好两条入边。 */
export const TWO_INPUT_OPERATORS = ['UNION', 'JOIN']

/** 只有出口的数据源节点：不该被连入。 */
export const SOURCE_ONLY_TYPES = ['DB_INPUT', 'SQL_INPUT', 'DATASET_INPUT']
/** 只有入口的落地节点：不能当作上游。 */
export const SINK_ONLY_TYPES = ['DB_OUTPUT', 'DATASET_OUTPUT']

/** 这个节点能否接收上游连线。 */
export function acceptsInput(node) {
  return !SOURCE_ONLY_TYPES.includes(node?.type)
}

/** 这个节点能否作为别的节点的上游。 */
export function canBeUpstream(node) {
  return !SINK_ONLY_TYPES.includes(node?.type)
}

export function needsTwoInputs(node) {
  return node?.type === 'OPERATOR' && TWO_INPUT_OPERATORS.includes(node.config?.operator)
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
  }),
  UNION: () => ({ operator: 'UNION', mode: 'ALL' }),
  JOIN: () => ({ operator: 'JOIN', joinType: 'INNER', keys: [] }),
  PIVOT: () => ({
    operator: 'PIVOT', groupBy: [], pivotColumn: '', valueColumn: '', aggregate: 'SUM', values: []
  }),
  UNPIVOT: () => ({ operator: 'UNPIVOT', columns: [], nameColumn: '字段', valueColumn: '值' }),
  SQL: () => ({ operator: 'SQL', sql: '' })
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
    // 数据库输入统一走 SQL 查询；previewMode/limit 控制运行时的取数上限
    defaults: () => ({ sourceId: null, mode: 'SQL', database: '', sql: '', previewMode: 'LIMIT', limit: 10000 }),
    // 带库名前缀：标题已经显示节点名，副标题再重复表名就没有信息量了
    subtitle: config => {
      if (config.mode === 'SQL') return config.sql ? '自定义查询' : '未填写 SQL'
      return config.table ? `${config.database ? config.database + '.' : ''}${config.table}` : '未选择表'
    }
  },
  /** 旧版「SQL 输入」节点：不再出现在算子面板里，只用于兼容已保存的数据流。 */
  SQL_INPUT: {
    label: 'SQL 输入',
    icon: Code2,
    tone: 'sql',
    defaults: () => ({ sourceId: null, mode: 'SQL', database: '', sql: '', previewMode: 'LIMIT', limit: 10000 }),
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
  },
  DATASET_INPUT: {
    label: '数据集输入',
    icon: Layers,
    tone: 'src',
    defaults: () => ({ datasetId: null }),
    subtitle: config => (config.datasetId ? `数据集 #${config.datasetId}` : '未选择数据集')
  },
  DATASET_OUTPUT: {
    label: '数据集输出',
    icon: Save,
    tone: 'sink',
    defaults: () => ({ datasetId: null, mode: 'OVERWRITE' }),
    subtitle: config => (config.datasetId
      ? `数据集 #${config.datasetId} · ${MODE_LABELS[config.mode] || '覆盖'}`
      : '未选择数据集')
  }
}

export const NODE_TYPE_OPTIONS = Object.entries(NODE_TYPES).map(([value, meta]) => ({
  value,
  label: meta.label
}))

/** 算子的功能分组：14 个算子按「列 / 行 / 聚合 / 多表 / 高级」归类，便于在 2 列栅格里快速定位。 */
const OPERATOR_GROUPS = [
  { label: '列编辑', operators: ['PROJECT', 'CALCULATE', 'MERGE_COLUMNS', 'REPLACE_VALUE', 'REPLACE_NULL'] },
  { label: '行处理', operators: ['FILTER', 'SORT', 'DEDUP'] },
  { label: '聚合', operators: ['AGGREGATE', 'PIVOT', 'UNPIVOT'] },
  { label: '多表', operators: ['UNION', 'JOIN'] },
  { label: '高级', operators: ['SQL'] }
]

/**
 * 调色板：输入/输出 + 五个算子分组，共 6 组 18 项。
 *
 * <p>节点类型仍是 OPERATOR，算子只体现在 config.operator 上——图模型与执行器都不用改。
 * 「SQL 输入」已经并进「数据库输入」（取数方式切换），这里不再单列。
 */
export const PALETTE_GROUPS = [
  {
    label: '输入/输出',
    items: [
      { type: 'DB_INPUT', label: NODE_TYPES.DB_INPUT.label, icon: Database, tone: 'src' },
      { type: 'DATASET_INPUT', label: NODE_TYPES.DATASET_INPUT.label, icon: Layers, tone: 'src' },
      { type: 'DB_OUTPUT', label: NODE_TYPES.DB_OUTPUT.label, icon: Upload, tone: 'sink' },
      { type: 'DATASET_OUTPUT', label: NODE_TYPES.DATASET_OUTPUT.label, icon: Save, tone: 'sink' }
    ]
  },
  ...OPERATOR_GROUPS.map(group => ({
    label: group.label,
    items: group.operators.map(operator => ({
      type: 'OPERATOR', operator, label: OPERATOR_LABELS[operator], icon: OPERATOR_ICONS[operator], tone: 'op'
    }))
  }))
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
