// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MailSubscriptions from './MailSubscriptions.vue'
import { components } from './ui'
import { api } from '../api'
vi.mock('../api',()=>({api:vi.fn(),getAll:vi.fn(async()=>[{id:1,name:'订单任务'}])}))
vi.mock('./ui/feedback',()=>({CdcMessage:{success:vi.fn(),error:vi.fn()},CdcMessageBox:{confirm:vi.fn()}}))
describe('mail subscription rules',()=>{
  it('edits fetched reactive rules and persists recipients and task scope',async()=>{
    api.mockImplementation(async path=>path==='/mail/rules'?[{id:1,name:'运维',description:'',enabled:true,recipients:['ops@example.com'],alertTypes:['TABLE_FAILURE'],scope:'tasks',taskIds:[1],frequency:'5min'}]:{})
    const wrapper=mount(MailSubscriptions,{global:{components,stubs:{Teleport:true}}})
    await flushPromises()
    await wrapper.findAll('button').find(button=>button.text()==='编辑').trigger('click')
    expect(wrapper.find('[role=dialog]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="多个邮箱用逗号或空格分隔"]').element.value).toBe('ops@example.com')
    await wrapper.findAll('button').find(button=>button.text()==='保存规则').trigger('click');await flushPromises()
    expect(api).toHaveBeenCalledWith('/mail/rules/1',expect.objectContaining({method:'PUT'}))
    wrapper.unmount()
  })
})
