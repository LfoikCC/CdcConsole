// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AlertsView from './AlertsView.vue'
import { api } from '../api'
import { CdcTable, CdcTableColumn, CdcButton, CdcTag } from '../components/ui'

vi.mock('../api', () => ({ api: vi.fn() }))
vi.mock('../components/ui/feedback', () => ({ CdcMessage: { success: vi.fn(), error: vi.fn() } }))
const rows = [
  { id:1, taskId:1, status:'OPEN', title:'待处理事件' },
  { id:2, taskId:1, status:'ACKNOWLEDGED', title:'已确认事件' },
  { id:3, taskId:1, status:'RESOLVED', title:'已解决事件' }
]
function render() { return mount(AlertsView, { global: { plugins:[createPinia()], components:{CdcTable,CdcTableColumn,CdcButton,CdcTag}, stubs:{RouterLink:true}, directives:{loading:()=>{}} } }) }
beforeEach(() => { api.mockReset(); api.mockImplementation(async path => path === '/alerts' ? structuredClone(rows) : []) })
describe('alert summary filters', () => {
  it('defaults to all and keeps global counts while switching filters locally', async () => {
    const wrapper=render(); await flushPromises()
    const buttons=wrapper.findAll('.alert-filter')
    expect(buttons.map(button=>button.text())).toEqual(['全部3','待处理1','已确认1','已解决1'])
    expect(buttons[0].attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    for(let i=1;i<4;i++) {
      await buttons[i].trigger('click')
      expect(wrapper.findAll('tbody tr')).toHaveLength(1)
      expect(buttons.map(button=>button.find('strong').text())).toEqual(['3','1','1','1'])
    }
    expect(api.mock.calls.filter(([path])=>path==='/alerts')).toHaveLength(1)
    expect(wrapper.find('.toolbar').exists()).toBe(false)
    wrapper.unmount()
  })
  it('retains selection on runtime refresh and preserves rows on request failure', async () => {
    const wrapper=render(); await flushPromises()
    await wrapper.findAll('.alert-filter')[1].trigger('click')
    api.mockImplementation(async path => { if(path==='/alerts') throw new Error('offline'); return [] })
    window.dispatchEvent(new Event('cdc-runtime-change')); await flushPromises()
    expect(wrapper.findAll('.alert-filter')[1].attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('待处理事件')
    wrapper.unmount()
  })
  it('updates counts after acknowledgement while keeping the pending filter', async () => {
    const wrapper=render(); await flushPromises()
    await wrapper.findAll('.alert-filter')[1].trigger('click')
    api.mockImplementation(async path => path==='/alerts'?rows.map(row=>({...row,status:row.id===1?'ACKNOWLEDGED':row.status})):[])
    await wrapper.findAll('tbody button')[0].trigger('click'); await flushPromises()
    expect(api).toHaveBeenCalledWith('/alerts/1/acknowledge',{method:'POST'})
    expect(wrapper.findAll('.alert-filter').map(button=>button.find('strong').text())).toEqual(['3','0','2','1'])
    expect(wrapper.text()).toContain('当前筛选下没有告警')
    wrapper.unmount()
  })
})
