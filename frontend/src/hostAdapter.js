const DEFAULT_THEME = {
  '--background': '#09090b',
  '--foreground': '#fafafa',
  '--card': '#09090b',
  '--muted': '#27272a',
  '--muted-foreground': '#a1a1aa',
  '--border': '#27272a',
  '--primary': '#fafafa',
  '--primary-foreground': '#18181b',
  '--success': '#22c55e',
  '--warning': '#eab308',
  '--danger': '#ef4444',
  '--info': '#3b82f6'
}

function resolveHost() {
  if (typeof window === 'undefined') return null
  return window.dbx?.hostApi || window.dbx?.host || window.__DBX_HOST_API__ || null
}

function normalizeTokens(tokens = {}) {
  return Object.fromEntries(Object.entries({ ...DEFAULT_THEME, ...tokens }).map(([key, value]) => [key.startsWith('--') ? key : `--${key}`, String(value)]))
}

function applyTokens(tokens) {
  const normalized = normalizeTokens(tokens)
  const root = document.documentElement
  Object.entries(normalized).forEach(([key, value]) => root.style.setProperty(key, value))
  const aliases = {
    '--bg': '--background', '--bg-elev': '--card', '--panel': '--card', '--panel-2': '--card',
    '--hover': '--border', '--active': '--border', '--line': '--border', '--line-soft': '--border',
    '--text': '--foreground', '--text-bright': '--foreground',
    '--fg': '--foreground', '--muted-fg': '--muted-foreground', '--primary-fg': '--primary-foreground',
    '--accent': '--muted', '--green': '--success', '--yellow': '--warning', '--red': '--danger', '--cyan': '--info'
  }
  Object.entries(aliases).forEach(([alias, source]) => root.style.setProperty(alias, `var(${source})`))
  root.setAttribute('data-theme', normalized['--background'] === '#ffffff' ? 'light' : 'dark')
  window.dispatchEvent(new Event('cdc-theme-change'))
  return normalized
}

function localTheme() {
  if (typeof window === 'undefined') return DEFAULT_THEME
  let saved
  try { saved = window.localStorage.getItem('cdc-theme') } catch {}
  return saved !== 'dark'
    ? { ...DEFAULT_THEME, '--background': '#ffffff', '--foreground': '#18181b', '--card': '#ffffff', '--muted': '#f4f4f5', '--muted-foreground': '#71717a', '--border': '#e4e4e7', '--primary': '#18181b', '--primary-foreground': '#fafafa', '--success': '#16a34a', '--warning': '#b45309', '--danger': '#dc2626', '--info': '#2563eb' }
    : DEFAULT_THEME
}

function call(host, method, ...args) {
  return typeof host?.[method] === 'function' ? Promise.resolve(host[method](...args)) : Promise.reject(new Error(`DBX Host API 缺少 ${method}`))
}

export class DbxHostAdapter {
  constructor() {
    this.host = resolveHost()
    this.modeValue = import.meta.env.MODE === 'dbx' || this.host ? 'dbx' : 'standalone'
    this.themeUnsubscribe = null
    this.tokenUnsubscribe = null
    this.backendConfig = null
  }

  mode() { return this.modeValue }
  isDbx() { return this.modeValue === 'dbx' }
  async getHostInfo() { return this.isDbx() ? call(this.host, 'getHostInfo') : { apiVersion: 'standalone', capabilities: [] } }
  async compatibility() {
    if (!this.isDbx()) return { compatible: true, mode: 'standalone', missing: [] }
    const required = ['getHostInfo', 'getBackendConfig', 'onBackendTokenChange']
    const optional = ['getThemeTokens', 'onThemeChange']
    const missing = required.filter(method => typeof this.host?.[method] !== 'function')
    const warnings = optional.filter(method => typeof this.host?.[method] !== 'function')
    const info = await this.getHostInfo().catch(() => ({}))
    const version = String(info.apiVersion || info.hostApiVersion || '')
    if (!/^1(?:\.|$)/.test(version)) missing.push('host_api 1')
    if (!missing.length) {
      try { await this.getBackendConfig() } catch { missing.push('后端地址或授权') }
    }
    return { compatible: missing.length === 0, mode: 'dbx', missing, warnings, hostInfo: info }
  }
  async getContext() { return this.isDbx() ? call(this.host, 'getContext') : {} }
  async getThemeTokens() { return this.isDbx() ? normalizeTokens(await call(this.host, 'getThemeTokens')) : localTheme() }
  onThemeChange(listener) {
    if (!this.isDbx() || typeof this.host?.onThemeChange !== 'function') return () => {}
    const unsubscribe = this.host.onThemeChange(tokens => listener(normalizeTokens(tokens)))
    return typeof unsubscribe === 'function' ? unsubscribe : () => {}
  }
  async getBackendConfig() {
    if (this.backendConfig?.expiresAt && Date.parse(this.backendConfig.expiresAt) - Date.now() < 60000) this.backendConfig = null
    if (this.backendConfig) return this.backendConfig
    if (this.isDbx()) this.backendConfig = await call(this.host, 'getBackendConfig')
    else this.backendConfig = { baseUrl: import.meta.env.VITE_CDC_BACKEND_URL || '', authMode: 'COOKIE' }
    if (this.isDbx() && (!this.backendConfig?.baseUrl || !this.backendConfig?.accessToken)) {
      this.backendConfig = null
      throw new Error('DBX 未提供 CDC 后端地址或访问令牌，请在插件设置中配置')
    }
    return this.backendConfig
  }
  onBackendTokenChange(listener) {
    if (!this.isDbx() || typeof this.host?.onBackendTokenChange !== 'function') return () => {}
    const unsubscribe = this.host.onBackendTokenChange(config => { this.backendConfig = config; listener(config) })
    return typeof unsubscribe === 'function' ? unsubscribe : () => {}
  }
  async notify(input) { return this.isDbx() && this.host?.notify ? this.host.notify(input) : undefined }
  async confirm(input) { return this.isDbx() && this.host?.confirm ? this.host.confirm(input) : window.confirm(input.message || input) }
  async openExternal(url) { return this.isDbx() && this.host?.openExternal ? this.host.openExternal(url) : window.open(url, '_blank', 'noopener') }
  async openPluginSettings() { return this.isDbx() && this.host?.openPluginSettings ? this.host.openPluginSettings() : undefined }
  async registerCommands(commands) { return this.isDbx() && this.host?.registerCommands ? this.host.registerCommands(commands) : undefined }
  toggleTheme() {
    if (this.isDbx()) return
    const theme = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark'
    try { window.localStorage.setItem('cdc-theme', theme) } catch {}
    applyTokens(localTheme())
  }
  async initialize() {
    const tokens = await this.getThemeTokens().catch(() => localTheme())
    applyTokens(tokens)
    if (this.isDbx()) {
      this.themeUnsubscribe = this.onThemeChange(next => applyTokens(next))
      this.tokenUnsubscribe = this.onBackendTokenChange(() => window.dispatchEvent(new Event('cdc-backend-config-change')))
    }
    return this
  }
  dispose() { this.themeUnsubscribe?.(); this.tokenUnsubscribe?.() }
}

export const dbxHost = new DbxHostAdapter()
export { applyTokens, DEFAULT_THEME }
