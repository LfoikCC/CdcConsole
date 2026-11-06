import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BiCardView from './BiCardView.vue'

const chartStub = { name: 'BiChart', template: '<div class="stub-chart"/>' }
const data = { columns: ['状态', '金额'], rows: [['已完成', 10]] }
const card = (type, config) => ({ type, title: '卡片', datasetId: 7, config })

function mountView(props) {
  return mount(BiCardView, {
    props,
    global: { stubs: { BiChart: chartStub, BiDataTable: true, BiKpi: true, BiTextCard: true } }
  })
}

describe('BiCardView 刷新与切换', () => {
  it('keeps the chart mounted while refreshing', () => {
    // 刷新时不能把图换成「加载中…」，否则整块会闪一下、图也要重画
    const wrapper = mountView({
      card: card('bar', { dimensions: [{ column: '状态' }], metrics: [{ column: '金额', agg: 'SUM' }] }),
      data,
      loading: true
    })

    expect(wrapper.find('.stub-chart').exists()).toBe(true)
    expect(wrapper.find('.bi-card-loading').exists()).toBe(false)
  })

  it('shows the placeholder only before the first payload', () => {
    const wrapper = mountView({
      card: card('bar', { dimensions: [{ column: '状态' }], metrics: [{ column: '金额', agg: 'SUM' }] }),
      data: null,
      loading: true
    })

    expect(wrapper.find('.bi-card-loading').exists()).toBe(true)
    expect(wrapper.find('.stub-chart').exists()).toBe(false)
  })

  it('explains missing fields instead of drawing', () => {
    const wrapper = mountView({
      card: card('bar', { dimensions: [], metrics: [] }),
      data: null,
      loading: false
    })

    expect(wrapper.find('.bi-card-hint').text()).toContain('建议 1 个维度，1 个数值')
    expect(wrapper.find('.stub-chart').exists()).toBe(false)
  })
})
