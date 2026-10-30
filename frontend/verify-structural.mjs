import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:5173';
const OUT = 'shots/structural';
const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', e => errors.push(String(e)));

await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.fill('#login-user', 'admin');
await page.fill('#login-password', 'admin123');
await page.click('button[type=submit]');
await page.waitForURL('**/tasks', { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(2000);
const firstTaskHref = await page.evaluate(() => document.querySelector('.task-table a[href^="/tasks/"]')?.getAttribute('href') || null);

const report = {};

// 1. regression: task table density + action column visible
report.tasks = await page.evaluate(() => {
  const t = document.querySelector('.task-table table');
  const row = t?.querySelector('tbody tr');
  const wrap = document.querySelector('.task-table');
  return {
    rowHeight: row ? Math.round(row.getBoundingClientRect().height) : null,
    tableWidth: t ? Math.round(t.getBoundingClientRect().width) : null,
    wrapWidth: wrap ? Math.round(wrap.getBoundingClientRect().width) : null,
    actionCellVisible: t ? Math.round(t.querySelector('tbody tr td:last-child').getBoundingClientRect().right) <= Math.round(wrap.getBoundingClientRect().right) + 1 : null,
  };
});
await page.screenshot({ path: `${OUT}/01-tasks.png` });

// 2. connections two-column
await page.goto(`${BASE}/connections/sources`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
report.connections = await page.evaluate(() => {
  const layout = document.querySelector('.connections-layout');
  const aside = document.querySelector('.connections-subnav');
  const content = document.querySelector('.connections-content');
  const cs = layout ? getComputedStyle(layout) : null;
  const a = aside?.getBoundingClientRect(), c = content?.getBoundingClientRect();
  return {
    display: cs?.display,
    columns: cs?.gridTemplateColumns,
    asideWidth: a ? Math.round(a.width) : null,
    sideBySide: !!(a && c && a.right <= c.left + 2 && Math.abs(a.top - c.top) < 40),
    headingVisible: !!document.querySelector('.subnav-heading')?.getClientRects().length,
  };
});
await page.screenshot({ path: `${OUT}/02-connections.png` });

// 3. audit wrap
await page.goto(`${BASE}/audit`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
report.audit = await page.evaluate(() => {
  const cell = document.querySelector('.audit-resource');
  if (!cell) return { present: false };
  const cs = getComputedStyle(cell);
  const wrap = document.querySelector('.table-wrap');
  return {
    present: true,
    whiteSpace: cs.whiteSpace,
    wrapScroll: wrap.scrollWidth > wrap.clientWidth + 1,
    cellWidth: Math.round(cell.getBoundingClientRect().width),
  };
});
await page.screenshot({ path: `${OUT}/03-audit.png` });

// 4. task detail: description + keyboard rows
await page.goto(`${BASE}${firstTaskHref || '/tasks'}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1500);
report.detail = await page.evaluate(() => {
  const p = document.querySelector('.page-header p');
  const rows = [...document.querySelectorAll('.table-wrap tbody tr[tabindex="0"]')];
  return {
    description: p ? { visible: !!p.getClientRects().length, text: (p.textContent || '').slice(0, 40) } : null,
    focusableRows: rows.length,
    firstRowRole: rows[0]?.getAttribute('role') || null,
    cursor: rows[0] ? getComputedStyle(rows[0]).cursor : null,
  };
});
await page.screenshot({ path: `${OUT}/04-detail.png` });

// 5. container classes resolved (probe elements, same specificity as real usage)
report.containers = await page.evaluate(() => {
  const probe = (cls, extra = '') => {
    const el = document.createElement('div');
    el.className = cls;
    if (extra) el.setAttribute('style', extra);
    el.textContent = 'probe';
    document.body.appendChild(el);
    const cs = getComputedStyle(el);
    const out = { display: cs.display, border: cs.borderTopWidth, radius: cs.borderTopLeftRadius, padding: cs.paddingTop, gap: cs.gap };
    el.remove();
    return out;
  };
  return {
    tablePicker: probe('table-picker'),
    mappingCard: probe('mapping-card'),
    configCard: probe('config-card'),
    targetTag: probe('target-tag'),
    detailSummary: probe('detail-summary'),
  };
});

// 6. keyboard activation on a focusable row
report.keyboard = await page.evaluate(() => {
  const row = document.querySelector('.table-wrap tbody tr[tabindex="0"]');
  if (!row) return { ok: false, reason: 'no focusable row' };
  row.focus();
  const focused = document.activeElement === row;
  const before = document.body.innerHTML.length;
  row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  return { ok: focused, focused, before };
});

console.log(JSON.stringify(report, null, 2));
console.log('pageErrors:', errors.length ? errors.slice(0, 3) : 'none');
await browser.close();
