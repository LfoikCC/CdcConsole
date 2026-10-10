import { defineStore } from 'pinia'
import { api, getAll } from '../api'
import { AuthenticatedSseClient } from '../sseClient'

export const useAppStore = defineStore('app', {
  state: () => ({ sources: [], targets: [], openAlerts: 0, connected: false }),
  actions: {
    async loadConnections() {
      [this.sources, this.targets] = await Promise.all([getAll('/sources'), getAll('/targets')])
    },
    async loadAlerts() { this.openAlerts = (await api('/alerts?status=OPEN')).length },
    connectRuntime() {
      if (this.runtime) return
      this.runtime = new AuthenticatedSseClient({
        onState: state => { this.connected = state.connected },
        onEvent: (event, value) => {
          if (event === 'connected' || event === 'heartbeat') this.connected = true
          if (event === 'connected' && value?.replayGap) window.dispatchEvent(new Event('cdc-runtime-change'))
          if (['metrics-updated', 'task-log'].includes(event)) window.dispatchEvent(new Event('cdc-monitor-change'))
          if (['task-status', 'task', 'subscription', 'reconciliation', 'alert'].includes(event)) window.dispatchEvent(new Event('cdc-runtime-change'))
        }
      })
      this.runtime.start()
    }
  }
})
