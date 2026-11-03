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

/**
 * 写操作成功后通知外壳重新取一次计数。
 *
 * <p>侧栏的「同步任务 / 告警中心 / 数据账户」数字挂在 App.vue 上，页面里的增删改
 * 不会自动告诉它，表现就是「列表已经空了，侧栏还挂着 1，F5 才对」。
 * 放在 api() 里统一触发，比在每个调用点手写事件可靠；预览和校验虽然也是 POST，
 * 但只读不改数据，跳过。
 */
const READ_ONLY_POST = /\/(preview|validate)$/
let countsTimer = null
function notifyCountsChanged(path) {
  if (READ_ONLY_POST.test(path)) return
  clearTimeout(countsTimer)
  countsTimer = setTimeout(() => window.dispatchEvent(new Event('cdc-counts-change')), 200)
}

export async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  if (!['GET','HEAD','OPTIONS'].includes(method) && !csrfToken) await loadCsrf()
  // FormData 必须让浏览器自己带 boundary，手写 Content-Type 会让后端解析失败
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData
  const headers = { ...(isForm ? {} : { 'Content-Type': 'application/json' }), ...options.headers }
  if (csrfToken && !['GET','HEAD','OPTIONS'].includes(method)) headers['X-CSRF-Token'] = csrfToken
  const response = await fetch(`${base}/api${path}`, { ...options, headers, credentials: 'include', cache: 'no-store' })
  if (response.ok && !['GET','HEAD','OPTIONS'].includes(method)) notifyCountsChanged(path)
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

/** 上传文件（带进度）。FormData 不设 Content-Type，交给浏览器生成 boundary。 */
export async function upload(path, formData, onProgress) {
  await loadCsrf()
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${base}/api${path}`)
    xhr.withCredentials = true
    if (csrfToken) xhr.setRequestHeader('X-CSRF-Token', csrfToken)
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && onProgress) onProgress(Math.round((event.loaded / event.total) * 100))
    }
    xhr.onload = () => {
      let data = null
      try { data = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { data = null }
      if (xhr.status >= 200 && xhr.status < 300) {
        notifyCountsChanged(path)
        resolve(data)
      } else reject(new Error(data?.error || `上传失败 (${xhr.status})`))
    }
    xhr.onerror = () => reject(new Error('上传失败，请检查网络'))
    xhr.send(formData)
  })
}
