import { describe, expect, it } from 'vitest'
import {
  FIT_MAX_ZOOM, MIN_ZOOM, NODE_H, NODE_W, autoUpstream, bounds, descendantsOf, edgeMidpoint, edgePathForNodes, fitView,
  hasEdge, nextNodeId, portIn, portOut, snap, toWorld, topologicalOrder, upstreamOf, wouldCycle
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

  it('puts the edge midpoint halfway between the ports', () => {
    // 控制点左右对称，t=0.5 化简后正好是两端点的中点
    expect(edgeMidpoint({ x: 80, y: 32 }, { x: 300, y: 96 })).toEqual({ x: 190, y: 64 })
    expect(edgeMidpoint({ x: 0, y: 0 }, { x: 0, y: 0 })).toEqual({ x: 0, y: 0 })
  })

  it('orders a linear graph', () => {
    expect(topologicalOrder(linear)).toEqual(['a', 'b', 'c'])
  })

  it('picks the nearest upstream node for a dropped node', () => {
    // a 在 (0,0)、b 在 (600,0)；新节点落在 (200,0)，中心到 a 是 200、到 b 是 400
    const g = { nodes: [node('a', 0, 0), node('b', 600, 0)], edges: [] }
    const ghost = node('new', 200, 0)
    expect(autoUpstream(g, ghost, 260)?.id).toBe('a')
    // 半径收到 100 就够不着了
    expect(autoUpstream(g, ghost, 100)).toBeNull()
  })

  it('never auto-connects to a sink node', () => {
    const g = { nodes: [{ id: 's', type: 'DB_OUTPUT', x: 120, y: 0 }], edges: [] }
    expect(autoUpstream(g, node('new', 260, 0), 260)).toBeNull()
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
    // 「适应」不放大：上限就是 100%（与算子菜单里的卡片同尺寸），下限防止缩到看不清
    expect(view.zoom).toBeGreaterThanOrEqual(MIN_ZOOM)
    expect(view.zoom).toBeLessThanOrEqual(FIT_MAX_ZOOM)
    // 平移后图应该落在视口中间，而不是贴左上角
    expect(view.panX).toBeGreaterThan(0)
  })

  it('shrinks for many nodes but never below the floor', () => {
    const many = Array.from({ length: 60 }, (_, i) => node('n' + i, (i % 10) * 260, Math.floor(i / 10) * 180))
    const view = fitView(many, { width: 900, height: 600 })
    expect(view.zoom).toBeLessThan(1)
    expect(view.zoom).toBeGreaterThanOrEqual(MIN_ZOOM)
  })

  it('never enlarges a small graph beyond 100%', () => {
    const view = fitView([node('a', 0, 0)], { width: 2000, height: 1200 })
    expect(view.zoom).toBe(1)
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
