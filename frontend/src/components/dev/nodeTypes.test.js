import { describe, expect, it } from 'vitest'
import {
  OPERATOR_DEFAULTS, OPERATOR_ICONS, OPERATOR_LABELS, PALETTE_GROUPS, TWO_INPUT_OPERATORS,
  createNode, iconOf, needsTwoInputs, operatorDefaults
} from './nodeTypes'

describe('nodeTypes', () => {
  it('registers fourteen operators', () => {
    expect(Object.keys(OPERATOR_LABELS)).toHaveLength(14)
    expect(OPERATOR_LABELS.AGGREGATE).toBe('分组聚合')
    expect(OPERATOR_LABELS.UNION).toBe('行拼接')
    expect(OPERATOR_LABELS.JOIN).toBe('关联数据')
    expect(OPERATOR_LABELS.PIVOT).toBe('行转列')
    expect(OPERATOR_LABELS.UNPIVOT).toBe('列转行')
    expect(OPERATOR_LABELS.SQL).toBe('SQL 算子')
  })

  it('exposes an eighteen-item palette in six functional groups', () => {
    expect(PALETTE_GROUPS.map(group => group.label))
      .toEqual(['输入/输出', '列编辑', '行处理', '聚合', '多表', '高级'])
    const items = PALETTE_GROUPS.flatMap(group => group.items)
    expect(items).toHaveLength(18)
    expect(items.filter(item => item.type === 'OPERATOR')).toHaveLength(14)
    expect(items.filter(item => item.type === 'DATASET_INPUT' || item.type === 'DATASET_OUTPUT')).toHaveLength(2)
    // SQL 输入已并进数据库输入，面板上不该再出现
    expect(items.some(item => item.type === 'SQL_INPUT')).toBe(false)
    expect(items.every(item => item.icon)).toBe(true)
    const groupedOperators = PALETTE_GROUPS
      .filter(group => group.label !== '输入/输出')
      .flatMap(group => group.items.map(item => item.operator))
      .sort()
    expect(groupedOperators).toEqual(Object.keys(OPERATOR_LABELS).sort())
  })

  it('gives every operator its own icon', () => {
    const icons = new Set(Object.values(OPERATOR_ICONS))
    expect(icons.size).toBe(14)
  })

  it('marks only union and join as two-input operators', () => {
    expect(TWO_INPUT_OPERATORS).toEqual(['UNION', 'JOIN'])
    expect(needsTwoInputs({ type: 'OPERATOR', config: { operator: 'JOIN' } })).toBe(true)
    expect(needsTwoInputs({ type: 'OPERATOR', config: { operator: 'UNION' } })).toBe(true)
    expect(needsTwoInputs({ type: 'OPERATOR', config: { operator: 'FILTER' } })).toBe(false)
    expect(needsTwoInputs({ type: 'DB_OUTPUT', config: {} })).toBe(false)
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
