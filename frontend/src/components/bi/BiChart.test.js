import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  setOption: vi.fn(),
  dispose: vi.fn(),
  resize: vi.fn(),
  createChart: vi.fn()
}))

vi.mock('../../chartStyle', () => ({
  createChart: mocks.createChart
}))

vi.mock('./biChartOption', () => ({
  chartOptionFor: () => ({ series: [{ type: 'bar', data: [1] }] })
}))

import BiChart from './BiChart.vue'

// jsdom 老版本没有 rAF，补一个；有就用原生的
globalThis.requestAnimationFrame ||= callback => setTimeout(() => callback(Date.now()), 16)
globalThis.cancelAnimationFrame ||= handle => clearTimeout(handle)

const card = (type = 'bar', metrics = [{ column: 'amount', agg: 'SUM' }]) => ({
  type,
  title: '图',
  datasetId: 7,
  config: { dimensions: [{ column: 'status' }], metrics, filters: [], limit: 100 }
})
const rows = count => Array.from({ length: count }, (_, index) => [`第${index}行`, index])
const payload = count => ({ columns: ['状态', '金额'], rows: rows(count) })
const nextFrame = () => new Promise(resolve => requestAnimationFrame(() => resolve()))

beforeEach(() => {
  mocks.createChart.mockImplementation(() => ({
    setOption: mocks.setOption,
    dispose: mocks.dispose,
    resize: mocks.resize
  }))
})

afterEach(() => {
  mocks.setOption.mockClear()
  mocks.dispose.mockClear()
  mocks.resize.mockClear()
  mocks.createChart.mockClear()
})

describe('BiChart', () => {
  it('updates the existing chart instead of rebuilding it', async () => {
    mount(BiChart, { props: { card: card(), data: payload(3) } })
    await flushPromises()

    expect(mocks.setOption).toHaveBeenCalledTimes(1)
    // 增量合并：不再用 notMerge 把整张图丢掉重播入场动画
    expect(mocks.setOption.mock.calls[0][1]).toEqual({
      notMerge: false,
      replaceMerge: ['series'],
      lazyUpdate: true
    })
  })

  it('coalesces rapid updates into a single redraw', async () => {
    const wrapper = mount(BiChart, { props: { card: card(), data: payload(3) } })
    await flushPromises()
    mocks.setOption.mockClear()

    await wrapper.setProps({ data: payload(4) })
    await wrapper.setProps({ data: payload(5) })
    await nextFrame()

    expect(mocks.setOption).toHaveBeenCalledTimes(1)
  })

  it('rebuilds in place when the chart type changes', async () => {
    const wrapper = mount(BiChart, { props: { card: card(), data: payload(3) } })
    await flushPromises()
    mocks.setOption.mockClear()

    // 换图型复用同一个 canvas：不销毁重建，也就不会出现「旧图没了、新图还没画」的空白
    await wrapper.setProps({
      card: {
        ...card('line', [{ column: 'amount', agg: 'SUM' }, { column: 'id', agg: 'COUNT' }])
      }
    })
    await nextFrame()

    expect(mocks.createChart).toHaveBeenCalledTimes(1)
    expect(mocks.dispose).not.toHaveBeenCalled()
    expect(mocks.setOption).toHaveBeenCalledTimes(1)
    expect(mocks.setOption.mock.calls[0][1]).toEqual({ notMerge: true })
  })

  it('turns off the entrance animation for huge results', async () => {
    mount(BiChart, { props: { card: card(), data: payload(900) } })
    await flushPromises()

    const option = mocks.setOption.mock.calls.at(-1)[0]
    expect(option.animation).toBe(false)
  })

  it('disposes the chart on unmount', async () => {
    const wrapper = mount(BiChart, { props: { card: card(), data: payload(3) } })
    await flushPromises()
    wrapper.unmount()

    expect(mocks.dispose).toHaveBeenCalled()
  })
})
