import { afterEach, describe, expect, it, vi } from 'vitest'
import { DbxHostAdapter, applyTokens } from './hostAdapter'

afterEach(() => { delete window.__DBX_HOST_API__; window.localStorage.clear() })

describe('DBX host boundaries', () => {
  it('defaults standalone to light and remembers the selected theme', async () => {
    const adapter = new DbxHostAdapter()
    await adapter.initialize()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    adapter.toggleTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(window.localStorage.getItem('cdc-theme')).toBe('dark')
    await new DbxHostAdapter().initialize()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
  it('keeps background and secondary text tokens distinct', () => {
    applyTokens({ '--muted': '#111111', '--muted-foreground': '#dddddd' })
    expect(document.documentElement.style.getPropertyValue('--muted')).toBe('#111111')
    expect(document.documentElement.style.getPropertyValue('--muted-foreground')).toBe('#dddddd')
  })

  it('rejects backend configuration without a token', async () => {
    window.__DBX_HOST_API__ = { getBackendConfig: () => ({ baseUrl: 'https://cdc.example' }) }
    const adapter = new DbxHostAdapter()
    await expect(adapter.getBackendConfig()).rejects.toThrow('访问令牌')
  })

  it('keeps a rotated token in memory instead of requesting stale credentials', async () => {
    let rotate
    const getBackendConfig = vi.fn(() => ({ baseUrl: 'https://cdc.example', accessToken: 'old' }))
    window.__DBX_HOST_API__ = {
      getBackendConfig,
      onBackendTokenChange: listener => { rotate = listener; return () => {} }
    }
    const adapter = new DbxHostAdapter()
    await adapter.initialize()
    rotate({ baseUrl: 'https://cdc.example', accessToken: 'new' })
    expect((await adapter.getBackendConfig()).accessToken).toBe('new')
    expect(getBackendConfig).not.toHaveBeenCalled()
    adapter.dispose()
  })
})
