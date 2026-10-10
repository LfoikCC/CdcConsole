import { describe, expect, it, vi } from 'vitest'
import { AuthenticatedSseClient } from './sseClient'

describe('AuthenticatedSseClient', () => {
  it('parses data and tracks the latest event ID across replay', () => {
    const onEvent = vi.fn()
    const client = new AuthenticatedSseClient({ onEvent })
    client.dispatch('id: 9\nevent: task\ndata: {"taskId":1}')
    client.dispatch('id: 4\nevent: task\ndata: {"taskId":2}')
    expect(client.lastEventId).toBe('9')
    expect(onEvent).toHaveBeenCalledWith('task', { taskId: 2 })
    client.stop()
  })

  it('resets the cursor after a server replay gap', () => {
    const client = new AuthenticatedSseClient({ onEvent: vi.fn() })
    client.lastEventId = '999'
    client.dispatch('id: 2\nevent: connected\ndata: {"replayGap":true}')
    expect(client.lastEventId).toBe('2')
    client.stop()
  })
})
