import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:5173';
const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => localStorage.setItem('cdc-theme', 'dark'));
const page = await ctx.newPage();
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.fill('#login-user', 'admin');
await page.fill('#login-password', 'admin123');
await page.click('button[type=submit]');
await page.waitForURL('**/tasks', { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(2000);
await page.screenshot({ path: 'shots/live/dark-tasks.png' });
const t = await page.evaluate(() => {
  const s = getComputedStyle(document.documentElement);
  return { bg: s.getPropertyValue('--background').trim(), card: s.getPropertyValue('--card').trim(), fg: s.getPropertyValue('--foreground').trim() };
});
console.log(JSON.stringify(t));
await browser.close();
