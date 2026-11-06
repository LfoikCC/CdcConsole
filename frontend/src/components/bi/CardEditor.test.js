import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
import { CdcMessage } from '../ui/feedback'
import CardEditor from './CardEditor.vue'
import { blankFilter } from './biFilters'
import ComputedFieldDialog from './ComputedFieldDialog.vue'
import { MAX_LIMIT } from './biCards'

const datasets = [
  { id: 7, name: 'Orders', datasetType: 'TABLE', databaseName: 'CDC-SOURCE', tableName: 'Orders',
    columnCount: 2, rowCount: 0, updatedAt: '2026-11-04T10:00:00+08:00' },
  { id: 8, name: 'Customers', datasetType: 'TABLE', databaseName: 'CDC-SOURCE', tableName: 'Customers',
    columnCount: 2, rowCount: 0, updatedAt: '2026-11-04T10:00:00+08:00' }
]

// 每个用例结束都卸载：预览有 260ms 防抖，不卸载的话上一个用例的定时器
// 会在下一个用例里补发一次 /bi/query，把「没配字段就不取数」的断言搅乱。
const mounted = []

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
})

function mountEditor(card, extra = {}) {
  const wrapper = mount(CardEditor, {
    props: { card, datasets, ...extra },
    // 字段菜单是 teleport 到 body 的，stub 掉才能直接在组件树里断言
    global: { components, stubs: { teleport: true } }
  })
  mounted.push(wrapper)
  return wrapper
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

  it('shows what is missing instead of querying an incomplete card', async () => {
    const card = { type: 'bar', title: '空图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [] } }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 维度数值都没配：不发请求，中间直接说要什么
    expect(api.mock.calls.some(([path]) => path === '/bi/query')).toBe(false)
    const hint = wrapper.find('.bi-card-hint')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('建议 1 个维度，1 个数值')
    // 图形本身不渲染
    expect(wrapper.find('.bi-chart').exists()).toBe(false)
  })

  it('asks for the metric once only the dimension is set', async () => {
    const card = {
      type: 'bar', title: '缺数值', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [], filters: [] }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    expect(api.mock.calls.some(([path]) => path === '/bi/query')).toBe(false)
    expect(wrapper.find('.bi-card-hint').text()).toContain('还需要 1 个数值')
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

  it('applies dashboard filters to the preview query', async () => {
    const card = {
      id: 101, type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }], filters: [], limit: 100 }
    }
    const select = { ...blankFilter('select'), datasetId: 7, column: 'status', values: ['已完成'] }
    const other = { ...blankFilter('condition'), datasetId: 8, column: 'name', keyword: '张' }
    const elsewhere = { ...blankFilter('select'), datasetId: 7, column: 'status', values: ['作废'], scope: 'cards', cardIds: [999] }
    mountEditor(card, { dashboardFilters: [select, other, elsewhere] })
    await flushPromises()

    const call = api.mock.calls.filter(([path]) => path === '/bi/query').at(-1)
    const config = JSON.parse(call[1].body).config
    // 只并命中这张卡的：别的数据集、以及指定了别的卡片的都不算
    expect(config.filters).toEqual([{ column: 'status', op: 'IN', value: ['已完成'] }])
    // 也不能把看板条件写回卡片配置
    expect(card.config.filters).toEqual([])
  })

  it('does not refetch when only the chart type changes', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()
    const queries = () => api.mock.calls.filter(([path]) => path === '/bi/query').length
    const before = queries()

    await wrapper.findAll('.bi-type-btn').find(button => button.text() === '折线图').trigger('click')
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 320))

    // 字段没变，只是换了画法：不该再打一次 /bi/query
    expect(card.type).toBe('line')
    expect(queries()).toBe(before)
  })

  it('edits a numeric data format from the right-side submenu', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 第二个茶壶是数值；「数据格式」点一下展开右侧二级菜单
    await wrapper.findAll('.bi-pill')[1].trigger('click')
    const sub = wrapper.findAll('.bi-menu-sub').find(el => el.text().includes('数据格式'))
    await sub.find('button').trigger('click')
    expect(wrapper.find('.bi-format-panel').exists()).toBe(true)

    // 类型选「数值」后，精度/千分位/计数单位/前后缀/预览才出现
    await wrapper.find('.bi-format-panel .select-trigger').trigger('click')
    const options = wrapper.findAll('.select-popover [role="option"]')
    await options.find(option => option.text() === '数值').trigger('click')

    const panel = wrapper.find('.bi-format-panel')
    expect(panel.text()).toContain('精度')
    expect(panel.text()).toContain('千分位分隔符')
    expect(panel.text()).toContain('计数单位')
    expect(panel.text()).toContain('预览')

    const inputs = panel.findAll('input[type="text"]')
    await inputs[0].setValue('¥')
    await wrapper.findAll('.bi-format-panel button').find(button => button.text() === '确定').trigger('click')

    expect(card.config.metrics[0].format).toMatchObject({ type: 'number', prefix: '¥' })
    expect(wrapper.find('.bi-format-panel').exists()).toBe(false)
  })

  it('opens submenus on click and closes them on the next click', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()
    await wrapper.findAll('.bi-pill')[1].trigger('click')

    // 聚合方式：点开、再点关（不再靠鼠标移开）
    const agg = wrapper.findAll('.bi-menu-sub')[0]
    await agg.find('button').trigger('click')
    expect(wrapper.find('.bi-submenu').exists()).toBe(true)
    // 鼠标移开不该收起
    await agg.trigger('mouseleave')
    expect(wrapper.find('.bi-submenu').exists()).toBe(true)
    await agg.find('button').trigger('click')
    expect(wrapper.find('.bi-submenu').exists()).toBe(false)

    // 数据格式同理
    const format = wrapper.findAll('.bi-menu-sub')[1]
    await format.find('button').trigger('click')
    expect(wrapper.find('.bi-format-panel').exists()).toBe(true)
    await format.trigger('mouseleave')
    expect(wrapper.find('.bi-format-panel').exists()).toBe(true)
    await format.find('button').trigger('click')
    expect(wrapper.find('.bi-format-panel').exists()).toBe(false)
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

  it('lists computed fields and edits them from the formula button', async () => {
    const card = {
      type: 'bar', title: '按状态汇总', datasetId: 7,
      config: {
        dimensions: [], metrics: [], filters: [], limit: 100,
        computed: [{ name: '客单价', type: 'NUMBER', expr: 'SUM({amount})' }]
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    // 计算字段排在维度之后、数值里排最前面
    expect(wrapper.findAll('.bi-field').map(item => item.text())).toEqual(['status', '客单价', 'amount'])
    expect(wrapper.find('.bi-field-add').exists()).toBe(true)

    await wrapper.find('.bi-field-edit').trigger('click')
    expect(wrapper.find('.cf-input').element.value).toBe('客单价')
  })

  it('stores a new computed field and keeps references when it is renamed', async () => {    const card = {
      type: 'bar', title: '按状态汇总', datasetId: 7,
      config: {
        dimensions: [], metrics: [], filters: [], limit: 100,
        computed: [{ name: '客单价', type: 'NUMBER', expr: 'SUM({amount})' }],
        metrics: [{ column: '客单价', agg: 'NONE', alias: '' }]
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const dialog = wrapper.findComponent(ComputedFieldDialog)
    dialog.vm.$emit('submit', { index: null, name: '毛利', type: 'NUMBER', expr: 'SUM({amount})' })
    await flushPromises()
    expect(card.config.computed.map(item => item.name)).toEqual(['客单价', '毛利'])
    expect(wrapper.emitted('change')).toBeTruthy()

    // 改名要把图里的引用一起改掉，否则预览会报「数据集里没有字段」
    dialog.vm.$emit('submit', { index: 0, name: '单价', type: 'NUMBER', expr: 'SUM({amount})' })
    await flushPromises()
    expect(card.config.metrics[0].column).toBe('单价')
  })
})

describe('CardEditor 字段排序与上限', () => {
  it('reorders pills when one is dragged inside the dimension zone', async () => {
    const card = {
      type: 'table', title: '明细', datasetId: 7,
      config: { dimensions: [{ column: 'status' }, { column: 'amount' }], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const pills = wrapper.findAll('.bi-pill.tone-dim')
    await pills[0].trigger('dragstart', { dataTransfer: { setData: () => {}, effectAllowed: '' } })
    const zone = wrapper.find('.bi-zone')
    await zone.trigger('dragover')
    await zone.trigger('drop')
    await flushPromises()

    // 拖到末尾（jsdom 里量不到真实坐标，落点按“整段之后”处理）
    expect(card.config.dimensions.map(item => item.column)).toEqual(['amount', 'status'])
  })

  it('treats an empty row limit as unlimited', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: MAX_LIMIT } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const input = wrapper.find('.bi-ce-visual input[type="number"]')
    expect(input.attributes('placeholder')).toBe('不限制')
    expect(input.element.value).toBe('')

    await input.setValue('50')
    expect(card.config.limit).toBe(50)

    // 清空又回到「不限制」
    await input.setValue('')
    expect(card.config.limit).toBe(MAX_LIMIT)
  })

  it('removes a field straight from the pill', async () => {
    const card = {
      type: 'table', title: '明细', datasetId: 7,
      config: { dimensions: [{ column: 'status' }, { column: 'amount' }], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.findAll('.bi-pill.tone-dim')[0].find('.bi-pill-x').trigger('click')
    await flushPromises()

    expect(card.config.dimensions.map(item => item.column)).toEqual(['amount'])
  })

  it('toggles table pagination and row numbers', async () => {
    const card = {
      type: 'table', title: '明细', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const rowFor = label => wrapper.findAll('.bi-toggle-row').find(row => row.text().includes(label))
    expect(rowFor('表格分页')).toBeTruthy()
    expect(rowFor('显示行序号')).toBeTruthy()

    await rowFor('表格分页').trigger('click')
    expect(card.config.pagination).toBe(true)
    // 开了分页才出现「每页行数」
    expect(rowFor('每页行数')).toBeTruthy()

    await rowFor('显示行序号').trigger('click')
    expect(card.config.showRowIndex).toBe(true)
  })

  it('clears the insertion slot once the pointer leaves the zone', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }],
        filters: [], limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const zone = wrapper.findAll('.bi-zone')[0]
    await wrapper.findAll('.bi-pill.tone-dim')[0]
      .trigger('dragstart', { dataTransfer: { setData: () => {}, effectAllowed: '' } })
    await zone.trigger('dragover')
    expect(zone.find('.bi-drop-slot').exists()).toBe(true)

    // 拖走之后「+」要跟着消失，不能留在原地
    await zone.trigger('dragleave')
    expect(zone.find('.bi-drop-slot').exists()).toBe(false)
  })

  it('offers the same drop slot on the filter zone', async () => {
    const card = {
      type: 'bar', title: '图', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM' }],
        filters: [], limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const filterZone = wrapper.findAll('.bi-section')
      .find(section => section.text().includes('筛选')).find('.bi-zone')
    expect(filterZone.find('.bi-drop-slot').exists()).toBe(false)

    await wrapper.findAll('.bi-field').find(item => item.text().includes('amount'))
      .trigger('dragstart', { dataTransfer: { setData: () => {}, effectAllowed: '' } })
    await filterZone.trigger('dragover')
    expect(filterZone.find('.bi-drop-slot').exists()).toBe(true)

    await filterZone.trigger('dragleave')
    expect(filterZone.find('.bi-drop-slot').exists()).toBe(false)
  })

  it('shows an insertion slot while a pill is dragged over the zone', async () => {
    const card = {
      type: 'table', title: '明细', datasetId: 7,
      config: { dimensions: [{ column: 'status' }], metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const zone = wrapper.find('.bi-zone')
    expect(zone.find('.bi-drop-slot').exists()).toBe(false)

    await wrapper.findAll('.bi-pill.tone-dim')[0]
      .trigger('dragstart', { dataTransfer: { setData: () => {}, effectAllowed: '' } })
    await zone.trigger('dragover')

    // 落点用一条带「+」的槽位标出来，拖动时不会整块变色
    expect(zone.find('.bi-drop-slot').exists()).toBe(true)
  })

  it('caps a table card at 50 fields', async () => {
    const dimensions = Array.from({ length: 50 }, (_, index) => ({ column: `c${index}` }))
    const card = {
      type: 'table', title: '宽表', datasetId: 7,
      config: { dimensions, metrics: [], filters: [], limit: 100 }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    await wrapper.findAll('.bi-field').find(item => item.text().includes('status')).trigger('click')
    await flushPromises()

    expect(card.config.dimensions).toHaveLength(50)
    expect(CdcMessage.warning).toHaveBeenCalled()
  })
})

describe('CardEditor 可视化类型', () => {
  it('offers every registered card type in the picker', async () => {
    const card = { type: 'bar', title: '图', datasetId: 7, config: { dimensions: [], metrics: [], filters: [], limit: 100 } }
    const wrapper = mountEditor(card)
    await flushPromises()

    const labels = wrapper.findAll('.bi-type-btn').map(item => item.text())
    // 12 种类型，3 列 x 4 行，超过 3 行由面板自己滚动
    expect(labels).toHaveLength(12)
    expect(labels).toEqual(expect.arrayContaining(['数据表', '指标卡', '柱状图', '条形图', '面积图', '漏斗图', '散点图']))
  })

  it('keeps only two metrics when switching to a scatter card', async () => {
    const card = {
      type: 'table', title: '明细', datasetId: 7,
      config: {
        dimensions: [{ column: 'status' }],
        metrics: [{ column: 'amount', agg: 'SUM' }, { column: 'id', agg: 'COUNT' }, { column: 'qty', agg: 'SUM' }],
        filters: [], limit: 100
      }
    }
    const wrapper = mountEditor(card)
    await flushPromises()

    const scatter = wrapper.findAll('.bi-type-btn').find(item => item.text() === '散点图')
    await scatter.trigger('click')
    await flushPromises()

    expect(card.type).toBe('scatter')
    expect(card.config.metrics).toHaveLength(2)
  })
})
