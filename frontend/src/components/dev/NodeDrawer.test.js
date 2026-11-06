import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NodeDrawer from './NodeDrawer.vue'
import { components } from '../ui'
import { api } from '../../api'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('../ui/feedback', () => ({ CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const upstreamColumns = [{ label: 'id' }, { label: 'amount' }, { label: 'status' }]
const secondUpstreamColumns = [{ label: 'id' }, { label: 'city' }]

function mountDrawer(node) {
  return mount(NodeDrawer, {
    props: {
      node,
      sources: [],
      targets: [],
      upstreamColumns,
      secondUpstreamColumns,
      preview: null,
      busy: false
    },
    global: { components }
  })
}

const filterNode = () => ({
  id: 'n1',
  type: 'OPERATOR',
  name: '行过滤',
  config: { operator: 'FILTER', join: 'AND', conditions: [{ column: 'id', op: '=', value: '1' }] }
})

describe('NodeDrawer', () => {
  it('renames the node from the drawer title when applying', async () => {
    const wrapper = mountDrawer(filterNode())
    await wrapper.find('.nd-name').trigger('click')
    await wrapper.find('.nd-name-input').setValue('只看大额订单')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    const payload = wrapper.emitted('apply')[0][0]
    expect(payload.name).toBe('只看大额订单')
    expect(payload.config.operator).toBe('FILTER')
  })

  it('falls back to the operator label when the name is blank', async () => {
    const wrapper = mountDrawer({ ...filterNode(), name: '行过滤' })
    await wrapper.find('.nd-name').trigger('click')
    await wrapper.find('.nd-name-input').setValue('   ')
    await wrapper.find('.nd-name-input').trigger('blur')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    expect(wrapper.emitted('apply')[0][0].name).toBe('行过滤')
  })

  it('shows the operator as a fixed label instead of a selector', async () => {
    const wrapper = mountDrawer({
      id: 'n5',
      type: 'OPERATOR',
      name: '分组聚合',
      config: { operator: 'AGGREGATE', groupBy: [], aggregates: [] }
    })

    expect(wrapper.find('.nd-head strong').text()).toBe('分组聚合')
    expect(wrapper.text()).toContain('添加聚合项')
    expect(wrapper.findAll('select')).toHaveLength(0)

    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    const config = wrapper.emitted('apply')[0][0].config
    expect(config.operator).toBe('AGGREGATE')
    expect(config.aggregates).toEqual([])
  })

  it('renders the calculate form with the upstream columns as a hint', async () => {
    const wrapper = mountDrawer({
      id: 'n2',
      type: 'OPERATOR',
      name: '添加计算列',
      config: { operator: 'CALCULATE', alias: '', expression: '', type: 'DECIMAL(38,10)' }
    })

    expect(wrapper.text()).toContain('新列名')
    expect(wrapper.text()).toContain('id、amount、status')
    expect(wrapper.text()).toContain('DECIMAL(38,10)')
  })

  it('adds join keys from both upstreams', async () => {
    const wrapper = mountDrawer({
      id: 'n3',
      type: 'OPERATOR',
      name: '关联数据',
      config: { operator: 'JOIN', joinType: 'INNER', keys: [] }
    })

    expect(wrapper.text()).toContain('关联类型')
    const add = wrapper.findAll('button').find(button => button.text().includes('添加关联键'))
    await add.trigger('click')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    expect(wrapper.emitted('apply')[0][0].config.keys).toEqual([{ left: 'id', right: 'id' }])
  })

  it('creates an offline dataset from the dataset output drawer', async () => {
    api.mockResolvedValue({ id: 99 })
    const wrapper = mountDrawer({
      id: 'o1',
      type: 'DATASET_OUTPUT',
      name: '写离线',
      config: { datasetId: null, mode: 'OVERWRITE' }
    })

    await wrapper.find('input[placeholder="新离线数据集名称"]').setValue('离线结果')
    await wrapper.findAll('button').find(button => button.text().includes('新建离线数据集')).trigger('click')
    await flushPromises()

    expect(api).toHaveBeenCalledWith('/datasets/extract', {
      method: 'POST',
      body: JSON.stringify({ name: '离线结果', description: '' })
    })
    expect(wrapper.emitted('dataset-created')[0][0]).toEqual({ id: 99 })

    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    expect(wrapper.emitted('apply')[0][0].config.datasetId).toBe(99)
  })

  it('warns when union sides have different column counts', () => {
    const wrapper = mountDrawer({
      id: 'n4',
      type: 'OPERATOR',
      name: '行拼接',
      config: { operator: 'UNION', mode: 'ALL' }
    })

    expect(wrapper.text()).toContain('左表 3 列、右表 2 列')
    expect(wrapper.text()).toContain('两侧列数不一致')
  })

  it('labels the left and right upstream tables in a join', () => {
    const wrapper = mount(NodeDrawer, {
      props: {
        node: { id: 'n3', type: 'OPERATOR', name: '关联数据', config: { operator: 'JOIN', joinType: 'INNER', keys: [] } },
        upstreamColumns,
        secondUpstreamColumns,
        upstreamLabel: '订单（CDC-SOURCE.orders）',
        secondUpstreamLabel: '客户（CDC-SOURCE.users）',
        busy: false
      },
      global: { components }
    })

    const head = wrapper.find('.nd-join-head')
    expect(head.text()).toContain('左表')
    expect(head.text()).toContain('订单（CDC-SOURCE.orders）')
    expect(head.text()).toContain('右表')
    expect(head.text()).toContain('客户（CDC-SOURCE.users）')
  })

  it('renders the database output as write mode, connection, target and pre-SQL', async () => {
    api.mockResolvedValue([])
    const wrapper = mount(NodeDrawer, {
      props: {
        node: {
          id: 'o2',
          type: 'DB_OUTPUT',
          name: '写 ADS',
          config: { targetId: 1, table: 'ads_orders', mode: 'APPEND' }
        },
        targets: [{ id: 1, name: 'SR_PRD_ODS', targetType: 'STARROCKS_STREAM_LOAD' }],
        upstreamColumns,
        busy: false
      },
      global: { components }
    })
    await flushPromises()

    // 图二的字段顺序：更新方式 → 数据连接 → 高性能模式 → 目标表 → 前置 SQL
    for (const label of ['更新方式', '直接追加', '全量更新', '插入更新', '数据连接', '高性能模式', '目标表', '前置 SQL']) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.text()).toContain('StarRocks')
    expect(wrapper.text()).toContain('新建表')
    expect(wrapper.text()).toContain('字段映射')

    const toggle = wrapper.find('input[role="switch"]')
    await toggle.setValue(true)
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    const config = wrapper.emitted('apply')[0][0].config
    expect(config.mode).toBe('APPEND')
    expect(config.highPerformance).toBe(true)
  })

  it('swaps the target table select for a name input when creating a table', async () => {
    api.mockResolvedValue([])
    const wrapper = mount(NodeDrawer, {
      props: {
        node: { id: 'o3', type: 'DB_OUTPUT', name: '写 ADS', config: { targetId: 1, table: 'ads_orders' } },
        targets: [{ id: 1, name: 'SR_PRD_ODS', targetType: 'STARROCKS_STREAM_LOAD' }],
        upstreamColumns,
        busy: false
      },
      global: { components }
    })
    await flushPromises()

    // 新建表在标题行，字段映射在选择框右侧
    expect(wrapper.find('.nd-field-head .nd-field-action').text()).toBe('新建表')
    expect(wrapper.find('.nd-target-row .btn').text()).toContain('字段映射')

    await wrapper.find('.nd-field-head .nd-field-action').trigger('click')
    expect(wrapper.find('.nd-target-row .nd-table-input').exists()).toBe(true)
    expect(wrapper.find('.nd-field-head .nd-field-action').text()).toBe('选择表')

    await wrapper.find('.nd-table-input').setValue('ads_orders_new')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    expect(wrapper.emitted('apply')[0][0].config.table).toBe('ads_orders_new')

    await wrapper.find('.nd-field-head .nd-field-action').trigger('click')
    expect(wrapper.find('.nd-table-input').exists()).toBe(false)
  })

  it('maps target columns by name and by order like the sync task wizard', async () => {
    api.mockResolvedValue([
      { table: 'ads_orders', columns: ['order_id', 'customer', 'amount', 'created_at'], primaryKeys: ['order_id'] }
    ])
    const wrapper = mount(NodeDrawer, {
      props: {
        node: { id: 'o5', type: 'DB_OUTPUT', name: '写 ADS', config: { targetId: 1, table: 'ads_orders' } },
        targets: [{ id: 1, name: 'SR_PRD_ODS', targetType: 'STARROCKS_STREAM_LOAD' }],
        upstreamColumns,
        busy: false
      },
      global: { components, stubs: { teleport: true } }
    })
    await flushPromises()

    await wrapper.find('.nd-target-row .btn').trigger('click')
    const action = label => wrapper.findAll('button').find(button => button.text() === label)

    expect(action('同名映射')).toBeTruthy()
    expect(action('顺序映射')).toBeTruthy()

    // 顺序映射：按目标表字段顺序依次对应
    await action('顺序映射').trigger('click')
    await action('应用映射').trigger('click')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    expect(wrapper.emitted('apply')[0][0].config.targetColumns).toEqual(['order_id', 'customer', 'amount'])

    // 同名映射：回到上游列名
    await wrapper.find('.nd-target-row .btn').trigger('click')
    await action('同名映射').trigger('click')
    await action('应用映射').trigger('click')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    expect(wrapper.emitted('apply')[1][0].config.targetColumns).toEqual(['id', 'amount', 'status'])
  })

  it('offers a refresh action instead of blocking when upstream columns are missing', async () => {
    api.mockResolvedValue([])
    const wrapper = mount(NodeDrawer, {
      props: {
        node: { id: 'o4', type: 'DB_OUTPUT', name: '写 ADS', config: { targetId: 1, table: 'ads_orders' } },
        targets: [{ id: 1, name: 'SR_PRD_ODS', targetType: 'STARROCKS_STREAM_LOAD' }],
        upstreamColumns: [],
        busy: false
      },
      global: { components, stubs: { teleport: true } }
    })
    await flushPromises()

    await wrapper.find('.nd-target-row .btn').trigger('click')
    expect(wrapper.text()).toContain('还没拿到上游字段')

    await wrapper.findAll('button').find(button => button.text().includes('刷新上游字段')).trigger('click')
    expect(wrapper.emitted('need-upstream')).toBeTruthy()
  })

  it('renders the database input as type, account and SQL query', async () => {
    api.mockResolvedValue([])
    const wrapper = mountDrawer({
      id: 'i1',
      type: 'DB_INPUT',
      name: '数据库输入',
      config: { sourceId: 1, mode: 'SQL', database: 'CDC-SOURCE', sql: 'SELECT id FROM orders', previewMode: 'LIMIT', limit: 10000 }
    })

    expect(wrapper.text()).toContain('数据库类型')
    expect(wrapper.text()).toContain('数据账户')
    expect(wrapper.text()).toContain('SQL 查询')
    expect(wrapper.text()).toContain('缓存数据行数')
    expect(wrapper.text()).not.toContain('取数方式')
    expect(wrapper.find('.nd-sql-preview').text()).toContain('SELECT id FROM orders')

    await wrapper.find('.nd-foot .btn-primary').trigger('click')
    const config = wrapper.emitted('apply')[0][0].config
    expect(config.mode).toBe('SQL')
    expect(config.sql).toBe('SELECT id FROM orders')
    expect(config.limit).toBe(10000)
  })
})
