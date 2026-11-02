// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { permit, identity } from '../auth'
import AlertsView from './AlertsView.vue'
import { api } from '../api'
import { CdcTable, CdcTableColumn, CdcButton, CdcTag } from '../components/ui'

vi.mock('../api', () => ({ api: vi.fn() }))
vi.mock('../components/ui/feedback', () => ({ CdcMessage: { success: vi.fn(), error: vi.fn() } }))
const rows = [
  { id: 1, taskId: 1, status: 'OPEN', title: '待处理事件' },
  { id: 2, taskId: 1, status: 'ACKNOWLEDGED', title: '已确认事件' },
  { id: 3, taskId: 1, status: 'RESOLVED', title: '已解决事件' }
]
const counts = { open: 1, acknowledged: 1, resolved: 1, total: 3 }
const page = items => ({ items, total: items.length, page: 0, size: 20, totalPages: 1, counts })

function render() {
  return mount(AlertsView, {
    global: {
      plugins: [createPinia()],
      components: { CdcTable, CdcTableColumn, CdcButton, CdcTag },
      stubs: { RouterLink: true },
      directives: { loading: () => {}, permit }
    }
  })
}

beforeEach(() => {
  identity.user = { role: 'ADMIN' }
  api.mockReset()
  api.mockImplementation(async path => {
    if (path === '/alerts/counts') return counts
    if (path.startsWith('/alerts?')) return page(structuredClone(rows))
    return []
  })
})

describe('alert server-side pagination', () => {
  it('loads the first page and shows global counts', async () => {
    const wrapper = render()
    await flushPromises()

    expect(wrapper.findAll('.alert-filter').map(button => button.text()))
      .toEqual(['全部3', '待处理1', '已确认1', '已解决1'])
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    expect(api.mock.calls.some(([path]) => path === '/alerts?page=0&size=20')).toBe(true)
    wrapper.unmount()
  })

  it('refetches with the status parameter when a filter is selected', async () => {
    const wrapper = render()
    await flushPromises()
    api.mockImplementation(async path => {
      if (path === '/alerts/counts') return counts
      if (path.startsWith('/alerts?')) return page([rows[0]])
      return []
    })

    await wrapper.findAll('.alert-filter')[1].trigger('click')
    await flushPromises()

    expect(api.mock.calls.some(([path]) => path === '/alerts?page=0&size=20&status=OPEN')).toBe(true)
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.findAll('.alert-filter')[1].attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('keeps rows and selection when refresh fails', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('.alert-filter')[1].trigger('click')
    api.mockImplementation(async path => {
      if (path === '/alerts/counts') return counts
      throw new Error('offline')
    })
    window.dispatchEvent(new Event('cdc-runtime-change'))
    await flushPromises()

    expect(wrapper.findAll('.alert-filter')[1].attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('待处理事件')
    wrapper.unmount()
  })

  it('reloads after acknowledgement and keeps the pending filter', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('.alert-filter')[1].trigger('click')
    api.mockImplementation(async path => {
      if (path === '/alerts/counts') return { open: 0, acknowledged: 2, resolved: 1, total: 3 }
      if (path.startsWith('/alerts?')) return page([])
      return []
    })

    await wrapper.findAll('tbody button')[0].trigger('click')
    await flushPromises()

    expect(api).toHaveBeenCalledWith('/alerts/1/acknowledge', { method: 'POST' })
    expect(wrapper.text()).toContain('当前筛选下没有告警')
    wrapper.unmount()
  })
})
