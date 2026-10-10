import { dbxHost } from './hostAdapter'
import { runtimeEventsPath } from './api'

export class AuthenticatedSseClient {
  constructor({ onEvent, onState }) {
    this.onEvent = onEvent
    this.onState = onState
    this.controller = null
    this.retry = 0
    this.lastEventId = null
    this.closed = false
    this.reconnectTimer = null
    this.heartbeatTimer = null
    this.restartRequested = false
    this.backendUrl = null
    this.tokenUnsubscribe = dbxHost.onBackendTokenChange(() => this.restart())
    this.visibilityHandler = () => { if (!document.hidden && !this.controller) this.connect() }
  }
  async connect() {
    if (this.closed || this.controller) return
    const controller = new AbortController()
    this.controller = controller
    try {
      const config = await dbxHost.getBackendConfig()
      if (controller.signal.aborted || this.closed) return
      if (this.backendUrl !== config.baseUrl) this.lastEventId = null
      this.backendUrl = config.baseUrl
      const headers = { Accept: 'text/event-stream' }
      if (config.accessToken) headers.Authorization = `Bearer ${config.accessToken}`
      if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId
      const response = await fetch(runtimeEventsPath(config.baseUrl), { headers, signal: controller.signal, credentials: config.accessToken ? 'omit' : 'include', cache: 'no-store' })
      if (!response.ok || !response.body) throw new Error(response.status === 401 ? '认证失效' : `SSE 连接失败 (${response.status})`)
      this.retry = 0
      this.onState?.({ connected: true })
      this.armHeartbeatTimeout()
      await this.consume(response.body)
    } catch (error) {
      if (!this.closed && error.name !== 'AbortError') this.scheduleReconnect(error)
    } finally {
      this.controller = null
      clearTimeout(this.heartbeatTimer)
      if (!this.closed && this.restartRequested) {
        this.restartRequested = false
        this.connect()
      } else if (!this.closed) this.scheduleReconnect()
    }
  }
  async consume(body) {
    const decoder = new TextDecoder()
    const reader = body.getReader()
    let buffer = ''
    while (!this.closed) {
      const { done, value } = await reader.read()
      if (done) break
      this.armHeartbeatTimeout()
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
      const frames = buffer.split('\n\n')
      buffer = frames.pop() || ''
      frames.filter(Boolean).forEach(frame => this.dispatch(frame))
    }
  }
  dispatch(frame) {
    let event = 'message', id = null, data = []
    frame.split('\n').forEach(line => {
      const index = line.indexOf(':')
      const field = index < 0 ? line : line.slice(0, index)
      const value = index < 0 ? '' : line.slice(index + 1).replace(/^ /, '')
      if (field === 'event') event = value
      if (field === 'id') id = value
      if (field === 'data') data.push(value)
    })
    const text = data.join('\n')
    let value = text
    try { value = JSON.parse(text) } catch { /* non-JSON event payload */ }
    if (event === 'connected' && value?.replayGap) this.lastEventId = id
    else if (id && (!this.lastEventId || Number(id) > Number(this.lastEventId))) this.lastEventId = id
    if (data.length) this.onEvent?.(event, value)
  }
  scheduleReconnect(error) {
    if (this.closed || this.reconnectTimer) return
    this.onState?.({ connected: false, error })
    const delay = Math.min(30000, 1000 * (2 ** Math.min(this.retry++, 5))) + Math.floor(Math.random() * 250)
    this.reconnectTimer = window.setTimeout(() => { this.reconnectTimer = null; this.connect() }, delay)
  }
  armHeartbeatTimeout() {
    clearTimeout(this.heartbeatTimer)
    this.heartbeatTimer = window.setTimeout(() => this.controller?.abort(), 45000)
  }
  start() { document.addEventListener('visibilitychange', this.visibilityHandler); this.connect() }
  stop() { this.closed = true; clearTimeout(this.reconnectTimer); clearTimeout(this.heartbeatTimer); this.controller?.abort(); this.tokenUnsubscribe?.(); document.removeEventListener('visibilitychange', this.visibilityHandler) }
  restart() {
    if (this.closed) return
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    if (this.controller) { this.restartRequested = true; this.controller.abort() }
    else this.connect()
  }
}
