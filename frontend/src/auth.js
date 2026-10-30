import { reactive } from 'vue'
import { api, loadCsrf, resetCsrf } from './api'
export const identity = reactive({ user: null, checked: false })
export function can(level = 'operate') { return level === 'admin' ? identity.user?.role === 'ADMIN' : ['ADMIN','OPERATOR'].includes(identity.user?.role) }
export const roleNames = { ADMIN: '管理员', OPERATOR: '普通用户', VIEWER: '只读用户' }
export async function checkIdentity() {
  if (identity.checked) return identity.user
  try { identity.user = await api('/auth/me') } catch (e) { if (e.status !== 401) throw e; identity.user = null }
  identity.checked = true
  return identity.user
}
export async function login(username, password) {
  await loadCsrf()
  identity.user = await api('/auth/login', { method:'POST', body:JSON.stringify({username,password}) })
  resetCsrf(); await loadCsrf(); identity.checked = true
}
export function clearIdentity() { identity.user = null; identity.checked = true; resetCsrf(); window.dispatchEvent(new Event('cdc-clear-session')) }
export async function logout() { try { await api('/auth/logout', { method:'POST' }) } finally { clearIdentity() } }

const displays = new WeakMap()
export const permit = {
  mounted(el,binding) { displays.set(el,el.style.display); el.style.display=can(binding.value||'operate')?displays.get(el):'none' },
  updated(el,binding) { el.style.display=can(binding.value||'operate')?(displays.get(el)||''):'none' }
}
