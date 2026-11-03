import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, resetCsrf } from './api'

/** 写操作要通知外壳刷新侧栏计数，否则列表删空了、侧栏还挂着旧数字。 */
describe('api counts notification', () => {
  let events = []
  const listener = () => events.push(1)

  beforeEach(() => {
    events = []
    resetCsrf()
    vi.useFakeTimers()
    global.fetch = vi.fn(async url => new Response(
      JSON.stringify(String(url).includes('/auth/csrf') ? { token: 't' } : { total: 0 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }
    ))
    window.addEventListener('cdc-counts-change', listener)
  })

  afterEach(() => {
    window.removeEventListener('cdc-counts-change', listener)
    vi.useRealTimers()
  })

  it('fires after a successful write', async () => {
    await api('/tasks/1', { method: 'DELETE' })
    await vi.advanceTimersByTimeAsync(300)
    expect(events).toHaveLength(1)
  })

  it('stays quiet for reads', async () => {
    await api('/tasks/summary')
    await vi.advanceTimersByTimeAsync(300)
    expect(events).toHaveLength(0)
  })

  it('ignores read-only posts such as preview and validate', async () => {
    await api('/flows/1/nodes/n1/preview', { method: 'POST', body: '{}' })
    await api('/flows/validate', { method: 'POST', body: '{}' })
    await vi.advanceTimersByTimeAsync(300)
    expect(events).toHaveLength(0)
  })

  it('coalesces a burst of writes into one notification', async () => {
    await api('/flows', { method: 'POST', body: '{}' })
    await api('/flows/2', { method: 'PUT', body: '{}' })
    await api('/flows/3', { method: 'DELETE' })
    await vi.advanceTimersByTimeAsync(300)
    expect(events).toHaveLength(1)
  })
})
