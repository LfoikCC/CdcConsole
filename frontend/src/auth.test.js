import { beforeEach,describe,it,expect,vi } from 'vitest'
import { identity,can,permit,login,logout } from './auth'
import { api,loadCsrf,resetCsrf } from './api'
vi.mock('./api',()=>({api:vi.fn(),loadCsrf:vi.fn(),resetCsrf:vi.fn()}))
beforeEach(()=>{vi.clearAllMocks();identity.user=null;identity.checked=false})
describe('local identity and permission boundaries',()=>{
  it('keeps viewers read-only and reserves configuration for administrators',()=>{
    identity.user={role:'VIEWER'};expect(can()).toBe(false);expect(can('admin')).toBe(false)
    identity.user={role:'OPERATOR'};expect(can()).toBe(true);expect(can('admin')).toBe(false)
    identity.user={role:'ADMIN'};expect(can()).toBe(true);expect(can('admin')).toBe(true)
  })
  it('restores the original element display after permission changes',()=>{
    const el=document.createElement('button');el.style.display='inline-flex'
    permit.mounted(el,{value:'admin'});expect(el.style.display).toBe('none')
    identity.user={role:'ADMIN'};permit.updated(el,{value:'admin'});expect(el.style.display).toBe('inline-flex')
    identity.user={role:'VIEWER'};permit.updated(el,{value:'admin'});expect(el.style.display).toBe('none')
  })
  it('rotates CSRF after login and clears cached identity even if logout fails',async()=>{
    api.mockResolvedValueOnce({id:1,role:'ADMIN'})
    await login('admin','password123')
    expect(loadCsrf).toHaveBeenCalledTimes(2);expect(resetCsrf).toHaveBeenCalled()
    expect(api.mock.calls[0][0]).toBe('/auth/login');expect(identity.checked).toBe(true)
    api.mockRejectedValueOnce(new Error('offline'))
    await expect(logout()).rejects.toThrow('offline');expect(identity.user).toBeNull()
  })
})
