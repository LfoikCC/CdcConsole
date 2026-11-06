import { describe, expect, it } from 'vitest'
import { config, flushPromises, mount } from '@vue/test-utils'
import { components } from '../ui'
import BiDataTable from './BiDataTable.vue'

// 分页条里的「每页行数」用的是站内自绘下拉，组件是全局注册的；
// teleport 打桩，弹层才会渲染在组件内部，测试才能点到选项
config.global.components = components
config.global.stubs = { teleport: true }

const rowsOf = count => Array.from({ length: count }, (_, index) => [`行${index}`, index])

describe('BiDataTable', () => {
  it('renders every row when the data is small', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(20) } } })

    expect(wrapper.findAll('tr.bi-row')).toHaveLength(20)
    // 数据不多时不插占位行，DOM 干净
    expect(wrapper.find('.bi-row-pad').exists()).toBe(false)
  })

  it('only renders one chunk of a huge result', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(5000) } } })

    // 五千行先挂前 100 行，剩下的靠占位行撑滚动条
    const rendered = wrapper.findAll('tr.bi-row').length
    expect(rendered).toBe(100)
    expect(wrapper.find('.bi-row-pad').exists()).toBe(true)
    expect(wrapper.text()).toContain('行0')
    expect(wrapper.text()).not.toContain('行150')
  })

  it('moves to the next chunk when scrolled down', async () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(5000) } } })
    const wrap = wrapper.find('.bi-table-wrap')
    // 行高 30px：滚到第 101 行附近就该换第二批
    wrap.element.scrollTop = 30 * 150
    await wrap.trigger('scroll')
    await flushPromises()

    expect(wrapper.text()).toContain('行100')
    expect(wrapper.text()).not.toContain('行0 ')
    expect(wrapper.findAll('tr.bi-row').length).toBeLessThanOrEqual(100)
  })

  it('keeps the header and shows a dash for empty cells', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称'], rows: [[null], ['']] } } })

    expect(wrapper.findAll('th').map(item => item.text())).toEqual(['名称'])
    expect(wrapper.findAll('td').map(item => item.text())).toEqual(['—', '—'])
  })

  it('pages the rows when pagination is on', async () => {
    const wrapper = mount(BiDataTable, {
      props: {
        data: { columns: ['名称', '值'], rows: rowsOf(320) },
        options: { pagination: true, pageSize: 50 }
      }
    })

    expect(wrapper.findAll('tr.bi-row')).toHaveLength(50)
    expect(wrapper.find('.bi-pager').text()).toContain('共 320 条')
    expect(wrapper.text()).toContain('行0')

    await wrapper.find('.bi-pager [aria-label="下一页"]').trigger('click')
    expect(wrapper.find('.bi-pager .bi-pager-num.is-active').text()).toBe('2')
    expect(wrapper.text()).toContain('行50')
    expect(wrapper.text()).not.toContain('行0 ')
  })

  it('changes the page size and jumps to a page from the pager', async () => {
    const wrapper = mount(BiDataTable, {
      props: {
        data: { columns: ['名称', '值'], rows: rowsOf(320) },
        options: { pagination: true, pageSize: 50 }
      }
    })

    // 打开下拉，选「100 条/页」
    await wrapper.find('.bi-pager-size .select-trigger').trigger('click')
    await flushPromises()
    const option = wrapper.findAll('.select-popover [role="option"]')
      .find(item => item.text().includes('100'))
    await option.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('tr.bi-row')).toHaveLength(100)
    expect(wrapper.emitted('page-size')[0]).toEqual([100])

    await wrapper.find('.bi-pager-jump-input').setValue('3')
    await wrapper.find('.bi-pager-jump-input').trigger('keyup.enter')
    expect(wrapper.find('.bi-pager .bi-pager-num.is-active').text()).toBe('3')
    expect(wrapper.text()).toContain('行200')
  })

  it('collapses long page lists with an ellipsis', async () => {
    const wrapper = mount(BiDataTable, {
      props: {
        data: { columns: ['名称'], rows: rowsOf(2000) },
        options: { pagination: true, pageSize: 50 }
      }
    })

    // 40 页只显示首尾和当前页附近，中间用省略号
    expect(wrapper.find('.bi-pager').text()).toContain('…')
    expect(wrapper.findAll('.bi-pager-num').length).toBeLessThan(8)
  })

  it('numbers rows from the first row of the page', async () => {
    const wrapper = mount(BiDataTable, {
      props: {
        data: { columns: ['名称'], rows: rowsOf(120) },
        options: { pagination: true, pageSize: 50, showRowIndex: true }
      }
    })

    expect(wrapper.findAll('th')[0].text()).toBe('#')
    expect(wrapper.findAll('tr.bi-row')[0].findAll('td')[0].text()).toBe('1')

    await wrapper.find('.bi-pager [aria-label="下一页"]').trigger('click')
    expect(wrapper.findAll('tr.bi-row')[0].findAll('td')[0].text()).toBe('51')
  })

  it('formats metric columns that have a data format', () => {
    const wrapper = mount(BiDataTable, {
      props: {
        data: { columns: ['状态', '金额'], rows: [['已完成', 1234.5]] },
        options: {
          dimensions: [{ column: 'status' }],
          metrics: [{ column: 'amount', agg: 'SUM', format: { type: 'number', prefix: '¥', precision: 1 } }]
        }
      }
    })

    const cells = wrapper.findAll('tr.bi-row td').map(td => td.text())
    expect(cells[0]).toBe('已完成')
    expect(cells[1]).toBe('¥1,234.5')
  })

  it('right-aligns numeric columns and stripes alternate rows', () => {
    const wrapper = mount(BiDataTable, { props: { data: { columns: ['名称', '值'], rows: rowsOf(4) } } })

    const headers = wrapper.findAll('th')
    expect(headers[0].classes()).not.toContain('is-num')
    expect(headers[1].classes()).toContain('is-num')

    const rows = wrapper.findAll('tr.bi-row')
    expect(rows[0].findAll('td')[1].classes()).toContain('is-num')
    expect(rows[0].findAll('td')[0].classes()).not.toContain('is-num')
    // 隔行底色按数据行序号算，第一行不能带
    expect(rows[0].classes()).not.toContain('is-alt')
    expect(rows[1].classes()).toContain('is-alt')
  })
})
