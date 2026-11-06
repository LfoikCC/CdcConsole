import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { components } from '../ui'

vi.mock('../../api', () => ({ api: vi.fn() }))

import { api } from '../../api'
import NewCardDialog from './NewCardDialog.vue'

const datasets = [
  { id: 7, name: 'Orders', datasetType: 'TABLE', accountId: 1, databaseName: 'CDC-SOURCE', tableName: 'Orders',
    columnCount: 2, rowCount: 0, description: '订单明细', updatedAt: '2026-11-04T10:00:00+08:00' },
  { id: 8, name: 'SalesDaily', datasetType: 'TABLE', accountId: 2, databaseName: 'ADS', tableName: 'sales_daily',
    columnCount: 3, rowCount: 0, updatedAt: '2026-11-06T09:30:00+08:00' },
  { id: 9, name: '门店清单', datasetType: 'EXCEL', accountId: null, columnCount: 1, rowCount: 12,
    updatedAt: '2026-11-05T16:00:00+08:00' }
]

function mountDialog(props = {}) {
  return mount(NewCardDialog, {
    props: { modelValue: true, datasets, ...props },
    global: { components, stubs: { teleport: true } }
  })
}

beforeEach(() => {
  api.mockReset()
  api.mockImplementation(async path => {
    if (path === '/accounts?size=200') {
      return { items: [{ id: 1, accountType: 'MYSQL' }, { id: 2, accountType: 'STARROCKS' }] }
    }
    if (path === '/datasets/7') {
      return {
        columns: [
          { name: 'id', dataType: 'BIGINT' },
          { name: 'customer_name', dataType: 'VARCHAR' },
          { name: 'created_at', dataType: 'TIMESTAMP' }
        ],
        file: null
      }
    }
    if (path === '/bi/datasets/7/stats') return { rows: 85353 }
    if (path === '/datasets/9') return { columns: [{ name: '门店', dataType: 'VARCHAR' }], file: { name: '门店清单.xlsx' } }
    return {}
  })
})

describe('NewCardDialog', () => {
  it('keeps the submit disabled until title and dataset are filled', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const submit = () => wrapper.findAll('.modal-foot button').find(button => button.text().includes('创建并编辑'))

    expect(submit().attributes('disabled')).toBeDefined()
    await wrapper.find('.bi-newcard-title input').setValue('按状态汇总')
    // 只填了标题还不够
    expect(submit().attributes('disabled')).toBeDefined()
  })

  it('lists datasets newest first', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const names = wrapper.findAll('.bi-picker-row .bi-picker-name').map(item => item.text())
    // updatedAt 新的在前：SalesDaily(11-06) → 门店清单(11-05) → Orders(11-04)
    expect(names).toEqual(['SalesDaily', '门店清单', 'Orders'])
  })

  it('shows the database brand for tables and the excel logo for imports', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const rows = wrapper.findAll('.bi-picker-row')
    const logo = index => rows[index].find('.brand-icon img').attributes('src')
    // 库表按数据账户的类型取 LOGO：StarRocks / MySQL
    expect(logo(0)).toBe('/brands/starrocks-icon.svg')
    expect(logo(1)).toBe('/brands/excel-icon.svg')
    expect(logo(2)).toBe('/brands/mysql-small.png')
    expect(rows[0].find('.bi-pick-badge').text()).toBe('直连库表')
    expect(rows[1].find('.bi-pick-badge').text()).toBe('Excel 导入')
  })

  it('shows the selected dataset details and creates the card', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.find('.bi-newcard-title input').setValue('按状态汇总')
    await wrapper.findAll('.bi-picker-row').find(row => row.text().includes('Orders')).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('根目录 / CDC-SOURCE / Orders')
    expect(wrapper.text()).toContain('订单明细')
    expect(wrapper.text()).toContain('85,353')
    // 字段按类型配图标，名字都在
    expect(wrapper.findAll('.bi-pick-field').map(item => item.text()))
      .toEqual(['id', 'customer_name', 'created_at'])
    expect(api.mock.calls.some(([path]) => path === '/datasets/7')).toBe(true)

    const submit = wrapper.findAll('.modal-foot button').find(button => button.text().includes('创建并编辑'))
    await submit.trigger('click')

    expect(wrapper.emitted('create')[0][0]).toMatchObject({ title: '按状态汇总', datasetId: 7, type: 'bar' })
  })

  it('defaults to the datasets this page already uses', async () => {
    const wrapper = mountDialog({ usedDatasetIds: [7, 9] })
    await flushPromises()
    // 只看本页在用的：更新时间倒序 门店清单 → Orders
    expect(wrapper.findAll('.bi-picker-row .bi-picker-name').map(item => item.text()))
      .toEqual(['门店清单', 'Orders'])
    expect(wrapper.find('.bi-picker-type .select-value').text()).toBe('页面数据集')
  })

  it('shows all datasets when the page has no card yet', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    expect(wrapper.findAll('.bi-picker-row')).toHaveLength(3)
    expect(wrapper.find('.bi-picker-type .select-value').text()).toBe('全部')
  })

  it('can switch from the page datasets to all of them', async () => {
    const wrapper = mountDialog({ usedDatasetIds: [8] })
    await flushPromises()
    expect(wrapper.findAll('.bi-picker-row').map(item => item.text())).toEqual([expect.stringContaining('SalesDaily')])

    await wrapper.find('.bi-picker-type .select-trigger').trigger('click')
    await flushPromises()
    await wrapper.findAll('.select-popover [role="option"]').find(item => item.text() === '全部').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.bi-picker-row')).toHaveLength(3)
    expect(wrapper.find('.bi-picker-type .select-value').text()).toBe('全部')
  })

  it('filters the list by keyword', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.find('.bi-picker-search input').setValue('sales')
    expect(wrapper.findAll('.bi-picker-row')).toHaveLength(1)

    await wrapper.find('.bi-picker-search input').setValue('nope')
    expect(wrapper.text()).toContain('没有匹配的数据集')
  })
})
