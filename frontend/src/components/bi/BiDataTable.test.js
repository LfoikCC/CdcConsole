import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BiDataTable from './BiDataTable.vue'

const rowsOf = count => Array.from({ length: count }, (_, index) => [`行${index}`, index])

describe('BiDataTable', () => {
  it('renders every row when the data is small', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(20) } } })

    expect(wrapper.findAll('tr.bi-row')).toHaveLength(20)
    // 数据不多时不插占位行，DOM 干净
    expect(wrapper.find('.bi-row-pad').exists()).toBe(false)
  })

  it('only renders a window of a huge result', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(5000) } } })

    // 五千行只渲染视口附近的一小段，DOM 不会爆
    const rendered = wrapper.findAll('tr.bi-row').length
    expect(rendered).toBeGreaterThan(0)
    expect(rendered).toBeLessThan(60)
    expect(wrapper.find('.bi-row-pad').exists()).toBe(true)
    expect(wrapper.text()).toContain('行0')
  })

  it('keeps the header and shows a dash for empty cells', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称'], rows: [[null], ['']] } } })

    expect(wrapper.findAll('th').map(item => item.text())).toEqual(['名称'])
    expect(wrapper.findAll('td').map(item => item.text())).toEqual(['—', '—'])
  })
})
