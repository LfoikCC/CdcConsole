/**
 * 卡片数据 → ECharts option。
 *
 * <p>纯函数：给定卡片配置与查询结果（columns + rows），返回可直接 setOption 的对象。
 * 主题相关的部分（配色、tooltip、坐标轴样式）全部来自 chartStyle/chartAxis，
 * 所以调用方必须在 draw() 里**现调**本函数，不能缓存结果——切主题时要重算。
 */
import { chartAxis, chartStyle } from '../../chartStyle'
import { AXIS_TYPES, formatMetricValue, hasMetricFormat, metricFormat } from './biCards'

const num = value => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 各类卡片 → ECharts option：
 * 直角坐标系（柱 / 条 / 折线 / 面积 / 堆积 / 组合）、散点、饼 / 漏斗。
 * 表格、指标卡、文本由各自组件渲染，这里返回 null。
 */
export function chartOptionFor(card, data) {
  const type = card?.type
  const columns = data?.columns || []
  const rows = data?.rows || []
  if (!rows.length) return null
  if (type === 'pie' || type === 'funnel') return shareOption(card, columns, rows, type)
  if (type === 'scatter') return scatterOption(card, columns, rows)
  if (!AXIS_TYPES.includes(type)) return null
  return axisOption(card, columns, rows, type)
}

/** 设过数据格式就按格式渲染，否则原样交给 ECharts。 */
function valueText(value, metric) {
  return hasMetricFormat(metric) ? formatMetricValue(value, metric) : value
}

/**
 * 轴刻度专用格式化。
 *
 * <p>`unit: 'auto'` 是逐值挑单位的，同一根轴上会出现
 * 「0.00 / 5,000.00万 / 1.00亿 / 1.50亿」这种混排，看着像数据错了。
 * 轴这里按整根轴的最大值先定一个单位，所有刻度统一用它。
 */
function axisValueFormatter(metric, maxValue) {
  if (!hasMetricFormat(metric)) return value => value
  const format = metricFormat(metric)
  if (format.unit !== 'auto') return value => valueText(value, metric)
  const unit = maxValue >= 1e8 ? 'yi' : (maxValue >= 1e4 ? 'wan' : 'none')
  const pinned = { ...metric, format: { ...format, unit } }
  return value => valueText(value, pinned)
}

/** 这一条序列该画成柱还是线：组合图第一项柱、其余线。 */
function seriesKind(type, index) {
  if (type === 'line' || type === 'area') return 'line'
  if (type === 'combo') return index === 0 ? 'bar' : 'line'
  return 'bar'
}

function axisOption(card, columns, rows, type) {
  const labels = seriesNames(columns)
  const config = card?.config || {}
  // 条形图把分类轴竖起来：x 是数值、y 是分类，其余类型相反
  const horizontal = type === 'hbar'
  const metrics = config.metrics || []
  const axes = metrics.map(metric => (metric.axis === 'right' ? 1 : 0))
  const twoAxes = !horizontal && axes.includes(1)
  const primary = metrics[0] || null
  const rightMetric = metrics.find(metric => metric.axis === 'right') || null
  const base = chartStyle()
  // 所有度量列的最大绝对值：轴刻度定单位时要用
  const axisMax = metrics.reduce((max, _, index) => rows.reduce(
    (inner, row) => Math.max(inner, Math.abs(Number(row[index + 1]) || 0)), max), 0)
  const categoryAxis = {
    ...chartAxis(),
    type: 'category',
    data: rows.map(row => text(row[0])),
    splitLine: { show: false }
  }
  const axisBase = chartAxis()
  const valueAxis = {
    ...axisBase,
    type: 'value',
    minInterval: 1,
    name: twoAxes ? primary?.alias || '' : '',
    // 轴刻度也按字段格式显示（千分位、前后缀），否则轴上和提示里的数字对不上
    axisLabel: { ...axisBase.axisLabel, formatter: axisValueFormatter(primary, axisMax) }
  }
  // 第二根轴恒定存在，只靠 show 控制：合并更新时 yAxis 在「对象 ↔ 数组」之间切换，
  // ECharts 会留下上一轮的轴，右边缘就多出一截空白。
  const secondaryAxis = {
    ...axisBase,
    type: 'value',
    show: twoAxes,
    splitLine: { show: false },
    axisLabel: { ...axisBase.axisLabel, formatter: axisValueFormatter(rightMetric, axisMax) }
  }
  return {
    ...base,
    // 默认不画图例：图例会挤掉绘图区，而且颜色和分类在轴/提示里已经能看到。
    // 只有用户显式打开才显示——以前「多序列默认显示」，所以才会时有时无。
    legend: config.showLegend === true ? { ...base.legend, show: true } : { show: false },
    // 条形图的数值标签挂在柱子右侧，最长的柱子会把标签顶出绘图区，
    // 右边多留一点，否则最后那根的数只显示一半
    grid: {
      ...base.grid,
      right: horizontal ? 76 : (twoAxes ? 56 : base.grid.right),
      ...(horizontal ? { bottom: 40 } : {})
    },
    // 条形图：提示框保留分类和系列值，去掉上面那行 x 轴数值——
    // 同一个数在下面已经显示过一次，头部再顶一行既重复又抢视线
    tooltip: horizontal
      ? {
          ...base.tooltip,
          axisPointer: { ...base.tooltip.axisPointer, label: { show: false } },
          formatter: params => {
            const list = Array.isArray(params) ? params : [params]
            if (!list.length) return ''
            const title = text(rows[list[0].dataIndex]?.[0])
            // 横向图的两个轴都会回传参数，按系列去重，否则同一条数值会列两遍
            const seen = new Set()
            const lines = []
            for (const item of list) {
              if (seen.has(item.seriesIndex)) continue
              seen.add(item.seriesIndex)
              lines.push(`${item.marker}${item.seriesName}　${valueText(item.value, metrics[item.seriesIndex])}`)
            }
            return [title, ...lines].join('<br/>')
          }
        }
      // 别写 undefined：对象展开后再把 tooltip 置空，等于整张图没有提示框，
      // 柱状/折线/面积图的数值浮窗就全没了
      : base.tooltip,
    // 条形图的指针在数值轴上，标签要挂到轴自己身上才压得住（tooltip.axisPointer 只管默认值）
    xAxis: horizontal
      ? { ...valueAxis, axisPointer: { label: { show: false } } }
      : categoryAxis,
    yAxis: [horizontal ? categoryAxis : valueAxis, secondaryAxis],
    series: labels.map((label, index) => {
      const kind = seriesKind(type, index)
      const metric = metrics[index] || null
      return {
        name: label,
        type: kind,
        barMaxWidth: 28,
        // 堆积：所有度量叠在同一根柱子上
        stack: type === 'stack' ? 'total' : undefined,
        areaStyle: type === 'area' ? { opacity: 0.22 } : undefined,
        // 折线的数据标签挂在数据点上，showSymbol 关掉后 ECharts 连标签一起不画。
        // 所以只要开了数据标签就必须画点，不能为了「点太密」把它关掉。
        showSymbol: kind === 'line' && (rows.length <= 12 || config.showLabel === true),
        smooth: false,
        yAxisIndex: twoAxes ? (axes[index] ?? 0) : 0,
        label: {
          show: config.showLabel === true,
          // 条形图是横着长的，标签挂「上面」会压在柱子上，要放右侧
          position: horizontal ? 'right' : 'top',
          fontSize: 11,
          // 默认继承系列色，折线的浅蓝压在浅底上几乎看不见，统一用前景色
          color: base.textStyle?.color,
          // 一定要回字符串：formatter 返回非字符串时部分渲染路径会直接跳过
          formatter: params => {
            const value = valueText(params.value, metric)
            return value === null || value === undefined ? '' : String(value)
          }
        },
        // 提示框里的数值也走同一个格式
        tooltip: hasMetricFormat(metric) ? { valueFormatter: value => valueText(value, metric) } : undefined,
        data: rows.map(row => num(row[index + 1]))
      }
    })
  }
}

/** 饼图 / 漏斗图：都是「一个维度 + 一个度量」看占比。 */
function shareOption(card, columns, rows, type) {
  const base = chartStyle()
  const config = card?.config || {}
  const metric = config.metrics?.[0] || null
  const name = seriesNames(columns)[0] || '数值'
  const data = rows.map(row => ({ name: text(row[0]), value: num(row[1]) ?? 0 }))
  const legend = config.showLegend === true
    ? { ...base.legend, type: 'scroll', show: true }
    : { show: false }
  // 提示框里的数值按字段格式显示（饼图的标签仍然是占比，不受影响）
  const valueTip = hasMetricFormat(metric)
    ? params => `${params.name}<br/>${valueText(params.value, metric)}（${params.percent}%）`
    : '{b}: {c}（{d}%）'
  if (type === 'funnel') {
    return {
      ...base,
      tooltip: { ...base.tooltip, trigger: 'item', axisPointer: undefined, formatter: valueTip },
      legend,
      series: [{
        name,
        type: 'funnel',
        left: '8%',
        right: '8%',
        top: 16,
        bottom: 52,
        minSize: '18%',
        sort: 'descending',
        gap: 2,
        label: { show: true, position: 'inside', fontSize: 11, formatter: '{b} {d}%' },
        itemStyle: { borderWidth: 0 },
        data
      }]
    }
  }
  return {
    ...base,
    // chartStyle 默认 trigger:'axis'，饼图必须按扇区触发，否则 tooltip 显示的是错的
    tooltip: { ...base.tooltip, trigger: 'item', axisPointer: undefined, formatter: valueTip },
    legend,
    series: [{
      name,
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      label: { show: true, formatter: '{b} {d}%', fontSize: 11 },
      emphasis: { label: { show: true } },
      data
    }]
  }
}

/** 散点图：维度当标签，两个度量分别是 x / y。 */
function scatterOption(card, columns, rows) {
  const base = chartStyle()
  const config = card?.config || {}
  const metrics = config.metrics || []
  const xName = metrics[0]?.alias || columns[1] || ''
  const yName = metrics[1]?.alias || columns[2] || ''
  return {
    ...base,
    tooltip: {
      ...base.tooltip,
      trigger: 'item',
      axisPointer: undefined,
      formatter: params => `${params.data.name}<br/>${xName}：${valueText(params.value[0], metrics[0])}`
        + `<br/>${yName}：${valueText(params.value[1], metrics[1])}`
    },
    legend: { show: false },
    // 两个轴都要标题，底部留够位置
    grid: { ...base.grid, bottom: 56, left: 24 },
    xAxis: { ...chartAxis(), type: 'value', name: xName, nameLocation: 'middle', nameGap: 30 },
    yAxis: { ...chartAxis(), type: 'value', name: yName, nameLocation: 'middle', nameGap: 46 },
    series: [{
      type: 'scatter',
      symbolSize: 9,
      // 点本身没有轴标签，打开「数据标签」时把维度名标在点旁边
      label: { show: config.showLabel === true, position: 'right', fontSize: 10, formatter: '{b}' },
      data: rows.map(row => ({ name: text(row[0]), value: [num(row[1]), num(row[2])] }))
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
