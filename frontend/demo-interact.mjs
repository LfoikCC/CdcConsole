import { chromium } from 'playwright';

const url = process.env.URL || 'file:///C:/Users/lhy/Documents/CodeX/Fluxa-core/docs/design/cdc-ui-demo.html';
const b = await chromium.launch({ channel: 'chrome' });
const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
const errs = [];
p.on('pageerror', e => errs.push('pageerror: ' + e.message));
p.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
await p.goto(url, { waitUntil: 'load' });
await p.waitForTimeout(400);

const rows = () => p.locator('#taskTable tbody tr').count();
const first = async () => (await p.locator('#taskTable tbody tr').first().innerText()).split('\n')[0];
const pager = async () => (await p.locator('#pager').innerText()).replace(/\s+/g, ' ').trim();
const log = (k, v) => console.log(String(k).padEnd(24), v);

log('1 initial rows', await rows());

await p.locator('#viewTabs button[data-view="running"]').click();
await p.waitForTimeout(200);
log('2 运行中', await rows());
await p.locator('#viewTabs button[data-view="all"]').click();
await p.waitForTimeout(200);
log('  back 全部', await rows());

await p.locator('#q').fill('库存');
await p.waitForTimeout(250);
log('3 search 库存', `${await rows()} -> ${await first()}`);
await p.locator('#q').fill('');
await p.waitForTimeout(200);

log('4 pager before', await pager());
await p.locator('#nextPage').click();
await p.waitForTimeout(250);
log('  pager next', await pager());
await p.locator('#prevPage').click();
await p.waitForTimeout(200);

await p.locator('#taskTable tbody tr').first().click();
await p.waitForTimeout(300);
log('5 detail visible', await p.locator('#viewDetail').isVisible());
log('  list hidden', await p.locator('#viewList').isHidden());
log('  dTitle', await p.locator('#dTitle').innerText());

for (const t of ['runs', 'subs', 'recon', 'logs']) {
  await p.locator(`#dTabs button[data-tab="${t}"]`).click();
  await p.waitForTimeout(200);
  log(`6 tab ${t}`, await p.locator(`#tab-${t}`).isVisible());
}

await p.locator('#dPause').click();
await p.waitForTimeout(200);
log('7 pause ->', await p.locator('#dStatus').innerText());

await p.locator('#backBtn').click();
await p.waitForTimeout(300);
log('8 back to list', await p.locator('#viewList').isVisible());

await p.locator('#hintFab').click();
await p.waitForTimeout(250);
log('9 hint panel', await p.locator('#hintPanel').evaluate(e => e.classList.contains('show')));

await p.locator('#themeBtn').click();
await p.waitForTimeout(300);
log('10 theme', await p.evaluate(() => document.documentElement.getAttribute('data-theme')));

// row menu
await p.locator('#taskTable tbody tr [data-menu]').first().click();
await p.waitForTimeout(250);
log('11 row menu open', await p.locator('.menu.open').count());

console.log('\nerrors:', errs.length ? errs : 'none');
await b.close();
