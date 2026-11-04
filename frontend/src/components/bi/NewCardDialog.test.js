import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { components } from '../ui'

vi.mock('../../api', () => ({ api: vi.fn() }))

import { api } from '../../api'
import NewCardDialog from './NewCardDialog.vue'

const datasets = [
  { id: 7, name: 'Orders', datasetType: 'TABLE', databaseName: 'CDC-SOURCE', tableName: 'Orders',
    columnCount: 2, rowCount: 7, updatedAt: '2026-11-04T10:00:00+08:00' }
]

function mountDialog() {
  return mount(NewCardDialog, {
    props: { modelValue: true, datasets },
    global: { components, stubs: { teleport: true } }
  })
}

beforeEach(() => {
  api.mockReset()
  api.mockImplementation(async path => {
    if (path === '/datasets/7') return { columns: [{ name: 'id' }, { name: 'amount' }] }
    return {}
  })
})

describe('NewCardDialog', () => {
  it('keeps the submit disabled until title and dataset are filled', async () => {
    const wrapper = mountDialog()
    const submit = () => wrapper.findAll('.modal-foot button').find(button => button.text().includes('创建并编辑'))

    expect(submit().attributes('disabled')).toBeDefined()
    await wrapper.find('.modal-body input').setValue('按状态汇总')
    // 只填了标题还不够
    expect(submit().attributes('disabled')).toBeDefined()
  })

  it('shows the selected dataset details and creates the card', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.modal-body input').setValue('按状态汇总')

    await wrapper.find('.select-trigger').trigger('click')
    await flushPromises()
    // teleport 被 stub 掉后，弹层渲染在组件树里，直接在 wrapper 内找
    const option = wrapper.findAll('.select-popover .select-list button')
      .find(button => button.text().includes('Orders'))
    await option.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('CDC-SOURCE.Orders')
    expect(api.mock.calls.some(([path]) => path === '/datasets/7')).toBe(true)

    const submit = wrapper.findAll('.modal-foot button').find(button => button.text().includes('创建并编辑'))
    await submit.trigger('click')

    expect(wrapper.emitted('create')[0][0]).toMatchObject({ title: '按状态汇总', datasetId: 7, type: 'bar' })
  })
})
