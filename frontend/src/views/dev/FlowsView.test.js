import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import FlowsView from './FlowsView.vue'
import { components } from '../../components/ui'
import { identity } from '../../auth'
import { api } from '../../api'
import { CdcMessageBox } from '../../components/ui/feedback'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('../../components/ui/feedback', () => ({
  CdcMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  CdcMessageBox: { confirm: vi.fn() }
}))

const flow = { id: 6416, name: '订单清洗(MySQL)', description: '示例', nodeCount: 3, version: 2 }

let wrapper

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dev/flows', component: FlowsView },
      { path: '/dev/flows/:id', component: { template: '<div/>' } },
      { path: '/dev/flows/new', component: { template: '<div/>' } }
    ]
  })
  await router.push('/dev/flows')
  await router.isReady()
  wrapper = mount(FlowsView, { global: { plugins: [router], components } })
  await flushPromises()
}

/** 操作菜单是 teleport 到 body 的，只能从 document 里找。 */
async function menuButtons() {
  await wrapper.find('.row-actions .menu-trigger').trigger('click')
  await flushPromises()
  return Array.from(document.querySelectorAll('.menu.show button'))
}

beforeEach(() => {
  identity.user = { role: 'ADMIN' }
  api.mockReset()
  CdcMessageBox.confirm.mockReset()
  api.mockImplementation(async (path, options) => {
    if (path === '/flows/summary') return { total: 1, running: 0, failed: 0, succeeded: 0 }
    if (options?.method === 'DELETE') return null
    return { items: [flow], total: 1 }
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('FlowsView actions', () => {
  it('shows the same action pattern as sync tasks', async () => {
    await mountView()

    expect(wrapper.find('thead th:last-child').text()).toBe('操作')
    expect(wrapper.find('.row-actions a.btn').text()).toBe('详情')
    const buttons = await menuButtons()
    expect(buttons.map(button => button.textContent.trim())).toEqual(['运行', '移动到文件夹', '编辑', '删除'])
  })

  it('deletes a flow after confirmation', async () => {
    CdcMessageBox.confirm.mockResolvedValue()
    await mountView()

    const remove = (await menuButtons()).find(button => button.textContent.trim() === '删除')
    remove.click()
    await flushPromises()

    expect(CdcMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('订单清洗(MySQL)'), '删除数据流', expect.anything())
    expect(api).toHaveBeenCalledWith('/flows/6416', { method: 'DELETE' })
    expect(api.mock.calls.filter(([path]) => String(path).startsWith('/flows?'))).toHaveLength(2)
  })

  it('keeps the flow when the confirmation is cancelled', async () => {
    CdcMessageBox.confirm.mockRejectedValue('cancel')
    await mountView()

    const remove = (await menuButtons()).find(button => button.textContent.trim() === '删除')
    remove.click()
    await flushPromises()

    expect(api).not.toHaveBeenCalledWith('/flows/6416', { method: 'DELETE' })
  })

  it('hides edit and delete from non-admins', async () => {
    identity.user = { role: 'OPERATOR' }
    await mountView()

    const buttons = await menuButtons()
    expect(buttons.map(button => button.textContent.trim())).toEqual(['运行'])
  })

  it('filters by the summary cards', async () => {
    await mountView()
    const cards = wrapper.findAll('.summary-item')
    expect(cards[0].classes()).toContain('active')

    await cards[2].trigger('click')
    await flushPromises()

    const requests = api.mock.calls.map(([path]) => String(path)).filter(path => path.startsWith('/flows?'))
    expect(requests.at(-1)).toContain('view=failed')
    expect(cards[2].classes()).toContain('active')
    expect(cards[0].classes()).not.toContain('active')

    await cards[0].trigger('click')
    await flushPromises()
    expect(api.mock.calls.map(([path]) => String(path)).filter(path => path.startsWith('/flows?')).at(-1))
      .toContain('view=all')
  })
})
