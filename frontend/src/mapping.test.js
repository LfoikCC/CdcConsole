import { describe, expect, it } from 'vitest'
import { mapByName, mapByOrder, validMapping } from './mapping'

describe('field mapping', () => {
  it('matches same-name columns without duplicates', () => {
    expect(mapByName(['id','amount','created_at'], ['id','created_at','status']))
      .toEqual({ id:'id', created_at:'created_at' })
  })
  it('maps by position up to the shortest list', () => {
    expect(mapByOrder(['id','amount','extra'], ['order_id','total']))
      .toEqual({ id:'order_id', amount:'total' })
  })
  it('rejects missing or duplicated targets', () => {
    expect(validMapping({id:'id',total:'id'}, ['id','total'], ['id'])).toBe(false)
    expect(validMapping({id:'id'}, ['id'], ['id'])).toBe(true)
  })
})
