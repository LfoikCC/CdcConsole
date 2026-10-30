import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FlowCanvas from './FlowCanvas.vue'

const graph = {
  nodes: [{ id: 'a', type: 'DB_INPUT', x: 40, y: 40, name: '订单', config: {} }],
  edges: []
}

function mountCanvas() {
  return mount(FlowCanvas, {
    props: { graph, selectedId: 'a' },
    attachTo: document.body
  })
}

function pointer(type, x, y, target) {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 })
  ;(target || window).dispatchEvent(event)
}

describe('FlowCanvas deselect', () => {
  it('emits deselect on a blank single click', async () => {
    const wrapper = mountCanvas()
    pointer('pointerdown', 10, 10, wrapper.find('.flow-canvas').element)
    pointer('pointerup', 10, 10)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('deselect')).toHaveLength(1)
    wrapper.unmount()
  })

  it('does not deselect when the blank area was dragged', async () => {
    const wrapper = mountCanvas()
    pointer('pointerdown', 10, 10, wrapper.find('.flow-canvas').element)
    pointer('pointermove', 60, 60)
    pointer('pointerup', 60, 60)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('deselect')).toBeUndefined()
    wrapper.unmount()
  })

  it('ignores clicks on the zoom controls inside the canvas', async () => {
    const wrapper = mountCanvas()
    pointer('pointerdown', 10, 10, wrapper.find('.flow-zoom').element)
    pointer('pointerup', 10, 10)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('deselect')).toBeUndefined()
    wrapper.unmount()
  })
})
