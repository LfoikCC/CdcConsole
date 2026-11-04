import { describe, expect, it } from 'vitest'
import { MAX_LIMIT, blankConfig, hasActiveFilter } from './biCards'

const card = filters => ({ type: 'bar', config: { filters } })

describe('blankConfig', () => {
  it('gives detail tables the full row budget', () => {
    // 明细表默认留 100 行会让人以为数据只有这么多
    expect(blankConfig('table').limit).toBe(MAX_LIMIT)
    expect(blankConfig('bar').limit).toBe(100)
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
