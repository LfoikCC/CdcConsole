import { describe,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter,createMemoryHistory } from 'vue-router'
import { permit,identity } from '../auth'
import TaskDetailView from './TaskDetailView.vue'
import { components } from '../components/ui'
import { api } from '../api'
import { CdcMessageBox } from '../components/ui/feedback'
vi.mock('../api',()=>({api:vi.fn()}))
vi.mock('../components/ui/feedback',()=>({CdcMessage:{error:vi.fn(),success:vi.fn()},CdcMessageBox:{confirm:vi.fn()}}))

describe('subscription lifecycle presentation',()=>{
  it('archives cancelled subscriptions despite stale active tables and confirms explicit reactivation',async()=>{
    identity.user={role:'ADMIN'}
    const mapping={sourceDatabase:'db',sourceTable:'orders',targetTable:'sr_orders',reconciliation:{enabled:true}}
    const binding={id:10,status:'ACTIVE',initializationCompletedAt:'2026-10-09T12:00:00+08:00',mapping}
    const task={id:7,name:'task',status:'RUNNING',tables:[],targetSubscriptions:[
      {id:1,name:'有效订阅',status:'ACTIVE',tables:[binding]},
      {id:2,name:'已取消目标',status:'CANCELLED',pendingEvents:37,tables:[binding]}
    ]}
    api.mockImplementation(async path=>path==='/tasks/7'?{...task}:path.includes('/logs?')||path.includes('/delivery-batches?')?{items:[],total:0}:[])
    CdcMessageBox.confirm.mockResolvedValue()
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/tasks/:id',component:TaskDetailView},{path:'/tasks',component:{template:'<div />'}}]})
    await router.push('/tasks/7?tab=subscriptions');await router.isReady()
    const w=mount(TaskDetailView,{global:{plugins:[createPinia(),router],components,directives:{permit,loading:()=>{}},stubs:{SubscriptionWizard:true,ReconciliationConfigDialog:true,RecoveryDialog:true,CheckpointPanel:true}}})
    await flushPromises()
    expect(w.findAll('.subscription-card').filter(card=>!card.element.closest('details')).map(card=>card.text())).toHaveLength(1)
    const history=w.find('details.subscription-history')
    expect(history.attributes('open')).toBeUndefined()
    expect(history.text()).toContain('历史待处理事件（不再投递）')
    expect(history.text()).not.toContain('运行中')
    expect(history.findAll('button').map(b=>b.text())).toEqual(['重新启用'])
    expect(w.findAll('th').map(h=>h.text())).toContain('投递状态')
    expect(w.findAll('th').map(h=>h.text())).toContain('初始化进度')
    history.element.open=true
    await history.find('button').trigger('click');await flushPromises()
    expect(CdcMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('不清空目标表'),'重新启用订阅',expect.anything())
    expect(api).toHaveBeenCalledWith('/tasks/7/subscriptions/2/actions/resume',{method:'POST'})
    task.status='STOPPED';window.dispatchEvent(new Event('cdc-runtime-change'));await flushPromises()
    expect(history.find('button').attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
