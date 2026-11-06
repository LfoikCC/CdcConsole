import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ComputedFieldDialog from './ComputedFieldDialog.vue'
import { components } from '../ui'
import { CdcMessage } from '../ui/feedback'

vi.mock('../ui/feedback', () => ({ CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const fields = [
  { name: 'status', dataType: 'VARCHAR', numeric: false },
  { name: 'amount', dataType: 'DECIMAL(38,10)', numeric: true }
]

function mountDialog(extra = {}) {
  return mount(ComputedFieldDialog, {
    props: { modelValue: true, fields, computed: [], editing: null, ...extra },
    // 弹窗是 teleport 到 body 的，stub 掉才能在组件树里直接断言
    global: { components, stubs: { teleport: true } }
  })
}

function button(wrapper, label) {
  return wrapper.findAll('button').find(item => item.text().trim() === label)
}

beforeEach(() => {
  CdcMessage.warning.mockReset()
})

describe('ComputedFieldDialog', () => {
  it('inserts functions and field placeholders at the cursor', async () => {
    const wrapper = mountDialog()

    await wrapper.findAll('.cf-fn').find(item => item.text() === 'SUM').trigger('click')
    await wrapper.findAll('.cf-field').find(item => item.text().includes('amount')).trigger('click')

    expect(wrapper.find('.cf-code').element.value).toBe('SUM({amount}')
  })

  it('submits the name, type and expression', async () => {
    const wrapper = mountDialog()

    await wrapper.find('.cf-input').setValue('客单价')
    await wrapper.find('.select-trigger').trigger('click')
    await wrapper.find('[role=option]').trigger('click')
    await wrapper.find('.cf-code').setValue('SUM({amount}) / COUNT({status})')
    await button(wrapper, '确定').trigger('click')

    expect(wrapper.emitted('submit')[0][0]).toEqual({
      index: null,
      name: '客单价',
      type: 'NUMBER',
      expr: 'SUM({amount}) / COUNT({status})'
    })
  })

  it('refuses duplicate or colliding names', async () => {
    const wrapper = mountDialog({ computed: [{ name: '客单价', type: 'NUMBER', expr: 'SUM({amount})' }] })

    await wrapper.find('.cf-input').setValue('客单价')
    await wrapper.find('.select-trigger').trigger('click')
    await wrapper.find('[role=option]').trigger('click')
    await wrapper.find('.cf-code').setValue('SUM({amount})')
    await button(wrapper, '确定').trigger('click')

    expect(CdcMessage.warning).toHaveBeenCalled()
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('opens the function search from the header icon', async () => {
    const wrapper = mountDialog()
    // 字段页签本身也有搜索框，所以按数量判断：收起时 1 个，展开后 2 个
    expect(wrapper.findAll('.cf-mini-search')).toHaveLength(1)

    await wrapper.find('.cf-pane-icon').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.cf-mini-search')).toHaveLength(2)

    const input = wrapper.find('input[aria-label="搜索函数"]')
    await input.setValue('count')
    const names = wrapper.findAll('.cf-fn').map(item => item.text())
    expect(names).toContain('COUNT')
    expect(names).not.toContain('ABS')
  })

  it('warns when the expression references an unknown field', async () => {
    const wrapper = mountDialog()

    await wrapper.find('.cf-input').setValue('合计')
    await wrapper.find('.select-trigger').trigger('click')
    await wrapper.find('[role=option]').trigger('click')
    await wrapper.find('.cf-code').setValue('SUM({nope})')
    await button(wrapper, '确定').trigger('click')

    expect(CdcMessage.warning).toHaveBeenCalledWith(expect.stringContaining('nope'))
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('opens with the existing values when editing and can delete', async () => {
    const wrapper = mountDialog({ editing: { index: 1, name: '客单价', type: 'NUMBER', expr: 'SUM({amount})' } })

    expect(wrapper.text()).toContain('编辑计算字段')
    expect(wrapper.find('.cf-input').element.value).toBe('客单价')
    expect(wrapper.find('.cf-code').element.value).toBe('SUM({amount})')

    await button(wrapper, '删除').trigger('click')
    expect(wrapper.emitted('remove')[0]).toEqual([1])
  })

  it('leaves the parameters tab empty for future global parameters', async () => {
    const wrapper = mountDialog()

    await wrapper.findAll('.cf-tabs button').find(item => item.text() === '参数').trigger('click')

    expect(wrapper.text()).toContain('暂无全局参数')
    // 参数页不是函数说明，别再把签名列表塞进来
    expect(wrapper.text()).not.toContain('SUM(数值)')
  })

  it('puts the caret in the editor when clicking anywhere in the box', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.cf-code').setValue('SUM({amount})')

    // 点行号（或下方空白）也算点进编辑框，光标落到末尾
    await wrapper.find('.cf-gutter').trigger('click')

    const el = wrapper.find('.cf-code').element
    expect(el.selectionStart).toBe(el.value.length)
  })
})
