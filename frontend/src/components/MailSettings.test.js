// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MailSettings from './MailSettings.vue'
import { components } from './ui'
import { api } from '../api'
vi.mock('../api',()=>({api:vi.fn()}))
vi.mock('./ui/feedback',()=>({CdcMessage:{success:vi.fn(),error:vi.fn()}}))
describe('SMTP settings',()=>{
  it('fills default ports, permits overrides and tests the current draft',async()=>{
    api.mockResolvedValue({secure:'starttls',port:587,host:'smtp.example.com'})
    const wrapper=mount(MailSettings,{global:{components}})
    await flushPromises()
    const port=wrapper.find('input[type=number]')
    await wrapper.find('select').setValue('ssl')
    expect(port.element.value).toBe('465')
    await port.setValue(2465)
    expect(port.element.value).toBe('2465')
    await wrapper.findAll('button').find(b=>b.text().includes('测试连接')).trigger('click')
    await flushPromises()
    expect(api).toHaveBeenLastCalledWith('/mail/test-connection',expect.objectContaining({body:expect.any(String)}))
    const request=JSON.parse(api.mock.calls.at(-1)[1].body)
    expect(request).toMatchObject({port:2465,secure:'ssl'})
    wrapper.unmount()
  })
})
