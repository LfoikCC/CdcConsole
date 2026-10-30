import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NodeDrawer from './NodeDrawer.vue'
import { CdcSelect, components } from '../ui'

vi.mock('../../api', () => ({ api: vi.fn() }))

const upstreamColumns = [{ label: 'id' }, { label: 'amount' }, { label: 'status' }]

function mountDrawer(node) {
  return mount(NodeDrawer, {
    props: {
      node,
      sources: [],
      targets: [],
      upstreamColumns,
      preview: null,
      busy: false
    },
    global: { components }
  })
}

const filterNode = () => ({
  id: 'n1',
  type: 'OPERATOR',
  name: '行过滤',
  config: { operator: 'FILTER', join: 'AND', conditions: [{ column: 'id', op: '=', value: '1' }] }
})

describe('NodeDrawer', () => {
  it('renames the node when applying', async () => {
    const wrapper = mountDrawer(filterNode())
    await wrapper.find('input').setValue('只看大额订单')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    const payload = wrapper.emitted('apply')[0][0]
    expect(payload.name).toBe('只看大额订单')
    expect(payload.config.operator).toBe('FILTER')
  })

  it('falls back to the operator label when the name is blank', async () => {
    const wrapper = mountDrawer({ ...filterNode(), name: '行过滤' })
    await wrapper.find('input').setValue('   ')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    expect(wrapper.emitted('apply')[0][0].name).toBe('行过滤')
  })

  it('replaces the whole config when switching operators', async () => {
    const wrapper = mountDrawer(filterNode())
    const operatorSelect = wrapper.findAllComponents(CdcSelect)[0]
    await operatorSelect.vm.$emit('update:modelValue', 'AGGREGATE')
    await flushPromises()

    expect(wrapper.text()).toContain('添加聚合项')
    await wrapper.find('.nd-foot .btn-primary').trigger('click')

    const config = wrapper.emitted('apply')[0][0].config
    expect(config.operator).toBe('AGGREGATE')
    expect(config.aggregates).toEqual([])
    // SORT/DEDUP 共用 keys 的历史残留不能带过来
    expect(config.conditions).toBeUndefined()
  })

  it('renders the calculate form with the upstream columns as a hint', async () => {
    const wrapper = mountDrawer({
      id: 'n2',
      type: 'OPERATOR',
      name: '添加计算列',
      config: { operator: 'CALCULATE', alias: '', expression: '', type: 'DECIMAL(38,10)' }
    })

    expect(wrapper.text()).toContain('新列名')
    expect(wrapper.text()).toContain('id、amount、status')
    expect(wrapper.text()).toContain('DECIMAL(38,10)')
  })
})
