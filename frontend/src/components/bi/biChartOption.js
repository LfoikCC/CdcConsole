/**
 * 卡片数据 → ECharts option。
 *
 * <p>纯函数：给定卡片配置与查询结果（columns + rows），返回可直接 setOption 的对象。
 * 主题相关的部分（配色、tooltip、坐标轴样式）全部来自 chartStyle/chartAxis，
 * 所以调用方必须在 draw() 里**现调**本函数，不能缓存结果——切主题时要重算。
 */
import { chartAxis, chartStyle } from '../../chartStyle'

const num = value => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** bar / line / pie 走 echarts；kpi / table / text 由各自组件渲染，返回 null。 */
export function chartOptionFor(card, data) {
  const type = card?.type
  if (type !== 'bar' && type !== 'line' && type !== 'pie') return null
  const columns = data?.columns || []
  const rows = data?.rows || []
  if (!rows.length) return null
  return type === 'pie' ? pieOption(card, columns, rows) : axisOption(card, columns, rows, type)
}

function axisOption(card, columns, rows, type) {
  const series = seriesNames(columns)
  const config = card?.config || {}
  const axes = (config.metrics || []).map(metric => (metric.axis === 'right' ? 1 : 0))
  const twoAxes = axes.includes(1)
  const base = chartStyle()
  const left = { ...chartAxis(), type: 'value', minInterval: 1, name: twoAxes ? config.metrics?.[0]?.alias || '' : '' }
  return {
    ...base,
    // 单条序列时图例是噪音；用户明确关掉时也不画
    // 用户显式打开就一定要显示（哪怕只有一条序列）；没设置过时只有多序列才显示
    legend: config.showLegend === true || (config.showLegend !== false && series.length > 1)
      ? base.legend
      : { show: false },
    grid: { ...base.grid, right: twoAxes ? 56 : base.grid.right },
    xAxis: {
      ...chartAxis(),
      type: 'category',
      data: rows.map(row => text(row[0])),
      splitLine: { show: false }
    },
    yAxis: twoAxes ? [left, { ...chartAxis(), type: 'value', splitLine: { show: false } }] : left,
    series: series.map((label, index) => ({
      name: label,
      type,
      barMaxWidth: 28,
      showSymbol: type === 'line' && rows.length <= 12,
      smooth: false,
      yAxisIndex: twoAxes ? (axes[index] ?? 0) : 0,
      label: { show: config.showLabel === true, position: 'top', fontSize: 11 },
      data: rows.map(row => num(row[index + 1]))
    }))
  }
}

function pieOption(card, columns, rows) {
  const base = chartStyle()
  const config = card?.config || {}
  return {
    ...base,
    // chartStyle 默认 trigger:'axis'，饼图必须按扇区触发，否则 tooltip 显示的是错的
    tooltip: { ...base.tooltip, trigger: 'item', axisPointer: undefined },
    legend: config.showLegend === false ? { show: false } : { ...base.legend, type: 'scroll' },
    series: [{
      name: seriesNames(columns)[0] || '数值',
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      label: { show: true, formatter: '{b} {d}%', fontSize: 11 },
      emphasis: { label: { show: true } },
      data: rows.map(row => ({ name: text(row[0]), value: num(row[1]) ?? 0 }))
    }]
  }
}

/** 度量名直接取后端返回的 labels（第一列是维度，后面是度量）。 */
function seriesNames(columns) {
  const labels = (columns || []).slice(1).filter(label => label != null && label !== '')
  return labels.length ? labels : ['数值']
}

function text(value) {
  return value === null || value === undefined ? '—' : String(value)
}

/** 指标卡的显示值：取第一行第一列（无维度时只有一行）。 */
export function kpiValue(data) {
  const row = data?.rows?.[0]
  if (!row || !row.length) return null
  return row[0]
}

export function formatNumber(value) {
  if (value === null || value === undefined || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return String(value)
  if (Number.isInteger(number)) return number.toLocaleString('zh-CN')
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
