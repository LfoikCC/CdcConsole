import { describe, expect, it } from 'vitest'
import {
  OPERATOR_DEFAULTS, OPERATOR_ICONS, OPERATOR_LABELS, PALETTE_GROUPS,
  createNode, iconOf, operatorDefaults
} from './nodeTypes'

describe('nodeTypes', () => {
  it('registers nine operators', () => {
    expect(Object.keys(OPERATOR_LABELS)).toHaveLength(9)
    expect(OPERATOR_LABELS.AGGREGATE).toBe('分组聚合')
    expect(OPERATOR_LABELS.MERGE_COLUMNS).toBe('合并列')
  })

  it('exposes a twelve-item palette grouped by input / operator / output', () => {
    expect(PALETTE_GROUPS.map(group => group.label)).toEqual(['输入', '算子', '输出'])
    const items = PALETTE_GROUPS.flatMap(group => group.items)
    expect(items).toHaveLength(12)
    expect(items.filter(item => item.type === 'OPERATOR')).toHaveLength(9)
    expect(items.every(item => item.icon)).toBe(true)
  })

  it('gives every operator its own icon', () => {
    const icons = new Set(Object.values(OPERATOR_ICONS))
    expect(icons.size).toBe(9)
  })

  it('creates an operator node with the defaults of that operator', () => {
    const node = createNode('OPERATOR', 'n1', 0, 0, 'CALCULATE')
    expect(node.name).toBe('添加计算列')
    expect(node.config).toMatchObject({ operator: 'CALCULATE', alias: '', expression: '' })
    expect(node.config.keys).toBeUndefined()
  })

  it('keeps SORT and DEDUP key shapes apart', () => {
    // 共用 keys 字段曾导致：SORT 的对象残留到 DEDUP，被静默跳过后退化成「不排序」
    const sort = operatorDefaults('SORT')
    const dedup = operatorDefaults('DEDUP')
    expect(sort.keys).toEqual([])
    expect(dedup.keys).toEqual([])
    sort.keys.push({ column: 'amount', direction: 'DESC' })
    expect(dedup.keys).toEqual([])
    expect(OPERATOR_DEFAULTS.SORT).not.toBe(OPERATOR_DEFAULTS.DEDUP)
  })

  it('falls back to PROJECT defaults for an unknown operator', () => {
    expect(operatorDefaults('NOPE').operator).toBe('PROJECT')
  })

  it('picks the icon from the operator, not from the node type', () => {
    const aggregate = { type: 'OPERATOR', config: { operator: 'AGGREGATE' } }
    const filter = { type: 'OPERATOR', config: { operator: 'FILTER' } }
    expect(iconOf(aggregate)).not.toBe(iconOf(filter))
  })

  it('uses the type label for non-operator nodes', () => {
    expect(createNode('DB_INPUT', 'n2', 0, 0).name).toBe('数据库输入')
  })
})
