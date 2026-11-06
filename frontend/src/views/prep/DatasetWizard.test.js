import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { components } from '../../components/ui'

vi.mock('../../api', () => ({ api: vi.fn(), upload: vi.fn() }))

import { api, upload } from '../../api'
import DatasetWizard from './DatasetWizard.vue'

const parsed = {
  token: '11111111-1111-1111-1111-111111111111',
  fileName: '发货计划.xlsx',
  sizeBytes: 2048,
  sheets: [{ index: 0, name: 'Sheet1', rowCount: 3, headerRow: 1,
    columns: [{ name: '客户编码', type: 'VARCHAR' }], sampleRows: [] }]
}

const inspected = {
  sheetIndex: 0, headerRow: 1, startColumn: 0, endColumn: 3, rowCount: 3,
  columns: [
    { name: '客户编码', type: 'VARCHAR', originalName: '客户编码' },
    { name: '客户名称', type: 'VARCHAR', originalName: '客户名称' },
    { name: '金额', type: 'DECIMAL(38,10)', originalName: '金额' }
  ],
  sampleRows: [['1000', '甲公司', '120'], ['1001', '乙公司', '80']]
}

let wrapper

async function mountWizard() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/prep/datasets/new', component: DatasetWizard },
    { path: '/prep/datasets', component: { template: '<div/>' } }
  ] })
  router.push('/prep/datasets/new')
  await router.isReady()
  wrapper = mount(DatasetWizard, { global: { components, plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  api.mockReset()
  upload.mockReset()
  api.mockImplementation(async path => {
    if (path === '/accounts?size=500') return { items: [] }
    if (path === '/datasets/excel/inspect') return inspected
    if (path === '/datasets/excel') return { id: 42 }
    return {}
  })
  upload.mockResolvedValue(parsed)
})

describe('DatasetWizard 本地文件', () => {
  it('names the file source 本地文件', async () => {
    await mountWizard()
    const sources = wrapper.findAll('.source-card').map(item => item.text())
    expect(sources.some(text => text.includes('本地文件'))).toBe(true)
    expect(sources.some(text => text.includes('Excel 文件'))).toBe(false)
  })

  it('configures fields in a grid with editable names, types and preview rows', async () => {
    await mountWizard()
    await wrapper.findAll('.source-card').find(item => item.text().includes('本地文件')).trigger('click')
    await wrapper.findAll('.modal-foot button, .wizard-footer button').find(item => item.text().includes('下一步')).trigger('click')
    await flushPromises()

    const input = wrapper.find('input[type=file]')
    const file = new File(['x'], '发货计划.xlsx', { type: 'application/vnd.ms-excel' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    // 表头行、列范围、去重主键都在同一屏
    expect(wrapper.text()).toContain('设置表头')
    expect(wrapper.text()).toContain('选取列数据')
    expect(wrapper.text()).toContain('去重主键')
    expect(api.mock.calls.some(([path]) => path === '/datasets/excel/inspect')).toBe(true)

    // 列名直接输入，类型跟着列走，下面就是数据预览
    const names = wrapper.findAll('.field-name').map(item => item.element.value)
    expect(names).toEqual(['客户编码', '客户名称', '金额'])
    expect(wrapper.findAll('.field-type')).toHaveLength(3)
    // 类型列只显示数据库类型本身，不带「文本 / 小数」这类中文前缀
    expect(wrapper.findAll('.field-type .select-trigger').map(item => item.text()))
      .toEqual(['VARCHAR', 'VARCHAR', 'DECIMAL(38,10)'])
    expect(wrapper.findAll('.field-grid tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('甲公司')
    // 列宽固定 150，表格按列数撑开：不然列一多就会被压成一条条细缝
    expect(wrapper.find('.field-grid').attributes('style')).toContain('width: 492px')

    // 改别名后进下一步，提交的是别名 + 原始字段名
    await wrapper.findAll('.field-name')[2].setValue('开票金额')
    await wrapper.findAll('.wizard-footer button').find(item => item.text().includes('下一步')).trigger('click')
    await flushPromises()
    await wrapper.find('.wizard-section input').setValue('发货计划')
    await wrapper.findAll('.wizard-footer button').find(item => item.text().includes('创建数据集')).trigger('click')
    await flushPromises()

    const create = api.mock.calls.find(([path]) => path === '/datasets/excel')
    const body = JSON.parse(create[1].body)
    expect(body.headerRow).toBe(1)
    expect(body.columns[2]).toEqual({ name: '开票金额', type: 'DECIMAL(38,10)', originalName: '金额' })
  })
})
