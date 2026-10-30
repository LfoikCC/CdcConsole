import { describe, expect, it } from 'vitest'
import {
  NODE_H, NODE_W, bounds, descendantsOf, edgePathForNodes, fitView, hasEdge,
  nextNodeId, portIn, portOut, snap, toWorld, topologicalOrder, upstreamOf, wouldCycle
} from './flowGraph'

const node = (id, x, y) => ({ id, type: 'OPERATOR', x, y })

const linear = {
  nodes: [node('a', 0, 0), node('b', 300, 0), node('c', 600, 0)],
  edges: [{ from: 'a', to: 'b' }, { from: 'b', to: 'c' }]
}

describe('flowGraph', () => {
  it('snaps to the 8px grid', () => {
    expect(snap(0)).toBe(0)
    expect(snap(4)).toBe(8)
    expect(snap(11)).toBe(8)
    expect(snap(13)).toBe(16)
  })

  it('puts ports on opposite sides of the node', () => {
    const n = node('a', 100, 40)
    expect(portOut(n)).toEqual({ x: 100 + NODE_W, y: 40 + NODE_H / 2 })
    expect(portIn(n)).toEqual({ x: 100, y: 40 + NODE_H / 2 })
  })

  it('builds a cubic bezier between ports', () => {
    const path = edgePathForNodes(node('a', 0, 0), node('b', 400, 100))
    // 用常量推导，尺寸调整时这条断言不会假失败
    expect(path.startsWith(`M ${NODE_W} ${NODE_H / 2} C `)).toBe(true)
    expect(path).toContain(`400 ${100 + NODE_H / 2}`)
  })

  it('does not cross control points when nodes are almost vertically stacked', () => {
    // 水平间距极小（6px）时，两个控制点若互相越界，连线中段会画出多余的钩子
    const path = edgePathForNodes(node('a', 0, 0), node('b', 190, 80))
    const match = path.match(/C ([\d.-]+) [\d.-]+, ([\d.-]+)/)
    const [first, second] = [Number(match[1]), Number(match[2])]
    expect(first).toBeLessThanOrEqual(second)
  })

  it('orders a linear graph', () => {
    expect(topologicalOrder(linear)).toEqual(['a', 'b', 'c'])
  })

  it('returns null for a cycle', () => {
    const cyclic = {
      nodes: [node('a', 0, 0), node('b', 300, 0)],
      edges: [{ from: 'a', to: 'b' }, { from: 'b', to: 'a' }]
    }
    expect(topologicalOrder(cyclic)).toBeNull()
  })

  it('detects a cycle before connecting', () => {
    expect(wouldCycle(linear, 'c', 'a')).toBe(true)
    expect(wouldCycle(linear, 'a', 'c')).toBe(false)
    expect(wouldCycle(linear, 'a', 'a')).toBe(true)
  })

  it('finds upstream and duplicates', () => {
    expect(upstreamOf(linear, 'b')?.id).toBe('a')
    expect(upstreamOf(linear, 'a')).toBeNull()
    expect(hasEdge(linear, 'a', 'b')).toBe(true)
    expect(hasEdge(linear, 'b', 'a')).toBe(false)
  })

  it('collects descendants transitively', () => {
    expect([...descendantsOf(linear, 'a')].sort()).toEqual(['b', 'c'])
    expect([...descendantsOf(linear, 'c')]).toEqual([])
  })

  it('computes bounds including node size', () => {
    const box = bounds([node('a', 0, 0), node('b', 100, 50)])
    expect(box.width).toBe(100 + NODE_W)
    expect(box.height).toBe(50 + NODE_H)
  })

  it('fits the graph into the viewport and centers it', () => {
    const view = fitView([node('a', 0, 0), node('b', 400, 0)], { width: 1000, height: 600 })
    expect(view.zoom).toBeGreaterThan(0.4)
    expect(view.zoom).toBeLessThanOrEqual(1.4)
    // 平移后图应该落在视口中间，而不是贴左上角
    expect(view.panX).toBeGreaterThan(0)
  })

  it('converts client coordinates into world coordinates', () => {
    const rect = { left: 10, top: 20 }
    const world = toWorld(310, 220, rect, { x: 100, y: 50 }, 2)
    expect(world).toEqual({ x: 100, y: 75 })
  })

  it('picks the first free node id', () => {
    expect(nextNodeId([])).toBe('n1')
    expect(nextNodeId([{ id: 'n1' }, { id: 'n2' }])).toBe('n3')
    expect(nextNodeId([{ id: 'n2' }])).toBe('n1')
  })
})
