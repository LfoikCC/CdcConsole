// Metabase dark theme tokens — matches metabase/ui/colors/constants/themes/dark.ts (orion ramps)
const dark = { background:'#0c1419',foreground:'#f2f4f5',card:'#18242b',muted:'#202d34','muted-foreground':'#93a1a8',border:'#2b3a42',primary:'#509ee2','primary-foreground':'#ffffff',success:'#7ab648',warning:'#e8b921',danger:'#e3595e',info:'#509ee2' }
export function applyTheme(theme) {
  const root = document.documentElement
  root.dataset.theme = theme === 'dark' ? 'dark' : 'light'
  for (const key of Object.keys(dark)) theme === 'dark' ? root.style.setProperty(`--${key}`,dark[key]) : root.style.removeProperty(`--${key}`)
  window.dispatchEvent(new Event('cdc-theme-change'))
}
export function initializeTheme() { let theme = 'light'; try { theme = localStorage.getItem('cdc-theme') || theme } catch {} applyTheme(theme) }
export function toggleTheme() { const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'; try { localStorage.setItem('cdc-theme',theme) } catch {} applyTheme(theme) }
