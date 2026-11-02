// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FlowRunView from './FlowRunView.vue'
import { api } from '../../api'
import { CdcButton } from '../../components/ui'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1', runId: '2' } }),
  useRouter: () => ({ push: vi.fn() })
}))
const confirm = vi.fn()
vi.mock('../../components/ui/feedback', () => ({
  CdcMessage: { success: vi.fn(), error: vi.fn() },
  CdcMessageBox: { confirm: (...args) => confirm(...args) }
}))

const run = {
  id: 2, flowId: 1, flowVersion: 1, status: 'RUNNING', triggerType: 'MANUAL',
  actor: 'admin', startedAt: '2026-11-01T01:00:00+08:00', endedAt: null, durationMs: null,
  nodeTotal: 1, nodeSucceeded: 0, rowsWritten: 0, message: null
}

function render() {
  return mount(FlowRunView, { global: { components: { CdcButton }, stubs: { RouterLink: true }, directives: { loading: () => {} } } })
}

beforeEach(() => {
  api.mockReset()
  confirm.mockReset()
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/cancel')) return null
    return { run: structuredClone(run), nodes: [] }
  })
})

describe('FlowRunView cancel', () => {
  it('shows cancel only while running and calls the cancel endpoint after confirmation', async () => {
    confirm.mockResolvedValue()
    const wrapper = render()
    await flushPromises()

    const cancel = wrapper.findAll('button').find(button => button.text().includes('取消运行'))
    expect(cancel).toBeTruthy()

    await cancel.trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(api).toHaveBeenCalledWith('/flows/1/runs/2/cancel', { method: 'POST' })
    wrapper.unmount()
  })

  it('hides cancel for finished runs', async () => {
    api.mockImplementation(async () => ({ run: { ...run, status: 'SUCCEEDED' }, nodes: [] }))
    const wrapper = render()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text().includes('取消运行'))).toBe(false)
    wrapper.unmount()
  })
})
