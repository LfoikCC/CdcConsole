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

describe('FlowCanvas palette drop preview', () => {
  const graph = {
    nodes: [
      { id: 'n1', type: 'DB_INPUT', x: 0, y: 0, name: '源表', config: {} },
      { id: 'n2', type: 'DB_OUTPUT', x: 900, y: 0, name: '入库', config: {} }
    ],
    edges: []
  }

  function dragOver(wrapper, x, y) {
    const canvas = wrapper.find('.flow-canvas').element
    canvas.dispatchEvent(new MouseEvent('dragover', { bubbles: true, clientX: x, clientY: y }))
  }

  it('draws the dashed line to the node it will auto-connect to', async () => {
    const wrapper = mount(FlowCanvas, {
      props: { graph, dragging: { type: 'OPERATOR', operator: 'FILTER' } },
      attachTo: document.body
    })
    dragOver(wrapper, 200, 40)
    await wrapper.vm.$nextTick()

    const draft = wrapper.find('.flow-edge.is-draft')
    expect(draft.exists()).toBe(true)
    // 起点是源表的出端口 (80, 32)，终点是新节点将来的入端口
    expect(draft.attributes('d').startsWith('M 80 32 C ')).toBe(true)
    expect(draft.attributes('d').endsWith('160 40')).toBe(true)
    // 落点虚框里带算子图标，一眼能看出要放什么
    expect(wrapper.find('.fnode-ghost .fnode-ghost-icon svg').exists()).toBe(true)
    wrapper.unmount()
  })

  it('skips the preview for input-only operators', async () => {
    const wrapper = mount(FlowCanvas, {
      props: { graph, dragging: { type: 'DB_INPUT' } },
      attachTo: document.body
    })
    dragOver(wrapper, 200, 40)
    await wrapper.vm.$nextTick()

    // 落点虚框还在（用户得看到会落在哪），但没有连线
    expect(wrapper.find('.fnode-ghost').exists()).toBe(true)
    expect(wrapper.find('.flow-edge.is-draft').exists()).toBe(false)
    wrapper.unmount()
  })

  it('clears the preview when the drag ends', async () => {
    const wrapper = mount(FlowCanvas, {
      props: { graph, dragging: { type: 'OPERATOR', operator: 'FILTER' } },
      attachTo: document.body
    })
    dragOver(wrapper, 200, 40)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.flow-edge.is-draft').exists()).toBe(true)

    await wrapper.setProps({ dragging: null })
    expect(wrapper.find('.flow-edge.is-draft').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('FlowCanvas link preview', () => {
  const linked = {
    nodes: [
      { id: 'n1', type: 'DB_INPUT', x: 0, y: 0, name: '源表', config: {} },
      { id: 'n2', type: 'OPERATOR', x: 400, y: 0, name: '行过滤', config: { operator: 'FILTER' } }
    ],
    edges: []
  }

  function mountLinking() {
    return mount(FlowCanvas, { props: { graph: linked }, attachTo: document.body })
  }

  function startFrom(wrapper, index, x, y) {
    const port = wrapper.findAll('.fnode-port.out')[index].element
    port.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, clientX: x, clientY: y, button: 0 }))
  }

  it('snaps the dashed preview onto the node it will connect to', async () => {
    const wrapper = mountLinking()
    startFrom(wrapper, 0, 80, 32)
    // 指针落在目标卡片内：虚线应该直接收进它的入端口，而不是跟着鼠标走
    pointer('pointermove', 420, 32)
    await wrapper.vm.$nextTick()

    const draft = wrapper.find('.flow-edge.is-draft')
    expect(draft.classes()).toContain('is-snap')
    expect(draft.attributes('d').endsWith('400 32')).toBe(true)
    wrapper.unmount()
  })

  it('connects to the snapped node on release', async () => {
    const wrapper = mountLinking()
    startFrom(wrapper, 0, 80, 32)
    pointer('pointermove', 430, 30)
    pointer('pointerup', 430, 30)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('connect')).toEqual([[{ from: 'n1', to: 'n2' }]])
    wrapper.unmount()
  })

  it('never snaps onto an input-only node', async () => {
    const wrapper = mountLinking()
    // 从算子往上游拖：目标是输入节点，它没有入端口，不该被吸附也不该连上
    startFrom(wrapper, 1, 480, 32)
    pointer('pointermove', 60, 32)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.flow-edge.is-draft').classes()).not.toContain('is-snap')

    pointer('pointerup', 60, 32)
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('connect')).toBeUndefined()
    wrapper.unmount()
  })

  it('does not connect when the port was only clicked', async () => {
    const wrapper = mountLinking()
    startFrom(wrapper, 0, 80, 32)
    // 原地松开（没拖动）：点到端口不该把旁边的节点连上
    pointer('pointerup', 80, 32)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('connect')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('FlowCanvas edge deletion', () => {
  const linked = {
    nodes: [
      { id: 'a', type: 'DB_INPUT', x: 40, y: 40, name: '订单', config: {} },
      { id: 'b', type: 'DB_OUTPUT', x: 400, y: 40, name: '入库', config: {} }
    ],
    edges: [{ from: 'a', to: 'b' }]
  }

  function mountLinked() {
    return mount(FlowCanvas, { props: { graph: linked }, attachTo: document.body })
  }

  it('shows the delete button only after clicking an edge', async () => {
    const wrapper = mountLinked()
    expect(wrapper.find('.flow-edge-menu').exists()).toBe(false)

    await wrapper.find('.flow-edge-hit').trigger('click')
    expect(wrapper.find('.flow-edge-menu').exists()).toBe(true)
    // 点连线也要退出节点编辑
    expect(wrapper.emitted('select-edge')).toHaveLength(1)
    wrapper.unmount()
  })

  it('emits delete-edge with both ends when the button is clicked', async () => {
    const wrapper = mountLinked()
    await wrapper.find('.flow-edge-hit').trigger('click')
    await wrapper.find('.flow-edge-menu').trigger('click')

    expect(wrapper.emitted('delete-edge')).toEqual([[{ from: 'a', to: 'b' }]])
    // 删掉之后按钮跟着消失
    expect(wrapper.find('.flow-edge-menu').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the button off the canvas when the edge disappears', async () => {
    const wrapper = mountLinked()
    await wrapper.find('.flow-edge-hit').trigger('click')
    await wrapper.setProps({ graph: { nodes: linked.nodes, edges: [] } })

    expect(wrapper.find('.flow-edge-menu').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not treat an edge click as a blank click', async () => {
    const wrapper = mountLinked()
    const hit = wrapper.find('.flow-edge-hit').element
    pointer('pointerdown', 100, 60, hit)
    pointer('pointerup', 100, 60)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('deselect')).toBeUndefined()
    wrapper.unmount()
  })
})

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
