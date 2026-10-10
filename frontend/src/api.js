import { dbxHost } from './hostAdapter'

function joinUrl(baseUrl, path) {
  const base = String(baseUrl || '').replace(/\/$/, '')
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${base.endsWith('/api') ? base : `${base}/api`}${normalizedPath}`
}

async function requestConfig() {
  const config = await dbxHost.getBackendConfig()
  return { baseUrl: config.baseUrl || '', token: config.accessToken || null }
}

export async function api(path, options = {}) {
  const config = await requestConfig()
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (config.token) headers.Authorization = `Bearer ${config.token}`
  const response = await fetch(joinUrl(config.baseUrl, path), { ...options, headers, credentials: config.token ? 'omit' : 'include' })
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const message = response.status === 401 ? '后端认证已失效，请在 DBX 插件设置中重新授权' : response.status === 403 ? '当前凭据没有此操作权限' : body.error || `请求失败 (${response.status})`
    throw new Error(message)
  }
  return body
}

export function runtimeEventsPath(baseUrl = '') { return joinUrl(baseUrl, '/runtime/events') }
export const getAll = async path => (await api(path + (path.includes('?') ? '&' : '?') + 'page=0&size=500')).items
