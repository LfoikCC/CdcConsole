import { describe,it,expect,vi,beforeEach,afterEach } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MonitorView from './MonitorView.vue'
import { api } from '../api'
vi.mock('../api',()=>({api:vi.fn()}))
const snapshot={summary:{total:1,collecting:1,writeRate:12,maxLagMs:100,hourRows:300,openAlerts:0},items:[{id:1,name:'真实任务',status:'RUNNING',source:'源',target:'目标 A、目标 B',writeRate:12,lagMs:100,hourRows:300,trend:[],alertLagSeconds:60}],total:1,page:0,events:[],updatedAt:'2026-10-10T10:00:00+08:00',sampleMs:60000}
function render(){return mount(MonitorView,{global:{plugins:[createPinia()],stubs:{RouterLink:{template:'<a><slot/></a>'}}}})}
beforeEach(()=>{vi.useFakeTimers();api.mockReset();api.mockResolvedValue(structuredClone(snapshot))})
afterEach(()=>{vi.useRealTimers()})
describe('task monitor',()=>{
  it('coalesces event bursts, throttles to five seconds and uses a minute fallback',async()=>{
    const w=render();await flushPromises();expect(api).toHaveBeenCalledTimes(1)
    for(let i=0;i<100;i++)window.dispatchEvent(new Event('cdc-monitor-change'))
    await vi.advanceTimersByTimeAsync(499);expect(api).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1);await flushPromises();expect(api).toHaveBeenCalledTimes(2)
    for(let i=0;i<100;i++)window.dispatchEvent(new Event('cdc-monitor-change'))
    await vi.advanceTimersByTimeAsync(4999);expect(api).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1);await flushPromises();expect(api).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(55000);await flushPromises();expect(api).toHaveBeenCalledTimes(4)
    w.unmount();expect(vi.getTimerCount()).toBe(0)
  })
  it('ignores an in-flight response after pausing refresh',async()=>{
    let finish;api.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    const w=render();await flushPromises()
    await w.find('button.live-chip').trigger('click')
    finish(structuredClone(snapshot));await flushPromises()
    expect(w.text()).not.toContain('真实任务')
    await w.find('button.live-chip').trigger('click');await flushPromises()
    expect(w.text()).toContain('真实任务');w.unmount()
  })
  it('retains one pending refresh during a request and cancels it on pause',async()=>{
    const w=render();await flushPromises()
    let finish;api.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    window.dispatchEvent(new Event('cdc-monitor-change'));await vi.advanceTimersByTimeAsync(500)
    for(let i=0;i<50;i++)window.dispatchEvent(new Event('cdc-monitor-change'))
    await vi.advanceTimersByTimeAsync(5000);expect(api).toHaveBeenCalledTimes(2)
    await w.find('button.live-chip').trigger('click');finish(structuredClone(snapshot));await flushPromises()
    await vi.advanceTimersByTimeAsync(10000);expect(api).toHaveBeenCalledTimes(2)
    w.unmount();expect(vi.getTimerCount()).toBe(0)
  })
  it('freezes automatic refresh while hidden and immediately refreshes on return',async()=>{
    const hidden=vi.spyOn(document,'hidden','get').mockReturnValue(false)
    const w=render();await flushPromises()
    hidden.mockReturnValue(true);document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('cdc-monitor-change'));await vi.advanceTimersByTimeAsync(120000)
    expect(api).toHaveBeenCalledTimes(1)
    hidden.mockReturnValue(false);document.dispatchEvent(new Event('visibilitychange'));await flushPromises()
    expect(api).toHaveBeenCalledTimes(2);w.unmount();hidden.mockRestore()
  })
  it('pauses only monitor refresh, resumes and removes timers on unmount',async()=>{
    const w=render();await flushPromises();expect(w.text()).toContain('真实任务')
    await w.find('button.live-chip').trigger('click');const calls=api.mock.calls.length
    window.dispatchEvent(new Event('cdc-runtime-change'));await vi.advanceTimersByTimeAsync(30000)
    expect(api).toHaveBeenCalledTimes(calls)
    await w.find('button.live-chip').trigger('click');await flushPromises();expect(api.mock.calls.length).toBe(calls+1)
    w.unmount();expect(vi.getTimerCount()).toBe(0)
  })
  it('retains successful data on failure and requests chosen range and task filter',async()=>{
    const w=render();await flushPromises();api.mockRejectedValue(new Error('网络错误'))
    await w.findAll('.range-tabs button')[0].trigger('click');await flushPromises()
    expect(api).toHaveBeenLastCalledWith(expect.stringContaining('range=5m'))
    expect(w.text()).toContain('网络错误');expect(w.text()).toContain('真实任务')
    await w.find('input[type=checkbox]').setValue(true);await flushPromises()
    expect(api).toHaveBeenLastCalledWith(expect.stringContaining('onlyRunning=true'));w.unmount()
  })
})
