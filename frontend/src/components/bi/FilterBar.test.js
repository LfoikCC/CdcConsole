import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { components } from '../ui'

vi.mock('../../api', () => ({ api: vi.fn() }))

import { api } from '../../api'
import DashboardFilterBar from './DashboardFilterBar.vue'
import FilterWizard from './FilterWizard.vue'
import { blankFilter } from './biFilters'

const datasets = [
  { id: 7, name: 'Orders' },
  { id: 8, name: 'Customers' }
]
const cards = [
  { _k: 'k1', id: 101, type: 'bar', title: '金额趋势', datasetId: 7 },
  { _k: 'k2', id: 102, type: 'table', title: '明细', datasetId: 7 },
  { _k: 'k3', id: 103, type: 'bar', title: '客户', datasetId: 8 }
]

const mounted = []
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
})
beforeEach(() => {
  api.mockReset()
  api.mockImplementation(path => {
    if (path.startsWith('/datasets/')) {
      return Promise.resolve({
        columns: [
          { name: 'customer_name', dataType: 'VARCHAR' },
          { name: 'amount', dataType: 'DECIMAL(38,10)' },
          { name: 'updated_at', dataType: 'TIMESTAMP' }
        ]
      })
    }
    return Promise.resolve({ values: ['张伟', '李娜'], truncated: false })
  })
})

function mountBar(filters) {
  const wrapper = mount(DashboardFilterBar, {
    props: { filters, datasets },
    global: { components, stubs: { teleport: true } }
  })
  mounted.push(wrapper)
  return wrapper
}

function mountWizard(props = {}) {
  const wrapper = mount(FilterWizard, {
    props: { modelValue: true, datasets, cards, ...props },
    global: { components, stubs: { teleport: true } }
  })
  mounted.push(wrapper)
  return wrapper
}

describe('筛选栏', () => {
  it('收起按钮只显示图标和数量，不占「筛选器」三个字', () => {
    const filter = { ...blankFilter('condition'), datasetId: 7, column: 'customer_name', name: '客户' }
    const wrapper = mountBar([filter])
    const toggle = wrapper.find('.bi-filter-toggle')
    expect(toggle.text()).toBe('1')
    expect(toggle.text()).not.toContain('筛选器')
    expect(toggle.attributes('aria-label')).toBe('筛选器')
    // 图标和箭头都还在
    expect(toggle.findAll('svg')).toHaveLength(2)
  })

  it('没有筛选器时给出提示', () => {
    const wrapper = mountBar([])
    expect(wrapper.text()).toContain('还没有筛选器')
    expect(wrapper.text()).toContain('添加筛选')
  })

  it('选择类筛选器：栏上只有下拉，排除和全选在面板里', async () => {
    const filter = { ...blankFilter('select'), datasetId: 7, column: 'customer_name', name: '客户', values: ['张伟'] }
    const wrapper = mountBar([filter])
    await flushPromises()

    expect(wrapper.find('.bi-filter-name').text()).toContain('客户')
    // 候选值来自 /bi/datasets/{id}/values
    expect(api.mock.calls.some(([path]) => path.startsWith('/bi/datasets/7/values'))).toBe(true)
    // 栏上不再有独立的「排除」勾选框，宽度都给了下拉
    expect(wrapper.find('.bi-filter-exclude').exists()).toBe(false)
    expect(wrapper.find('.bi-fv-trigger').text()).toBe('张伟')

    await wrapper.find('.bi-fv-trigger').trigger('click')
    const panel = wrapper.find('.bi-fv-panel')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('全选')
    expect(panel.text()).toContain('已选 1')

    // 勾选「排除」：面板里改，筛选器上跟着变
    const excludeBox = panel.findAll('.bi-fv-check input')[1]
    await excludeBox.setValue(true)
    const emitted = wrapper.emitted('update:filters').at(-1)[0]
    expect(emitted[0].exclude).toBe(true)
  })

  it('面板里可以全选和清空', async () => {
    const filter = { ...blankFilter('select'), datasetId: 7, column: 'customer_name', name: '客户', values: [] }
    const wrapper = mountBar([filter])
    await flushPromises()
    await wrapper.find('.bi-fv-trigger').trigger('click')

    const panel = wrapper.find('.bi-fv-panel')
    // 候选值两条：张伟、李娜
    expect(panel.findAll('.bi-fv-item')).toHaveLength(2)

    await panel.findAll('.bi-fv-check input')[0].setValue(true)
    expect(wrapper.emitted('update:filters').at(-1)[0][0].values).toEqual(['张伟', '李娜'])

    await panel.findAll('.bi-fv-foot button').find(button => button.text() === '清空').trigger('click')
    expect(wrapper.emitted('update:filters').at(-1)[0][0].values).toEqual([])
  })

  it('条件类筛选器直接输入关键词', async () => {
    const filter = { ...blankFilter('condition'), datasetId: 7, column: 'customer_name', name: '客户' }
    const wrapper = mountBar([filter])

    await wrapper.find('.bi-filter-control input').setValue('张')
    expect(wrapper.emitted('update:filters').at(-1)[0][0].keyword).toBe('张')
  })

  it('日期筛选器在栏上只占一个按钮，点开才能选区间', async () => {
    const filter = { ...blankFilter('date'), datasetId: 7, column: 'updated_at', name: '日期' }
    const wrapper = mountBar([filter])

    // 栏上只有按钮，没有平铺的日期输入（以前快捷文案会被压成「最近 7.」）
    const button = wrapper.find('.bi-filter-date')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('全部时间')
    expect(wrapper.find('.bi-date-panel').exists()).toBe(false)

    await button.trigger('click')
    const panel = wrapper.find('.bi-date-panel')
    expect(panel.exists()).toBe(true)

    await panel.findAll('.bi-quick-chip').find(item => item.text() === '今年').trigger('click')
    const updated = wrapper.emitted('update:filters').at(-1)[0][0]
    expect(updated.quick).toBe('thisYear')
    expect(updated.from).toMatch(/^\d{4}-01-01$/)
    expect(updated.to).toMatch(/^\d{4}-12-31$/)
  })

  it('删除筛选器会整组回传', async () => {
    const first = { ...blankFilter('select'), datasetId: 7, column: 'a', name: 'A' }
    const second = { ...blankFilter('select'), datasetId: 7, column: 'b', name: 'B' }
    const wrapper = mountBar([first, second])

    await wrapper.findAll('.bi-filter-item')[0].findAll('.bi-filter-x')[1].trigger('click')
    expect(wrapper.emitted('update:filters').at(-1)[0].map(item => item.name)).toEqual(['B'])
  })

  it('可以收起筛选栏', async () => {
    const wrapper = mountBar([{ ...blankFilter('select'), datasetId: 7, column: 'a', name: 'A' }])
    expect(wrapper.find('.bi-filter-item').exists()).toBe(true)

    await wrapper.find('.bi-filter-toggle').trigger('click')
    expect(wrapper.find('.bi-filter-item').exists()).toBe(false)
    // 收起后角标还在，能看出筛了几个
    expect(wrapper.find('.bi-filter-count').text()).toBe('1')
  })
})

describe('查看页的筛选栏（只读可操作）', () => {
  it('不显示添加、编辑、删除，但控件照样能改值', async () => {
    const filter = { ...blankFilter('condition'), datasetId: 7, column: 'customer_name', name: '客户' }
    const wrapper = mount(DashboardFilterBar, {
      props: { filters: [filter], datasets, readonly: true },
      global: { components, stubs: { teleport: true } }
    })
    mounted.push(wrapper)

    expect(wrapper.classes()).toContain('is-readonly')
    expect(wrapper.find('.bi-filter-add').exists()).toBe(false)
    expect(wrapper.find('.bi-filter-x').exists()).toBe(false)
    // 名称不是按钮，点不了「编辑」
    expect(wrapper.find('button.bi-filter-name').exists()).toBe(false)
    expect(wrapper.find('.bi-filter-name.is-static').text()).toBe('客户')

    // 控件还在，改值会通知父级重取
    await wrapper.find('.bi-filter-control input').setValue('张')
    expect(wrapper.emitted('update:filters').at(-1)[0][0].keyword).toBe('张')
  })

  it('筛选器多到换行时全部渲染，不会被丢掉', () => {
    const many = Array.from({ length: 8 }, (_, index) => ({
      ...blankFilter('condition'), datasetId: 7, column: `col_${index}`, name: `条件${index}`
    }))
    const wrapper = mount(DashboardFilterBar, {
      props: { filters: many, datasets, readonly: true },
      global: { components, stubs: { teleport: true } }
    })
    mounted.push(wrapper)
    expect(wrapper.findAll('.bi-filter-item')).toHaveLength(8)
  })
})

describe('新建筛选器向导', () => {
  it('第一步是类型宫格，选完进属性配置', async () => {
    const wrapper = mountWizard()
    const types = wrapper.findAll('.bi-filter-type')
    expect(types.map(item => item.text())).toEqual(['选择', '条件', '范围', '日期', '组合条件'])

    await types.find(item => item.text() === '范围').trigger('click')
    expect(wrapper.find('.bi-filter-form').text()).toContain('比较方式')
  })

  it('选完数据集和字段后能走到联动并提交', async () => {
    const wrapper = mountWizard()
    await wrapper.findAll('.bi-filter-type').find(item => item.text() === '条件').trigger('click')

    // 数据集 → 触发字段加载
    const selects = wrapper.findAll('.bi-filter-form .select-trigger')
    await selects[0].trigger('click')
    const datasetOptions = wrapper.findAll('.select-popover [role="option"]')
    await datasetOptions.find(item => item.text() === 'Orders').trigger('click')
    await flushPromises()

    const fieldSelect = wrapper.findAll('.bi-filter-form .select-trigger')[1]
    await fieldSelect.trigger('click')
    await wrapper.findAll('.select-popover [role="option"]')
      .find(item => item.text().includes('customer_name')).trigger('click')

    // 关键词 + 名称
    await wrapper.findAll('.bi-filter-form input')[0].setValue('张')

    await wrapper.findAll('button').find(item => item.text() === '下一步').trigger('click')

    expect(wrapper.text()).toContain('作用范围')
    await wrapper.findAll('button').find(item => item.text() === '完成').trigger('click')

    const submitted = wrapper.emitted('submit').at(-1)[0]
    expect(submitted.type).toBe('condition')
    expect(submitted.datasetId).toBe(7)
    expect(submitted.column).toBe('customer_name')
    expect(submitted.keyword).toBe('张')
    expect(submitted.name).toBe('customer_name')
    expect(submitted.scope).toBe('dataset')
  })

  it('限定卡片时只列出同数据集的卡片', async () => {
    const wrapper = mountWizard()
    await wrapper.findAll('.bi-filter-type').find(item => item.text() === '选择').trigger('click')

    const selects = wrapper.findAll('.bi-filter-form .select-trigger')
    await selects[0].trigger('click')
    await wrapper.findAll('.select-popover [role="option"]').find(item => item.text() === 'Orders').trigger('click')
    await flushPromises()

    await wrapper.findAll('.bi-filter-form .select-trigger')[1].trigger('click')
    await wrapper.findAll('.select-popover [role="option"]')
      .find(item => item.text().includes('customer_name')).trigger('click')

    await wrapper.findAll('button').find(item => item.text() === '下一步').trigger('click')
    // 切到「指定的卡片」
    await wrapper.findAll('.segmented button').find(item => item.text() === '指定的卡片').trigger('click')

    const labels = wrapper.findAll('.bi-filter-card').map(item => item.text())
    expect(labels).toEqual(['金额趋势', '明细'])
  })

  it('编辑已有筛选器直接停在属性配置', async () => {
    const filter = { ...blankFilter('range'), datasetId: 7, column: 'amount', name: '金额', valueOp: '>', from: '100' }
    const wrapper = mountWizard({ filter })
    await flushPromises()

    expect(wrapper.find('.bi-filter-types').exists()).toBe(false)
    expect(wrapper.find('.bi-filter-form').text()).toContain('比较方式')
  })
})
