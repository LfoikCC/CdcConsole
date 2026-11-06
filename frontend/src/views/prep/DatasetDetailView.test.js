import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { components } from '../../components/ui'
import { identity } from '../../auth'

vi.mock('../../api', () => ({ api: vi.fn(), upload: vi.fn() }))
vi.mock('../../components/ui/feedback', () => ({
  CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  CdcMessageBox: { confirm: vi.fn() }
}))

import { api } from '../../api'
import DatasetDetailView from './DatasetDetailView.vue'

const detail = {
  id: 12, name: '发货开票计划-营销维护', description: '手工录入', datasetType: 'EXCEL',
  accountId: null, rowCount: 3, columnCount: 2, status: 'READY',
  createdAt: '2026-10-28T16:48:45+08:00', updatedAt: '2026-10-29T08:36:28+08:00',
  columns: [
    { ordinal: 1, name: '客户编码', originalName: '客户编码', dataType: 'VARCHAR' },
    { ordinal: 2, name: '开票金额', originalName: '金额', dataType: 'DECIMAL(38,10)' }
  ],
  file: { fileName: '发货计划.xlsx', sizeBytes: 5242 },
  sheet: { sheetIndex: 0, sheetName: 'Sheet1', headerRow: 2, dedupeKey: '客户编码' }
}

const page = {
  columns: [
    { name: '客户编码', originalName: '客户编码', dataType: 'VARCHAR' },
    { name: '开票金额', originalName: '金额', dataType: 'DECIMAL(38,10)' }
  ],
  rows: [['1000', 120], ['1001', 80]],
  total: 120, hasMore: true, paged: true
}

let wrapper
let router

async function mountView() {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/prep/datasets', component: { template: '<div/>' } },
    { path: '/prep/datasets/:id', component: DatasetDetailView }
  ] })
  router.push('/prep/datasets/12')
  await router.isReady()
  wrapper = mount(DatasetDetailView, {
    global: { components, plugins: [router], stubs: { teleport: true } }
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  identity.user = { role: 'ADMIN' }
  api.mockReset()
  api.mockImplementation(async path => {
    if (path === '/datasets/12') return detail
    if (path === '/accounts?size=200') return { items: [] }
    if (path.startsWith('/datasets/12/rows')) return page
    return {}
  })
})

describe('DatasetDetailView', () => {
  it('shows the dataset header and the original column names', async () => {
    await mountView()
    expect(wrapper.find('.dataset-title h1').text()).toBe('发货开票计划-营销维护')
    expect(wrapper.text()).toContain('本地文件')
    expect(wrapper.text()).toContain('3 行')

    const rows = wrapper.findAll('.field-table tbody tr')
    // 字段名称是别名（输入框里），原始字段名称来自文件
    expect(rows[1].find('.field-name-input').element.value).toBe('开票金额')
    const cells = rows[1].findAll('td').map(cell => cell.text())
    expect(cells[2]).toBe('金额')
    expect(cells[3]).toBe('DECIMAL(38,10)')
  })

  it('loads paged rows in the data tab, 50 per page', async () => {
    await mountView()
    await wrapper.findAll('.dataset-tabs button').find(item => item.text() === '数据明细').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('共 120 条')
    expect(wrapper.text()).toContain('每页 50 行')
    expect(wrapper.findAll('.rows-table tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('1 / 3')
    expect(api.mock.calls.some(([path]) => path === '/datasets/12/rows?offset=0&limit=50')).toBe(true)

    await wrapper.find('[aria-label="下一页"]').trigger('click')
    await flushPromises()
    expect(api.mock.calls.some(([path]) => path === '/datasets/12/rows?offset=50&limit=50')).toBe(true)

    await wrapper.find('[aria-label="最后一页"]').trigger('click')
    await flushPromises()
    expect(api.mock.calls.some(([path]) => path === '/datasets/12/rows?offset=100&limit=50')).toBe(true)
  })

  it('renames a column display name in place without touching the source field', async () => {
    await mountView()

    // 字段名本身就是输入框：聚焦即编辑，不需要额外的图标
    const input = wrapper.findAll('.field-name-input')[1]
    expect(input.element.value).toBe('开票金额')
    await input.trigger('focus')
    await input.setValue('金额（元）')
    await input.trigger('blur')
    await flushPromises()

    const call = api.mock.calls.find(([path]) => path === '/datasets/12/columns/2')
    expect(call).toBeTruthy()
    expect(call[1]).toMatchObject({ method: 'PUT' })
    expect(JSON.parse(call[1].body)).toEqual({ name: '金额（元）' })
    // 原始字段名称不变
    const cells = wrapper.findAll('.field-table tbody tr')[1].findAll('td').map(cell => cell.text())
    expect(cells[2]).toBe('金额')
  })

  it('syncs database comments and resets names from the header buttons', async () => {
    api.mockImplementation(async path => {
      if (path === '/datasets/12') {
        return { ...detail, datasetType: 'TABLE', tableName: 'accident_detail', databaseName: 'DWD', file: null }
      }
      if (path === '/accounts?size=200') return { items: [] }
      if (path === '/datasets/12/columns/sync-comments' || path === '/datasets/12/columns/reset') {
        return { updated: 2 }
      }
      return {}
    })
    await mountView()

    const sync = wrapper.find('[aria-label="同步数据库字段注释"]')
    expect(sync.attributes('disabled')).toBeUndefined()
    await sync.trigger('click')
    await flushPromises()
    expect(api.mock.calls.some(([path, options]) =>
      path === '/datasets/12/columns/sync-comments' && options?.method === 'POST')).toBe(true)

    const reset = wrapper.find('[aria-label="重置为原始字段名"]')
    await reset.trigger('click')
    await flushPromises()
    expect(api.mock.calls.some(([path, options]) =>
      path === '/datasets/12/columns/reset' && options?.method === 'POST')).toBe(true)
  })

  it('disables comment sync for local files', async () => {
    await mountView()
    expect(wrapper.find('[aria-label="同步数据库字段注释"]').attributes('disabled')).toBeDefined()
  })

  it('offers append and replace for local files', async () => {
    await mountView()
    const trigger = wrapper.find('.dataset-actions .menu-trigger')
    expect(trigger.exists()).toBe(true)
    await trigger.trigger('click')
    await flushPromises()

    const items = wrapper.findAll('[role="menu"] button').map(item => item.text())
    expect(items).toContain('追加数据')
    expect(items).toContain('替换数据')

    await wrapper.findAll('[role="menu"] button').find(item => item.text() === '替换数据').trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal-title').text()).toContain('替换数据')
    expect(wrapper.findAll('.modal-foot button').map(item => item.text())).toContain('确认替换')
  })
})
