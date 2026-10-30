import { describe,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import RecoveryDialog from './RecoveryDialog.vue'
import { components } from './ui'
import { api } from '../api'
vi.mock('../api',()=>({api:vi.fn()}))
vi.mock('./ui/feedback',()=>({CdcMessage:{success:vi.fn()}}))
describe('target table recovery confirmation',()=>{
  it('prechecks only the selected target and requires Kafka duplicate acknowledgement',async()=>{
    api.mockImplementation(async p=>p.endsWith('/precheck')?{planId:'plan-1',kafka:true,sourceTable:'orders',targetTable:'orders-topic',events:2,bytes:200,warning:'重复消息风险'}:p.endsWith('/checkpoints')?{tables:[]}:null)
    const task={id:7,timeZone:'Asia/Shanghai',targetSubscriptions:[{name:'A',tables:[{id:11,sourceTaskTableId:21,mapping:{sourceTable:'orders',targetTable:'orders-topic'}}]},{name:'B',tables:[{id:12,sourceTaskTableId:22,mapping:{sourceTable:'other',targetTable:'other-topic'}}]}]}
    const w=mount(RecoveryDialog,{props:{modelValue:false,task,sourceTableId:21},global:{components,stubs:{Teleport:true}}})
    await w.setProps({modelValue:true});await flushPromises()
    await w.findAll('button').find(b=>b.text()==='执行预检').trigger('click');await flushPromises()
    const call=api.mock.calls.find(([p])=>p.endsWith('/precheck'))
    expect(JSON.parse(call[1].body)).toMatchObject({subscriptionTableId:11,mode:'LAST_SUCCESS'})
    const submit=w.findAll('button').find(b=>b.text()==='确认提交恢复');expect(submit.attributes('disabled')).toBeDefined()
    await w.find('input[type=checkbox]').setValue(true);await flushPromises();expect(w.find('input[type=checkbox]').element.checked).toBe(true);await w.findAll('button').find(b=>b.text()==='确认提交恢复').trigger('click');await flushPromises()
    expect(api.mock.calls.find(([p])=>p==='/tasks/7/recoveries')[1].body).toBe(JSON.stringify({planId:'plan-1',confirmKafkaDuplicates:true}))
    expect(w.emitted('submitted')).toHaveLength(1);w.unmount()
  })
})
