import { describe, expect, it } from 'vitest'
import { chartOptionFor, formatNumber, kpiValue } from './biChartOption'

const barCard = { type: 'bar', config: { metrics: [{ column: 'amount', agg: 'SUM' }, { column: 'id', agg: 'COUNT' }] } }
const pieCard = { type: 'pie' }
const data = { columns: ['region', '销售额', '订单数'], rows: [['华东', 1200.5, 12], ['华北', 800, 7]] }

describe('biChartOption', () => {
  it('builds a category axis and one series per metric', () => {
    const option = chartOptionFor(barCard, data)
    expect(option.xAxis.type).toBe('category')
    expect(option.xAxis.data).toEqual(['华东', '华北'])
    expect(option.series).toHaveLength(2)
    expect(option.series[0]).toMatchObject({ name: '销售额', type: 'bar' })
    expect(option.series[0].data).toEqual([1200.5, 800])
    // 图例默认关闭，哪怕有多条序列
    expect(option.legend.show).toBe(false)
  })

  it('hides the legend for a single-series chart', () => {
    const option = chartOptionFor({ type: 'line' }, { columns: ['region', '销售额'], rows: [['华东', 1200.5]] })
    expect(option.legend.show).toBe(false)
    expect(option.series[0].type).toBe('line')
    expect(option.series[0].showSymbol).toBe(true)
  })

  it('uses item tooltips and the first metric for pies', () => {
    const option = chartOptionFor(pieCard, data)
    expect(option.tooltip.trigger).toBe('item')
    expect(option.series[0].type).toBe('pie')
    expect(option.series[0].name).toBe('销售额')
    expect(option.series[0].data).toEqual([
      { name: '华东', value: 1200.5 },
      { name: '华北', value: 800 }
    ])
  })

  it('returns null for empty data and for non-chart cards', () => {
    expect(chartOptionFor(barCard, { columns: ['region'], rows: [] })).toBeNull()
    expect(chartOptionFor({ type: 'kpi' }, data)).toBeNull()
    expect(chartOptionFor({ type: 'text' }, data)).toBeNull()
  })

  it('shows the legend only when the user turns it on', () => {
    // 默认一律不画图例（单序列、多序列都一样），以前「多序列默认显示」才会时有时无
    const single = { type: 'line', config: { metrics: [{ column: 'amount', agg: 'SUM' }] } }
    const singleData = { columns: ['region', '销售额'], rows: [['华东', 1200.5]] }
    expect(chartOptionFor(single, singleData).legend.show).toBe(false)
    expect(chartOptionFor(barCard, data).legend.show).toBe(false)
    // 饼图也默认关
    expect(chartOptionFor(pieCard, data).legend.show).toBe(false)

    const withLegend = chartOptionFor({ ...single, config: { ...single.config, showLegend: true } }, singleData)
    expect(withLegend.legend.show).toBe(true)
    expect(chartOptionFor({ ...pieCard, config: { showLegend: true } }, data).legend.show).toBe(true)
  })

  it('honours legend, data-label and secondary-axis settings', () => {
    const card = {
      type: 'line',
      config: {
        showLegend: false,
        showLabel: true,
        metrics: [{ column: 'amount', agg: 'SUM', axis: 'right' }, { column: 'id', agg: 'COUNT', axis: 'left' }]
      }
    }
    const option = chartOptionFor(card, data)
    expect(option.legend.show).toBe(false)
    expect(option.series[0].label.show).toBe(true)
    // yAxis 恒为两根（第二根按需 show），series 用 yAxisIndex 指过去
    expect(option.yAxis).toHaveLength(2)
    expect(option.yAxis[1].show).toBe(true)
    expect(option.series.map(item => item.yAxisIndex)).toEqual([1, 0])
  })

  it('keeps the second y-axis hidden when no metric is on the right', () => {
    const option = chartOptionFor(barCard, data)
    // 数组形状恒定，只把第二根藏起来：合并更新时不会留下上一轮的轴
    expect(option.yAxis).toHaveLength(2)
    expect(option.yAxis[1].show).toBe(false)
    expect(option.series.every(item => item.yAxisIndex === 0)).toBe(true)
    expect(option.series[0].label.show).toBe(false)
  })

  it('turns bar charts sideways for 条形图', () => {
    const option = chartOptionFor({ type: 'hbar' }, data)
    // 分类挪到 y 轴，数值轴在 x 上
    expect(option.xAxis.type).toBe('value')
    expect(option.yAxis[0].type).toBe('category')
    expect(option.yAxis[0].data).toEqual(['华东', '华北'])
    expect(option.yAxis[1].show).toBe(false)
    expect(option.series.every(item => item.type === 'bar')).toBe(true)
  })

  it('keeps the tooltip for 条形图 but drops the axis-pointer value', () => {
    const option = chartOptionFor({ type: 'hbar' }, data)
    // 提示框还在：分类 + 系列值才是要看的
    expect(option.tooltip.show).not.toBe(false)
    // 只是不要指针旁边那个 x 轴数值标签
    expect(option.tooltip.axisPointer.label.show).toBe(false)
    // x 轴刻度也照常显示
    expect(option.xAxis.axisLabel.show).not.toBe(false)
    // 提示框内容 = 分类 + 系列值，不再顶一行 x 轴数值
    const tip = option.tooltip.formatter([{ dataIndex: 0, seriesName: '销售额', value: 1200.5, marker: '' }])
    expect(tip).toBe('华东<br/>销售额　1200.5')
  })

  it('draws 面积图 as lines with an area fill', () => {
    const option = chartOptionFor({ type: 'area' }, { columns: ['region', '销售额'], rows: [['华东', 100]] })
    expect(option.series[0].type).toBe('line')
    expect(option.series[0].areaStyle).toBeTruthy()
  })

  it('mixes bars and lines for 柱线组合图', () => {
    const option = chartOptionFor({ type: 'combo' }, data)
    expect(option.series.map(item => item.type)).toEqual(['bar', 'line'])
  })

  it('stacks every metric for 堆积柱状图', () => {
    const option = chartOptionFor({ type: 'stack' }, data)
    expect(option.series.map(item => item.stack)).toEqual(['total', 'total'])
  })

  it('renders 漏斗图 from the same category / value pairs', () => {
    const option = chartOptionFor({ type: 'funnel' }, data)
    expect(option.tooltip.trigger).toBe('item')
    expect(option.series[0].type).toBe('funnel')
    expect(option.series[0].data[0]).toEqual({ name: '华东', value: 1200.5 })
  })

  it('plots two metrics on x / y for 散点图', () => {
    const option = chartOptionFor(
      { type: 'scatter', config: { metrics: [{ alias: '单价' }, { alias: '销量' }] } },
      data
    )
    expect(option.xAxis.type).toBe('value')
    expect(option.xAxis.name).toBe('单价')
    expect(option.yAxis.name).toBe('销量')
    expect(option.series[0]).toMatchObject({ type: 'scatter' })
    expect(option.series[0].data[0]).toEqual({ name: '华东', value: [1200.5, 12] })
  })

  it('pins one unit for the whole axis when the format says auto', () => {
    const card = {
      type: 'bar',
      config: { metrics: [{ column: 'amount', agg: 'SUM', format: { type: 'number', precision: 2, unit: 'auto' } }] }
    }
    const option = chartOptionFor(card, { columns: ['region', '金额'], rows: [['华东', 296000000]] })
    const format = option.yAxis[0].axisLabel.formatter
    // 整根轴按最大值（2.96 亿）定单位，小刻度也用「亿」，
    // 不会再出现「0.00 / 5,000.00万 / 1.00亿」这种混排
    expect(format(0)).toBe('0.00亿')
    expect(format(50000000)).toBe('0.50亿')
    expect(format(100000000)).toBe('1.00亿')
  })

  it('formats values in tooltips and axis labels', () => {
    const card = {
      type: 'bar',
      config: { metrics: [{ column: 'amount', agg: 'SUM', format: { type: 'number', precision: 1, suffix: ' 元' } }] }
    }
    const option = chartOptionFor(card, { columns: ['region', '金额'], rows: [['华东', 1234.5]] })

    expect(option.yAxis[0].axisLabel.formatter(1234.5)).toBe('1,234.5 元')
    expect(option.series[0].tooltip.valueFormatter(1234.5)).toBe('1,234.5 元')
  })

  it('reads kpi values and formats numbers', () => {
    expect(kpiValue({ rows: [[1234]] })).toBe(1234)
    expect(kpiValue({ rows: [] })).toBeNull()
    expect(formatNumber(1234)).toBe('1,234')
    expect(formatNumber(12.345)).toBe('12.35')
    expect(formatNumber(null)).toBe('—')
  })
})
