import { defineStore } from 'pinia'
import { api, getAll } from '../api'
import { AuthenticatedSseClient } from '../sseClient'

export const useAppStore = defineStore('app', {
  state: () => ({ sources: [], targets: [], accounts: [], openAlerts: 0, connected: false }),
  actions: {
    disconnectRuntime() { this.runtime?.stop(); this.runtime = null; this.connected = false },
    async loadConnections() {
      [this.sources, this.targets] = await Promise.all([getAll('/sources'), getAll('/targets')])
    },
    async loadAccounts() { this.accounts = await getAll('/accounts') },
    async loadAlerts() { this.openAlerts = (await api('/alerts/counts')).open || 0 },
    connectRuntime() {
      if (this.runtime) return
      this.runtime = new AuthenticatedSseClient({
        onState: state => { this.connected = state.connected },
        onEvent: (event, value) => {
          if (event === 'connected' || event === 'heartbeat') this.connected = true
          if (event === 'connected' && value?.replayGap) window.dispatchEvent(new Event('cdc-runtime-change'))
          if (['metrics-updated', 'task-log'].includes(event)) window.dispatchEvent(new Event('cdc-monitor-change'))
          if (['task-status', 'task', 'subscription', 'reconciliation', 'alert', 'recovery'].includes(event)) window.dispatchEvent(new Event('cdc-runtime-change'))
        }
      })
      this.runtime.start()
    }
  }
})
