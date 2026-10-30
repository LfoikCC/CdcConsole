import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:5173';
const OUT = process.argv[2] || 'shots';

const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const page = await ctx.newPage();
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));

await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.screenshot({ path: `${OUT}/01-login.png`, fullPage: false });

await page.fill('#login-user', 'admin');
await page.fill('#login-password', 'admin123');
await page.click('button[type=submit]');
await page.waitForURL('**/tasks', { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(2500);
await page.screenshot({ path: `${OUT}/02-tasks.png`, fullPage: false });

const firstTask = page.locator('table tbody tr a, table tbody tr .task-link').first();
if (await firstTask.count()) {
  await firstTask.click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: `${OUT}/03-task-detail.png`, fullPage: false });
}

console.log('url:', page.url());
console.log('console-errors:', errors.length ? errors.slice(0, 5) : 'none');
const tokens = await page.evaluate(() => {
  const s = getComputedStyle(document.documentElement);
  const pick = k => s.getPropertyValue(k).trim();
  return {
    bg: pick('--background'), fg: pick('--foreground'), card: pick('--card'),
    border: pick('--border'), primary: pick('--primary'),
    bodyFont: getComputedStyle(document.body).fontSize,
    btnRadius: getComputedStyle(document.querySelector('.btn') || document.body).borderRadius,
  };
});
console.log('tokens:', tokens);
await browser.close();
