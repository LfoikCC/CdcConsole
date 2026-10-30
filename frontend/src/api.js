let csrfToken = null
let csrfRequest = null
const base = String(import.meta.env.VITE_CDC_BACKEND_URL || '').replace(/\/$/, '')
export function runtimeEventsPath() { return `${base}/api/runtime/events` }
export async function loadCsrf() {
  if (!csrfRequest) csrfRequest = fetch(`${base}/api/auth/csrf`, { credentials: 'include', cache: 'no-store' })
    .then(async r => { if (!r.ok) throw new Error('无法获取安全令牌'); csrfToken = (await r.json()).token; return csrfToken })
    .finally(() => { csrfRequest = null })
  return csrfRequest
}
export function resetCsrf() { csrfToken = null }
export async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  if (!['GET','HEAD','OPTIONS'].includes(method) && !csrfToken) await loadCsrf()
  const headers = { 'Content-Type': 'application/json', ...options.headers }
  if (csrfToken && !['GET','HEAD','OPTIONS'].includes(method)) headers['X-CSRF-Token'] = csrfToken
  const response = await fetch(`${base}/api${path}`, { ...options, headers, credentials: 'include', cache: 'no-store' })
  if (response.status === 204) return null
  if (options.blob && response.ok) return response.blob()
  const body = await response.text()
  let data; try { data = body ? JSON.parse(body) : null } catch { data = null }
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') { resetCsrf(); window.dispatchEvent(new Event('cdc-session-expired')) }
    const error = new Error(data?.error || data?.message || (response.status === 403 ? '当前角色没有此操作权限，或安全令牌已失效' : `请求失败 (${response.status})`))
    error.status = response.status
    throw error
  }
  return data
}
export const getAll = async path => (await api(path + (path.includes('?') ? '&' : '?') + 'page=0&size=500')).items
