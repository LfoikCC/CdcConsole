const startable = new Set(['DRAFT', 'STOPPED', 'FAILED'])
const pausable = new Set(['RUNNING', 'DEGRADED', 'STARTING', 'SNAPSHOTTING'])

export function canRunAction(status, action) {
  switch (action) {
    case 'start': return startable.has(status)
    case 'pause': return pausable.has(status)
    case 'resume': return status === 'PAUSED'
    case 'stop': return pausable.has(status) || status === 'PAUSED' || status === 'FAILED'
    case 'edit': return status === 'DRAFT' || status === 'STOPPED'
    default: return false
  }
}
