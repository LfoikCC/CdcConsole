import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { components } from '../../components/ui'
import { identity } from '../../auth'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('../../components/ui/feedback', () => ({
  CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  CdcMessageBox: { confirm: vi.fn() }
}))
// echarts 在 jsdom 里没有布局，画不出来；这里只要组件不报错即可
vi.mock('../../chartStyle', () => ({
  createChart: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), getDom: () => null }),
  chartStyle: () => ({ color: [], tooltip: {}, legend: {}, grid: {} }),
  chartAxis: () => ({})
}))

import { api } from '../../api'
import DashboardEditorView from './DashboardEditorView.vue'

let wrapper

const detail = {
  id: 1, name: '销售看板', description: '', status: 'DRAFT', version: 3,
  cards: [
    { id: 101, type: 'bar', title: '按状态汇总', datasetId: 7, x: 0, y: 0, w: 6, h: 8,
      config: { dimensions: [{ column: 'status' }], metrics: [{ column: 'amount', agg: 'SUM', alias: '' }], filters: [], limit: 100 } }
  ]
}

beforeEach(() => {
  identity.user = { role: 'ADMIN' }
  api.mockReset()
  api.mockImplementation(async (path, options) => {
    if (path === '/bi/dashboards/1' && !options?.method) return JSON.parse(JSON.stringify(detail))
    if (path === '/bi/dashboards/1/data') {
      return { elapsedMs: 5, cards: [{ cardId: 101, ok: true, columns: ['status', '求和(amount)'], rows: [['已完成', 100]], truncated: false }] }
    }
    if (path === '/datasets?size=100') return { items: [{ id: 7, name: 'Orders', datasetType: 'TABLE', columnCount: 2 }] }
    if (path === '/datasets/7') return { columns: [{ name: 'status', dataType: 'VARCHAR' }, { name: 'amount', dataType: 'DECIMAL' }] }
    if (path === '/bi/query') return { columns: ['status', '求和(amount)'], rows: [['已完成', 100]], truncated: false, elapsedMs: 3 }
    if (path === '/bi/dashboards/1/cards') {
      const body = JSON.parse(options.body)
      return { version: body.version + 1, cards: body.cards.map((card, index) => ({ ...card, id: 200 + index })) }
    }
    return {}
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountEditor() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/bi/dashboards/:id/edit', component: DashboardEditorView }]
  })
  await router.push('/bi/dashboards/1/edit')
  await router.isReady()
  wrapper = mount(DashboardEditorView, {
    global: { plugins: [router], components, directives: { loading: () => {} } }
  })
  await flushPromises()
  return wrapper
}

describe('DashboardEditorView', () => {
  it('renders the board with its cards and no palette', async () => {
    await mountEditor()
    expect(wrapper.findAll('.bi-card')).toHaveLength(1)
    // 左侧添加卡片栏已移除
    expect(wrapper.find('.bi-palette').exists()).toBe(false)
    expect(wrapper.text()).toContain('新建卡片')
  })

  it('opens the four-column card editor on double click', async () => {
    await mountEditor()
    await wrapper.find('.bi-card-head').trigger('dblclick')
    await flushPromises()

    expect(wrapper.find('.bi-card-editor').exists()).toBe(true)
    expect(wrapper.findAll('.bi-ce-col')).toHaveLength(4)
    // 左栏显示数据集与字段，中间是预览
    expect(wrapper.text()).toContain('Orders')
    expect(wrapper.findAll('.bi-field').map(item => item.text())).toEqual(['status', 'amount'])
  })

  it('edits a card and saves the whole board on 完成', async () => {
    await mountEditor()
    await wrapper.find('.bi-card-head').trigger('dblclick')
    await flushPromises()

    await wrapper.findAll('.bi-field').find(item => item.text() === 'amount').trigger('click')
    await flushPromises()

    const done = wrapper.findAll('.bi-editor-actions button').find(button => button.text().includes('完成'))
    await done.trigger('click')
    await flushPromises()

    const call = api.mock.calls.find(([path, options]) => path === '/bi/dashboards/1/cards' && options?.method === 'PUT')
    expect(call).toBeTruthy()
    const body = JSON.parse(call[1].body)
    expect(body.version).toBe(3)
    expect(body.cards[0].config.metrics).toHaveLength(2)
    // 保存后回到看板视图
    expect(wrapper.find('.bi-card-editor').exists()).toBe(false)
  })
})
