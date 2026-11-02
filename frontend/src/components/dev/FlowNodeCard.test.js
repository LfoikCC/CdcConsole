import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FlowNodeCard from './FlowNodeCard.vue'
import { NODE_H, NODE_W } from './flowGraph'

const node = {
  id: 'n1',
  type: 'DB_INPUT',
  x: 16,
  y: 24,
  name: '订单表',
  config: { sourceId: 1, database: 'demo', table: 'orders' }
}

describe('FlowNodeCard compact layout', () => {
  it('keeps only the icon and title, and moves the subtitle into the tooltip', () => {
    const wrapper = mount(FlowNodeCard, { props: { node } })

    expect(wrapper.find('.fnode-head').exists()).toBe(false)
    expect(wrapper.find('.fnode-sub').exists()).toBe(false)
    expect(wrapper.find('.fnode-title').text()).toBe('订单表')
    expect(wrapper.attributes('title')).toBe('订单表 · demo.orders')
    expect(wrapper.attributes('style')).toContain(`width: ${NODE_W}px`)
    expect(wrapper.attributes('style')).toContain(`height: ${NODE_H}px`)
  })

  it('keeps both input ports for two-input operators', () => {
    const wrapper = mount(FlowNodeCard, {
      props: {
        node: { id: 'j1', type: 'OPERATOR', x: 0, y: 0, name: '关联数据', config: { operator: 'JOIN' } }
      }
    })

    expect(wrapper.findAll('.fnode-port.in')).toHaveLength(2)
    expect(wrapper.find('.fnode-port.out').exists()).toBe(true)
  })
})
