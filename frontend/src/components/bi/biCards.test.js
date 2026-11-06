import { describe, expect, it } from 'vitest'
import {
  MAX_LIMIT, blankConfig, cardRequirement, formatMetricValue, hasActiveFilter,
  hasMetricFormat, metricFormatLabel
} from './biCards'

const card = filters => ({ type: 'bar', config: { filters } })

describe('blankConfig', () => {
  it('defaults every card to the full row budget', () => {
    // 默认留 100 行会让人以为数据只有这么多，所以统一按「不限制」（服务端上限）给
    expect(blankConfig('table').limit).toBe(MAX_LIMIT)
    expect(blankConfig('bar').limit).toBe(MAX_LIMIT)
    expect(blankConfig('pie').limit).toBe(MAX_LIMIT)
    // 指标卡只要一行、文本卡不取数
    expect(blankConfig('kpi').limit).toBe(1)
  })
})

describe('hasActiveFilter', () => {
  it('ignores conditions that are not filled in yet', () => {
    expect(hasActiveFilter(card([]))).toBe(false)
    expect(hasActiveFilter(card([{ column: 'status', op: 'IN', value: [] }]))).toBe(false)
    expect(hasActiveFilter(card([{ column: 'amount', op: 'BETWEEN', value: [null, null] }]))).toBe(false)
    expect(hasActiveFilter(card([{ column: 'status', op: '=', value: '' }]))).toBe(false)
  })

  it('counts filled-in conditions', () => {
    expect(hasActiveFilter(card([{ column: 'status', op: 'IN', value: ['已完成'] }]))).toBe(true)
    expect(hasActiveFilter(card([{ column: 'status', op: 'NOT IN', value: ['已完成'] }]))).toBe(true)
    expect(hasActiveFilter(card([{ column: 'amount', op: 'BETWEEN', value: [1, null] }]))).toBe(true)
    expect(hasActiveFilter(card([{ column: 'status', op: 'IS NOT NULL' }]))).toBe(true)
  })
})

describe('cardRequirement', () => {
  const card = (type, dimensions, metrics) => ({ type, datasetId: 7, config: { dimensions, metrics } })
  const dim = { column: 'status' }
  const metric = { column: 'amount', agg: 'SUM' }

  it('asks for both halves when a chart has nothing configured', () => {
    expect(cardRequirement(card('bar', [], []))).toEqual({ complete: false, text: '建议 1 个维度，1 个数值' })
    expect(cardRequirement(card('pie', [], []))).toEqual({ complete: false, text: '建议 1 个维度，1 个数值' })
    expect(cardRequirement(card('scatter', [], []))).toEqual({ complete: false, text: '建议 1 个维度，2 个数值' })
  })

  it('names only the part that is still missing', () => {
    expect(cardRequirement(card('bar', [dim], [])).text).toBe('还需要 1 个数值')
    expect(cardRequirement(card('bar', [], [metric])).text).toBe('还需要 1 个维度')
    // 散点两个数值都要，只有一个时还说差一个
    expect(cardRequirement(card('scatter', [dim], [metric])).text).toBe('还需要 1 个数值（X 轴 / Y 轴各一个）')
  })

  it('is complete once the type has what it needs', () => {
    expect(cardRequirement(card('bar', [dim], [metric])).complete).toBe(true)
    expect(cardRequirement(card('scatter', [dim], [metric, { column: 'qty', agg: 'SUM' }])).complete).toBe(true)
    expect(cardRequirement(card('kpi', [], [metric])).complete).toBe(true)
    expect(cardRequirement(card('table', [dim], [])).complete).toBe(true)
    expect(cardRequirement({ type: 'text', config: {} }).complete).toBe(true)
    // 指标卡不需要维度
    expect(cardRequirement(card('kpi', [], [])).text).toBe('建议 1 个数值')
  })
})

describe('数值字段的数据格式', () => {
  it('默认就是不做处理', () => {
    expect(formatMetricValue(1234.5, {})).toBe('1234.5')
    expect(formatMetricValue(1234.5, { format: { type: 'default', prefix: '¥' } })).toBe('1234.5')
    expect(hasMetricFormat({})).toBe(false)
    expect(hasMetricFormat({ format: { type: 'default' } })).toBe(false)
    expect(hasMetricFormat({ format: { type: 'number' } })).toBe(true)
  })

  it('按精度、千分位、计数单位、前后缀渲染', () => {
    expect(formatMetricValue(1234.5, { format: { type: 'number' } })).toBe('1,234.50')
    expect(formatMetricValue(1234.5, { format: { type: 'number', precision: 0 } })).toBe('1,235')
    expect(formatMetricValue(1234.5, { format: { type: 'number', thousands: false } })).toBe('1234.50')
    expect(formatMetricValue(12, { format: { type: 'number', prefix: '¥' } })).toBe('¥12.00')
    expect(formatMetricValue(12, { format: { type: 'number', prefix: '约 ', suffix: ' 元' } })).toBe('约 12.00 元')
    expect(formatMetricValue(null, { format: { type: 'number' } })).toBe('—')
  })

  it('缩放计数单位', () => {
    expect(formatMetricValue(1500, { format: { type: 'number', unit: 'k' } })).toBe('1.50K')
    expect(formatMetricValue(1500000, { format: { type: 'number', unit: 'm' } })).toBe('1.50M')
    expect(formatMetricValue(12345678, { format: { type: 'number', unit: 'wan' } })).toBe('1,234.57万')
    expect(formatMetricValue(150000000, { format: { type: 'number', unit: 'yi' } })).toBe('1.50亿')
    // 自动：按数量级挑万/亿
    expect(formatMetricValue(12345678, { format: { type: 'number', unit: 'auto' } })).toBe('1,234.57万')
    expect(formatMetricValue(150000000, { format: { type: 'number', unit: 'auto' } })).toBe('1.50亿')
    expect(formatMetricValue(1234, { format: { type: 'number', unit: 'auto' } })).toBe('1,234.00')
  })

  it('百分比只补 %，不乘 100', () => {
    expect(formatMetricValue(12.5, { format: { type: 'percent', precision: 1 } })).toBe('12.5%')
  })

  it('菜单摘要跟着格式变', () => {
    expect(metricFormatLabel({ format: { type: 'number', precision: 2, unit: 'wan' } })).toBe('数值 · 2 位 · 万')
    expect(metricFormatLabel({})).toBe('默认')
  })
})
