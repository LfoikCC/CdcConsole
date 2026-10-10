import { describe,it,expect,vi,beforeEach } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter,createMemoryHistory } from 'vue-router'
import TasksView from './TasksView.vue'
import { components } from '../components/ui'
import { api } from '../api'
vi.mock('../api',()=>({api:vi.fn()}))
vi.mock('../components/ui/feedback',()=>({CdcMessage:{error:vi.fn(),warning:vi.fn(),success:vi.fn()},CdcMessageBox:{confirm:vi.fn()}}))
beforeEach(()=>{api.mockReset();api.mockImplementation(async path=>path==='/tasks/summary'?{total:35,running:8,degraded:2,openAlerts:4}:path.startsWith('/tasks?')?{items:[],total:0}:[])})
describe('task summary quick filters',()=>{
  it('persists filters and page, retains global counts, clears quick filters for manual status',async()=>{
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/tasks',component:TasksView}]})
    await router.push('/tasks?q=orders&sourceType=MYSQL&page=2');await router.isReady()
    const w=mount(TasksView,{global:{plugins:[createPinia(),router],components,stubs:{Teleport:true}}});await flushPromises()
    await w.findAll('.task-filter')[3].trigger('click');await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({view:'open-alerts',q:'orders',sourceType:'MYSQL'})
    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(api.mock.calls.some(([path])=>path.includes('view=open-alerts')&&path.includes('page=0'))).toBe(true)
    expect(w.findAll('.task-filter strong').map(e=>e.text())).toEqual(['35','8','2','4'])
    expect(w.text()).toContain('当前筛选下没有任务')
    await w.findAll('.select-trigger')[0].trigger('click')
    await w.findAll('[role=option]').find(e=>e.text()==='已暂停').trigger('click');await flushPromises()
    expect(router.currentRoute.value.query.status).toBe('PAUSED')
    expect(router.currentRoute.value.query.view).toBeUndefined()
    window.dispatchEvent(new Event('cdc-runtime-change'));await flushPromises()
    expect(router.currentRoute.value.query.status).toBe('PAUSED');w.unmount()
  })
})
