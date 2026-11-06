import { beforeEach, describe, expect, it, vi } from 'vitest'

const init = vi.hoisted(() => vi.fn(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
  isDisposed: () => false
})))

vi.mock('echarts', () => ({ init }))

import { chartStyle, createChart, invalidateChartStyle } from './chartStyle'

beforeEach(() => {
  // jsdom 没有 ResizeObserver，createChart 会用到
  globalThis.ResizeObserver = class {
    observe() {}
    disconnect() {}
  }
})

describe('chartStyle', () => {
  it('animates charts instead of snapping them in', () => {
    const style = chartStyle()
    expect(style.animation).toBe(true)
    expect(style.animationDuration).toBeGreaterThan(0)
    // 数据刷新走 update 动画，柱线平滑过渡而不是瞬间替换
    expect(style.animationDurationUpdate).toBeGreaterThan(0)
  })

  it('renders on svg by default and can still ask for canvas', () => {
    const el = document.createElement('div')
    // SVG 的文字是矢量，高倍屏/缩放下不会发虚；Canvas 只作大数据量的兜底
    createChart(el)
    expect(init).toHaveBeenLastCalledWith(el, null, expect.objectContaining({ renderer: 'svg' }))
    createChart(el, 'canvas')
    expect(init).toHaveBeenLastCalledWith(el, null, expect.objectContaining({ renderer: 'canvas' }))
  })

  it('reads theme variables once per theme', () => {
    const spy = vi.spyOn(window, 'getComputedStyle')
    document.documentElement.dataset.theme = 'light'
    invalidateChartStyle()
    const before = spy.mock.calls.length

    chartStyle()
    chartStyle()
    // 一次读取 = html + body 各一次，第二次直接用缓存
    expect(spy.mock.calls.length - before).toBe(2)

    const cached = spy.mock.calls.length
    document.documentElement.dataset.theme = 'dark'
    chartStyle()
    expect(spy.mock.calls.length).toBeGreaterThan(cached)
    spy.mockRestore()
  })
})
