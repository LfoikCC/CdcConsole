import { chromium } from 'playwright';

const BASE = 'http://localhost:5173';
const OUT = 'shots/status';
const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
const errors = [];
const writes = [];
page.on('pageerror', e => errors.push(String(e)));
page.on('request', r => { if (r.method() !== 'GET') writes.push(`${r.method()} ${new URL(r.url()).pathname}`); });

const toastText = async () => {
  const el = page.locator('.toast').first();
  return (await el.count()) ? (await el.innerText()).trim() : null;
};

await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
await page.fill('#login-user', 'admin');
await page.fill('#login-password', 'admin123');
await page.click('button[type=submit]');
await page.waitForURL('**/tasks', { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1500);
const taskHref = await page.evaluate(() => document.querySelector('.task-table a[href^="/tasks/"]')?.getAttribute('href') || null);

const report = {};

// 1. connections: status badges
await page.goto(`${BASE}/connections/sources`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1500);
report.connections = await page.evaluate(() => {
  const cells = [...document.querySelectorAll('.table-wrap tbody tr')].map(tr => {
    const badges = [...tr.querySelectorAll('.badge')].map(b => ({ text: b.textContent.trim(), cls: b.className }));
    return badges;
  });
  return { rows: cells.length, badges: cells };
});
await page.screenshot({ path: `${OUT}/01-connections.png` });

// 2. connection validation: blank name must be blocked before any write
writes.length = 0;
await page.click('button:has-text("新增源连接")').catch(() => {});
await page.waitForTimeout(600);
const saveBtn = page.locator('.modal .btn', { hasText: '保存连接' }).first();
if (await saveBtn.count()) {
  await saveBtn.click();
  await page.waitForTimeout(600);
  report.connectionValidation = { toast: await toastText(), writes: [...writes] };
  await page.screenshot({ path: `${OUT}/02-connection-validation.png` });
  await page.locator('.modal .btn', { hasText: '取消' }).first().click().catch(() => {});
} else {
  report.connectionValidation = { error: 'save button not found' };
}

// 3. task detail instances: chinese status badges
if (taskHref) {
  await page.goto(`${BASE}${taskHref}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  const nav = page.locator('.dnav-item', { hasText: '运行监控' }).first();
  if (await nav.count()) { await nav.click(); await page.waitForTimeout(1200); }
  report.instances = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.table-wrap tbody tr')].slice(0, 4);
    return rows.map(tr => [...tr.querySelectorAll('td')].slice(0, 2).map(td => ({
      text: td.innerText.trim(),
      badge: !!td.querySelector('.badge'),
      cls: td.querySelector('.badge')?.className || null,
    })));
  });
  await page.screenshot({ path: `${OUT}/03-instances.png` });
}

// 4. monitor: event level badges
await page.goto(`${BASE}/monitor`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2000);
report.monitor = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.mon-event-row')].slice(0, 3);
  return rows.map(r => ({ badge: r.querySelector('.badge')?.textContent.trim() || null, cls: r.querySelector('.badge')?.className || null }));
});
await page.screenshot({ path: `${OUT}/04-monitor.png` });

console.log(JSON.stringify(report, null, 2));
console.log('pageErrors:', errors.length ? errors.slice(0, 3) : 'none');
await browser.close();
