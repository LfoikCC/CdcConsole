import { describe, expect, it } from 'vitest'
import { canRunAction } from './taskState'

describe('task operation availability', () => {
  it.each(['DRAFT', 'STOPPED', 'FAILED'])('starts from %s', status => {
    expect(canRunAction(status, 'start')).toBe(true)
  })
  it.each(['STARTING', 'SNAPSHOTTING', 'RUNNING', 'DEGRADED'])('pauses from %s', status => {
    expect(canRunAction(status, 'pause')).toBe(true)
    expect(canRunAction(status, 'edit')).toBe(false)
  })
  it('resumes only from PAUSED and retains configuration lock', () => {
    expect(canRunAction('PAUSED', 'resume')).toBe(true)
    expect(canRunAction('PAUSED', 'edit')).toBe(false)
    expect(canRunAction('RUNNING', 'resume')).toBe(false)
  })
  it('edits stopped drafts, but never stops them again', () => {
    for (const status of ['DRAFT', 'STOPPED']) {
      expect(canRunAction(status, 'edit')).toBe(true)
      expect(canRunAction(status, 'stop')).toBe(false)
    }
  })
  it('never enables unknown states or actions', () => {
    expect(canRunAction(undefined, 'stop')).toBe(false)
    expect(canRunAction('RUNNING', 'delete-everything')).toBe(false)
  })
})
