/**
 * 画布的纯几何与图算法。
 *
 * 节点尺寸是常量而不是测量 DOM 得到的：坐标与渲染彻底解耦，fitView、单测和持久化才能用同一套数字。
 */
export const NODE_W = 184
export const NODE_H = 60
export const GRID = 8

export function snap(value) {
  return Math.round(value / GRID) * GRID
}

export function portOut(node) {
  return { x: node.x + NODE_W, y: node.y + NODE_H / 2 }
}

export function portIn(node) {
  return { x: node.x, y: node.y + NODE_H / 2 }
}

/** 三次贝塞尔：控制点横向外扩。
 *
 * 距离取「水平间距的一半」，并且**不设下限**。
 * 一旦强制一个最小偏移（比如 16px），两个控制点就会在间距很小时互相越界，
 * 连线中段会画出一个多余的钩子——节点几乎垂直相邻时最明显。
 */
export function edgePath(from, to) {
  const gap = Math.abs(to.x - from.x)
  const dx = Math.min(gap / 2, 160)
  return `M ${from.x} ${from.y} C ${from.x + dx} ${from.y}, ${to.x - dx} ${to.y}, ${to.x} ${to.y}`
}

export function edgePathForNodes(fromNode, toNode) {
  return edgePath(portOut(fromNode), portIn(toNode))
}

export function nodeById(graph, id) {
  return (graph?.nodes || []).find(node => node.id === id) || null
}

/** Kahn 拓扑排序；有环时返回 null。 */
export function topologicalOrder(graph) {
  const ids = (graph?.nodes || []).map(node => node.id)
  const inDegree = new Map(ids.map(id => [id, 0]))
  const next = new Map(ids.map(id => [id, []]))
  for (const edge of graph?.edges || []) {
    if (!inDegree.has(edge.from) || !inDegree.has(edge.to) || edge.from === edge.to) continue
    inDegree.set(edge.to, inDegree.get(edge.to) + 1)
    next.get(edge.from).push(edge.to)
  }
  const ready = ids.filter(id => inDegree.get(id) === 0)
  const order = []
  while (ready.length) {
    const id = ready.shift()
    order.push(id)
    for (const to of next.get(id)) {
      const degree = inDegree.get(to) - 1
      inDegree.set(to, degree)
      if (degree === 0) ready.push(to)
    }
  }
  return order.length === ids.length ? order : null
}

export function wouldCycle(graph, from, to) {
  if (!from || !to || from === to) return true
  const edges = [...(graph?.edges || []), { from, to }]
  return topologicalOrder({ nodes: graph?.nodes || [], edges }) === null
}

export function hasEdge(graph, from, to) {
  return (graph?.edges || []).some(edge => edge.from === from && edge.to === to)
}

export function upstreamOf(graph, id) {
  const edge = (graph?.edges || []).find(item => item.to === id)
  return edge ? nodeById(graph, edge.from) : null
}

export function descendantsOf(graph, id) {
  const result = new Set()
  const walk = current => {
    for (const edge of graph?.edges || []) {
      if (edge.from === current && !result.has(edge.to)) {
        result.add(edge.to)
        walk(edge.to)
      }
    }
  }
  walk(id)
  return result
}

export function bounds(nodes) {
  if (!nodes?.length) return { minX: 0, minY: 0, maxX: NODE_W, maxY: NODE_H, width: NODE_W, height: NODE_H }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const node of nodes) {
    minX = Math.min(minX, node.x)
    minY = Math.min(minY, node.y)
    maxX = Math.max(maxX, node.x + NODE_W)
    maxY = Math.max(maxY, node.y + NODE_H)
  }
  return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY }
}

/** 让整张图落进视口，四周留白。 */
export function fitView(nodes, viewport, padding = 56) {
  const box = bounds(nodes)
  const vw = Math.max(1, viewport.width)
  const vh = Math.max(1, viewport.height)
  const zoom = Math.min(1.4, Math.max(0.4, Math.min((vw - padding * 2) / box.width, (vh - padding * 2) / box.height)))
  return {
    zoom,
    panX: (vw - box.width * zoom) / 2 - box.minX * zoom,
    panY: (vh - box.height * zoom) / 2 - box.minY * zoom
  }
}

/** 世界坐标 → 屏幕坐标（用于把指针位置换算成落点）。 */
export function toWorld(clientX, clientY, rect, pan, zoom) {
  return {
    x: (clientX - rect.left - pan.x) / zoom,
    y: (clientY - rect.top - pan.y) / zoom
  }
}

export function nextNodeId(nodes) {
  let index = 1
  const used = new Set((nodes || []).map(node => node.id))
  while (used.has(`n${index}`)) index++
  return `n${index}`
}
