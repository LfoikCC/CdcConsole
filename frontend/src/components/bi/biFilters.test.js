import { describe, expect, it } from 'vitest'
import {
  blankFilter, dashboardConditions, filterAppliesTo, filterConditions, filterName, filterSummary,
  normalizeFilter, quickRange
} from './biFilters'

describe('看板筛选器：结构', () => {
  it('新筛选器带默认值', () => {
    const filter = blankFilter('select')
    expect(filter.type).toBe('select')
    expect(filter.scope).toBe('dataset')
    expect(filter.values).toEqual([])
    expect(filter.id).toBeTruthy()
  })

  it('从多选改成单选的旧数据只留第一个值', () => {
    const normalized = normalizeFilter({
      type: 'select', datasetId: 7, column: 'status', multiple: false, values: ['A', 'B']
    })
    expect(normalized.values).toEqual(['A'])
  })

  it('把上一版的 {column, op, value} 补成新结构', () => {
    const select = normalizeFilter({ datasetId: 7, column: 'status', op: 'IN', value: ['已完成', '处理中'] })
    expect(select.type).toBe('select')
    expect(select.values).toEqual(['已完成', '处理中'])
    expect(select.multiple).toBe(true)

    const exclude = normalizeFilter({ datasetId: 7, column: 'status', op: 'NOT IN', value: ['作废'] })
    expect(exclude.exclude).toBe(true)

    const condition = normalizeFilter({ datasetId: 7, column: 'name', op: 'LIKE', value: '张' })
    expect(condition.type).toBe('condition')
    expect(condition.op).toBe('CONTAINS')
    expect(condition.keyword).toBe('张')

    expect(normalizeFilter(null)).toBeNull()
  })
})

describe('看板筛选器：翻译成查询条件', () => {
  it('选择：多选走 IN，排除走 NOT IN', () => {
    const base = { ...blankFilter('select'), column: 'status', values: ['已完成', '处理中'] }
    expect(filterConditions(base)).toEqual([{ column: 'status', op: 'IN', value: ['已完成', '处理中'] }])
    expect(filterConditions({ ...base, exclude: true }))
      .toEqual([{ column: 'status', op: 'NOT IN', value: ['已完成', '处理中'] }])
    // 没勾任何值：不过滤，不能把卡片筛成空表
    expect(filterConditions({ ...base, values: [] })).toEqual([])
  })

  it('单选只取第一个值', () => {
    const single = {
      ...blankFilter('select'), column: 'status', multiple: false,
      values: ['已完成', '处理中', '作废']
    }
    expect(filterConditions(single)).toEqual([{ column: 'status', op: 'IN', value: ['已完成'] }])
    // 多选还是全部带上
    expect(filterConditions({ ...single, multiple: true }))
      .toEqual([{ column: 'status', op: 'IN', value: ['已完成', '处理中', '作废'] }])
  })

  it('条件：关键词按匹配方式加通配符', () => {
    const base = { ...blankFilter('condition'), column: 'customer_name' }
    expect(filterConditions({ ...base, op: 'CONTAINS', keyword: '张' }))
      .toEqual([{ column: 'customer_name', op: 'LIKE', value: '%张%' }])
    expect(filterConditions({ ...base, op: 'NOT_CONTAINS', keyword: '张' }))
      .toEqual([{ column: 'customer_name', op: 'NOT LIKE', value: '%张%' }])
    expect(filterConditions({ ...base, op: 'STARTS', keyword: '张' }))
      .toEqual([{ column: 'customer_name', op: 'LIKE', value: '张%' }])
    expect(filterConditions({ ...base, op: 'ENDS', keyword: '张' }))
      .toEqual([{ column: 'customer_name', op: 'LIKE', value: '%张' }])
    expect(filterConditions({ ...base, op: '=', keyword: '张伟' }))
      .toEqual([{ column: 'customer_name', op: '=', value: '张伟' }])
    // 空关键词不参与过滤
    expect(filterConditions({ ...base, op: 'CONTAINS', keyword: '  ' })).toEqual([])
  })

  it('范围：区间、单边、为空', () => {
    const base = { ...blankFilter('range'), column: 'amount' }
    expect(filterConditions({ ...base, valueOp: 'BETWEEN', from: '100', to: '500' }))
      .toEqual([{ column: 'amount', op: 'BETWEEN', value: ['100', '500'] }])
    expect(filterConditions({ ...base, valueOp: 'BETWEEN', from: '100', to: '' }))
      .toEqual([{ column: 'amount', op: 'BETWEEN', value: ['100', null] }])
    expect(filterConditions({ ...base, valueOp: '>', from: '100' }))
      .toEqual([{ column: 'amount', op: '>', value: '100' }])
    expect(filterConditions({ ...base, valueOp: 'IS NULL' }))
      .toEqual([{ column: 'amount', op: 'IS NULL', value: null }])
    expect(filterConditions({ ...base, valueOp: 'BETWEEN', from: '', to: '' })).toEqual([])
  })

  it('组合条件：逐条翻译，空值丢掉', () => {
    const filter = {
      ...blankFilter('combo'),
      conditions: [
        { column: 'status', op: '=', value: '已完成' },
        { column: 'customer_name', op: 'CONTAINS', value: '' },
        { column: 'customer_name', op: 'STARTS', value: '张' }
      ]
    }
    expect(filterConditions(filter)).toEqual([
      { column: 'status', op: '=', value: '已完成' },
      { column: 'customer_name', op: 'LIKE', value: '张%' }
    ])
  })

  it('没选字段时不产生条件', () => {
    expect(filterConditions({ ...blankFilter('select'), values: ['A'] })).toEqual([])
  })
})

describe('看板筛选器：作用范围', () => {
  const filter = { ...blankFilter('select'), datasetId: 7, column: 'status', values: ['已完成'] }

  it('默认作用于同数据集的全部卡片', () => {
    expect(filterAppliesTo(filter, 7, 101)).toBe(true)
    expect(filterAppliesTo(filter, 8, 101)).toBe(false)
  })

  it('限定卡片时只对选中的那几张生效', () => {
    const scoped = { ...filter, scope: 'cards', cardIds: [101, 102] }
    expect(filterAppliesTo(scoped, 7, 101)).toBe(true)
    expect(filterAppliesTo(scoped, 7, 103)).toBe(false)
    // 还没保存的新卡没有 id，不能命中「指定卡片」
    expect(filterAppliesTo(scoped, 7, null)).toBe(false)
  })

  it('汇总某张卡要带的条件', () => {
    const other = { ...blankFilter('condition'), datasetId: 8, column: 'name', keyword: '张' }
    expect(dashboardConditions([filter, other], 7, 101))
      .toEqual([{ column: 'status', op: 'IN', value: ['已完成'] }])
  })
})

describe('看板筛选器：快捷日期与文案', () => {
  const today = new Date(2026, 10, 6) // 2026-11-06

  it('算出快捷区间的起止', () => {
    expect(quickRange('today', today)).toEqual(['2026-11-06', '2026-11-06'])
    expect(quickRange('yesterday', today)).toEqual(['2026-11-05', '2026-11-05'])
    expect(quickRange('last7', today)).toEqual(['2026-10-31', '2026-11-06'])
    expect(quickRange('thisMonth', today)).toEqual(['2026-11-01', '2026-11-30'])
    expect(quickRange('lastMonth', today)).toEqual(['2026-10-01', '2026-10-31'])
    expect(quickRange('thisQuarter', today)).toEqual(['2026-10-01', '2026-12-31'])
    expect(quickRange('thisYear', today)).toEqual(['2026-01-01', '2026-12-31'])
    expect(quickRange('', today)).toEqual(['', ''])
  })

  it('名字和摘要', () => {
    expect(filterName({ column: 'status' })).toBe('status')
    expect(filterSummary({ ...blankFilter('select'), column: 'status', values: ['已完成'] }))
      .toBe('status：已完成')
    expect(filterSummary({ ...blankFilter('select'), column: 'status', values: ['A'], exclude: true }))
      .toBe('status 排除 A')
    expect(filterSummary({ ...blankFilter('date'), column: 'updated_at', from: '2026-01-01', to: '2026-06-30' }))
      .toBe('updated_at：2026-01-01 ~ 2026-06-30')
  })
})
