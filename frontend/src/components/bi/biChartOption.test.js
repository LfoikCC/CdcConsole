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
    expect(option.legend.show).not.toBe(false)
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

  it('shows the legend for a single series once the user turns it on', () => {
    // 只有一条序列时默认不画图例（没意义），但用户手动打开就必须显示
    const single = { type: 'line', config: { metrics: [{ column: 'amount', agg: 'SUM' }] } }
    const singleData = { columns: ['region', '销售额'], rows: [['华东', 1200.5]] }
    expect(chartOptionFor(single, singleData).legend.show).toBe(false)

    const withLegend = chartOptionFor({ ...single, config: { ...single.config, showLegend: true } }, singleData)
    expect(withLegend.legend.show).not.toBe(false)
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
    // 有次轴时 yAxis 变成数组，series 用 yAxisIndex 指过去
    expect(Array.isArray(option.yAxis)).toBe(true)
    expect(option.yAxis).toHaveLength(2)
    expect(option.series.map(item => item.yAxisIndex)).toEqual([1, 0])
  })

  it('keeps a single y-axis when no metric is on the right', () => {
    const option = chartOptionFor(barCard, data)
    expect(Array.isArray(option.yAxis)).toBe(false)
    expect(option.series.every(item => item.yAxisIndex === 0)).toBe(true)
    expect(option.series[0].label.show).toBe(false)
  })

  it('reads kpi values and formats numbers', () => {
    expect(kpiValue({ rows: [[1234]] })).toBe(1234)
    expect(kpiValue({ rows: [] })).toBeNull()
    expect(formatNumber(1234)).toBe('1,234')
    expect(formatNumber(12.345)).toBe('12.35')
    expect(formatNumber(null)).toBe('—')
  })
})
