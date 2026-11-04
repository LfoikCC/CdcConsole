import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { components } from '../ui'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('../ui/feedback', () => ({ CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
vi.mock('../../chartStyle', () => ({
  createChart: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), getDom: () => null }),
  chartStyle: () => ({ color: [], tooltip: {}, legend: {}, grid: {} }),
  chartAxis: () => ({})
}))

import { api } from '../../api'
import CardEditor from './CardEditor.vue'

const datasets = [
  { id: 7, name: 'Orders', datasetType: 'TABLE', databaseName: 'CDC-SOURCE', tableName: 'Orders',
    columnCount: 2, rowCount: 0, updatedAt: '2026-11-04T10:00:00+08:00' },
  { id: 8, name: 'Customers', datasetType: 'TABLE', databaseName: 'CDC-SOURCE', tableName: 'Customers',
    columnCount: 2, rowCount: 0, updatedAt: '2026-11-04T10:00:00+08:00' }
]

function mountEditor(card) {
  return mount(CardEditor, {
    props: { card, datasets },
    // 字段菜单是 teleport 到 body 的，stub 掉才能直接在组件树里断言
    global: { components, stubs: { teleport: true } }
  })
}

beforeEach(() => {
  localStorage.clear()
  api.mockReset()
  api.mockImplementation(async path => {
    if (path === '/datasets/7') {
      return { columns: [{ name: 'status', dataType: 'VARCHAR' }, { name: 'amount', dataType: 'DECIMAL(38,10)' }] }
    }
    if (path === '/bi/query') return { columns: ['status', '求和(amount)'], rows: [['已完成', 100]], truncated: false, elapsedMs: 5 }
    if (path.startsWith('/bi/datasets/7/values')) {
      return { column: 'status', values: ['已完成', '处理中', '新建'], truncated: false }
    }
    if (path === '/bi/datasets/7/stats') return { rows: 7 }
    if (path === '/bi/datasets/8/stats') return { rows: 42 }
    if (path === '/datasets/8') return { columns: [{ name: 'name', dataType: 'VARCHAR' }] }
    return {}
  })
})

describe('CardEditor', () => {
  it('shows the dataset, its update time and the fields', async () => {
    const card = { type: 'bar', title: '按状态汇总', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Orders')
    expect(text).toContain('最近更新')
    expect(wrapper.findAll('.bi-field').map(item => item.text())).toEqual(['status', 'amount'])
    // 四栏都在
    expect(wrapper.findAll('.bi-ce-col')).toHaveLength(4)
    // 库表数据集的元数据里行数是 0，编辑器按需算出来的真实行数要显示出来
    expect(wrapper.findAll('.bi-ce-ds-line').at(-1).text()).toContain('7 行')

  })

  it('adds a dimension and a metric by clicking fields', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.findAll('.bi-field').find(item => item.text() === 'status').trigger('click')
    expect(card.config.dimensions).toEqual([{ column: 'status', alias: '' }])

    await wrapper.findAll('.bi-field').find(item => item.text() === 'amount').trigger('click')
    expect(card.config.metrics[0]).toMatchObject({ column: 'amount', agg: 'SUM' })
    expect(wrapper.emitted('change')).toBeTruthy()
  })

  it('accepts a field dropped onto the measure zone', async () => {
    const card = { type: 'table', title: '表', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const field = wrapper.findAll('.bi-field').find(item => item.text() === 'amount')
    await field.trigger('dragstart', { dataTransfer: { setData: () => {} } })
    const dropZone = wrapper.findAll('.bi-zone')[1]
    await dropZone.trigger('dragover')
    expect(dropZone.classes()).toContain('is-hover')
    await dropZone.trigger('drop')

    expect(card.config.metrics).toHaveLength(1)
  })

  it('accepts a dimension dropped onto the measure zone and counts it', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 文本维度也能当数值：默认按计数，不能再是 SUM
    const field = wrapper.findAll('.bi-field').find(item => item.text() === 'status')
    await field.trigger('dragstart', { dataTransfer: { setData: () => {} } })
    await wrapper.findAll('.bi-zone')[1].trigger('drop')

    expect(card.config.metrics[0]).toMatchObject({ column: 'status', agg: 'COUNT' })
  })

  it('accepts a numeric field dropped onto the dimension zone', async () => {
    const card = { type: 'table', title: '表', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const field = wrapper.findAll('.bi-field').find(item => item.text() === 'amount')
    await field.trigger('dragstart', { dataTransfer: { setData: () => {} } })
    await wrapper.findAll('.bi-zone')[0].trigger('drop')

    expect(card.config.dimensions.map(item => item.column)).toEqual(['amount'])
  })

  it('renames a dimension from the pill menu', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [{ column: 'status' }], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 别名不再占着绘制区，点茶壶才展开
    expect(wrapper.find('.bi-dim-row').exists()).toBe(false)
    await wrapper.find('.bi-pill').trigger('click')
    const alias = wrapper.find('.bi-pill-menu input')
    await alias.setValue('订单状态')
    expect(card.config.dimensions[0].alias).toBe('订单状态')
    expect(wrapper.find('.bi-pill').text()).toContain('订单状态')
  })

  it('only offers aggregations that make sense for a text field', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [{ column: 'status', agg: 'COUNT' }], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    expect(wrapper.find('.bi-pill').text()).toContain('计数')

    await wrapper.find('.bi-pill').trigger('click')
    // 聚合方式收进了二级菜单，点开才展开
    await wrapper.find('.bi-menu-item.has-sub').trigger('click')
    const labels = wrapper.findAll('.bi-submenu-item').map(item => item.text())
    expect(labels).toContain('计数')
    expect(labels).toContain('无处理')
    expect(labels).not.toContain('求和')
  })

  it('sorts by a field dragged onto the sort zone', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: { dimensions: [], metrics: [], filters: [], sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    // amount 还没进图：拖进排序会先当数值加进去，再按它降序
    await wrapper.findAll('.bi-field').find(item => item.text() === 'amount')
      .trigger('dragstart', { dataTransfer: { setData: () => {} } })
    await wrapper.findAll('.bi-zone')[3].trigger('drop')

    expect(card.config.metrics).toHaveLength(1)
    expect(card.config.sort).toMatchObject({ by: 'metric', index: 0, dir: 'desc' })
    const pill = wrapper.find('.bi-pill.tone-sort')
    expect(pill.text()).toContain('amount（求和）')
    expect(pill.text()).toContain('降序')
  })

  it('switches the sort direction from the sort pill', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [], filters: [],
        sort: { by: 'dimension', index: 0, dir: 'asc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill.tone-sort').trigger('click')
    await wrapper.findAll('.bi-menu-item').find(item => item.text() === '降序').trigger('click')
    expect(card.config.sort.dir).toBe('desc')
  })

  it('resizes the draw column by dragging its handle', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 默认和左侧字段栏一样宽
    expect(wrapper.find('.bi-card-editor').attributes('style')).toContain('--draw-col: 236px')

    const handle = wrapper.find('.bi-resizer')
    handle.element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, clientX: 300 }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 360 }))
    await flushPromises()
    expect(wrapper.find('.bi-card-editor').attributes('style')).toContain('--draw-col: 296px')

    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    // 拖完记在本机，下次打开还是这个宽度
    expect(localStorage.getItem('cdc.bi.drawWidth')).toBe('296')
  })

  it('loads the preview when opening an existing card', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }],
        filters: [], sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 配置没变化时 watch 不会触发，挂载后要主动取一次数，否则预览永远是空的
    expect(api.mock.calls.some(([path]) => path === '/bi/query')).toBe(true)
  })

  it('closes the field menu after removing a field', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill').trigger('click')
    await wrapper.findAll('.bi-menu-item').find(item => item.text().includes('移除字段')).trigger('click')

    expect(card.config.dimensions).toEqual([])
    // 不移除的话会留下一条空白的小浮窗
    expect(wrapper.find('.bi-pill-menu').exists()).toBe(false)
  })

  it('switches the dataset and clears the fields that no longer exist', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }],
        filters: [], sort: { by: 'dimension', index: 0, dir: 'asc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('[aria-label="切换数据集"]').trigger('click')
    await wrapper.find('.modal .select-trigger').trigger('click')
    await wrapper.findAll('.select-popover .select-list button').find(item => item.text().includes('Customers')).trigger('click')
    await wrapper.findAll('.modal-foot button').find(item => item.text() === '切换').trigger('click')
    await flushPromises()

    expect(card.datasetId).toBe(8)
    expect(card.config.dimensions).toEqual([])
    expect(card.config.metrics).toEqual([])
    expect(card.config.sort.by).toBe('none')
  })

  it('searches, selects all and excludes values in a select filter', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: {
        dimensions: [], metrics: [], filters: [{ column: 'status', op: 'IN', value: [] }],
        sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill').trigger('click')
    await flushPromises()

    // 搜索只留匹配项，全选也只勾这些
    await wrapper.find('.bi-check-search').setValue('已')
    expect(wrapper.findAll('.bi-check').map(item => item.text())).toEqual(['已完成'])
    await wrapper.find('.bi-link').trigger('click')
    expect(card.config.filters[0].value).toEqual(['已完成'])

    // 排除：op 换成 NOT IN
    await wrapper.find('.bi-exclude input').trigger('change')
    expect(card.config.filters[0].op).toBe('NOT IN')
  })

  it('switches to a value-list filter with pickable values', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: {
        dimensions: [], metrics: [], filters: [{ column: 'status', op: '=', value: '' }],
        sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill').trigger('click')
    await wrapper.findAll('.bi-menu-option').find(item => item.text() === '选择').trigger('click')
    await flushPromises()

    expect(card.config.filters[0].op).toBe('IN')
    const checks = wrapper.findAll('.bi-check')
    expect(checks.map(item => item.text())).toEqual(['已完成', '处理中', '新建'])

    await checks[0].find('input').trigger('change')
    expect(card.config.filters[0].value).toEqual(['已完成'])
  })

  it('switches to a range filter and converts numbers', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: {
        dimensions: [], metrics: [], filters: [{ column: 'amount', op: '=', value: '' }],
        sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill').trigger('click')
    await wrapper.findAll('.bi-menu-option').find(item => item.text() === '范围').trigger('click')
    expect(card.config.filters[0].op).toBe('BETWEEN')

    await wrapper.findAll('.bi-range input')[0].setValue('100')
    await flushPromises()
    await wrapper.findAll('.bi-range input')[1].setValue('500')
    // amount 是数值列，要传数字而不是字符串
    expect(card.config.filters[0].value).toEqual([100, 500])
  })

  it('reorders values for a custom sort', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [], filters: [],
        sort: { by: 'dimension', index: 0, dir: 'asc' }, limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill.tone-sort').trigger('click')
    await flushPromises()
    await wrapper.findAll('.bi-menu-item').find(item => item.text().includes('自定义排序')).trigger('click')
    await flushPromises()

    const names = () => wrapper.findAll('.bi-custom-name').map(item => item.text())
    expect(names()).toEqual(['已完成', '处理中', '新建'])

    await wrapper.findAll('.bi-custom-move').at(1).trigger('click')
    expect(card.config.sort.custom).toEqual(['处理中', '已完成', '新建'])
  })

  it('keeps the field menu open while picking from a dropdown inside it', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: { dimensions: [], metrics: [], filters: [{ column: 'status', op: '=', value: '' }], sort: { by: 'none', index: 0, dir: 'desc' }, limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.find('.bi-pill').trigger('click')
    await wrapper.find('.bi-pill-menu .select-trigger').trigger('click')
    const option = wrapper.findAll('.select-popover .select-list button').find(item => item.text() === '<>')
    // 点下拉弹层不能让字段菜单一起关掉，否则操作符永远选不上
    option.element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    expect(wrapper.find('.bi-pill-menu').exists()).toBe(true)
    await option.trigger('click')
    expect(card.config.filters[0].op).toBe('<>')
  })

  it('narrows the config when switching the chart type', async () => {
    const card = {
      type: 'table', title: '表', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }, { column: 'amount' }],
        metrics: [{ column: 'amount', agg: 'SUM' }, { column: 'amount', agg: 'MAX' }],
        filters: [], limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.findAll('.bi-type-btn').find(button => button.text().includes('饼图')).trigger('click')
    expect(card.type).toBe('pie')
    expect(card.config.dimensions).toHaveLength(1)
    expect(card.config.metrics).toHaveLength(1)
  })

  it('flips display options from their defaults', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const labelRow = wrapper.findAll('.bi-toggle-row').find(row => row.text().includes('数据标签'))
    await labelRow.trigger('click')
    expect(card.config.showLabel).toBe(true)
    await labelRow.trigger('click')
    expect(card.config.showLabel).toBe(false)
  })
})
