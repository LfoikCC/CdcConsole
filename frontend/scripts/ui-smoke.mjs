import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import assert from 'node:assert/strict'

const output = new URL('../../target/ui-screenshots/', import.meta.url).pathname.replace(/^\/([A-Z]:)/i, '$1')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))
page.on('console', message => { if (message.type() === 'warning' && /Failed to resolve component|Invalid prop|Extraneous/.test(message.text())) errors.push(message.text()) })
const source = { id: 1, name: '生产 MySQL', dbType: 'MYSQL', host: 'mysql-prod', port: 3306, username: 'cdc_reader', enabled: true, pollIntervalMs: 500 }
const target = { id: 2, name: '分析 StarRocks', targetType: 'STARROCKS_STREAM_LOAD', enabled: true, config: { database: 'dwd', username: 'cdc' } }
const mapping = { id: 11, mappingId: 21, sourceDatabase: 'sales', sourceTable: 'orders', targetSchema: 'dwd', targetTable: 'orders', columns: { id: 'id', amount: 'amount', updated_at: 'updated_at' }, keyColumns: ['id'], status: 'RUNNING', processedRows: 125890, errorCount: 0, reconciliation: { enabled: true, sourceTimeColumn: 'updated_at', targetTimeColumn: 'updated_at', fields: [{ sourceColumn: 'amount', targetColumn: 'amount', logicalType: 'NUMBER', metrics: ['SUM'] }] } }
const subscription = { id: 31, name: '订单分析订阅', targetId: 2, status: 'ACTIVE', pendingEvents: 0, pendingBytes: 0, initializedAt: '2026-10-09T10:00:00+08:00', tables: [{ id: 41, sourceTaskTableId: 11, status: 'ACTIVE', mapping }] }
const task = { id: 1, name: '订单实时同步', description: '交易数据同步至分析库', sourceId: 1, targetId: 2, status: 'RUNNING', syncMode: 'FULL_INCREMENTAL', timeZone: 'Asia/Shanghai', version: 1, tables: [mapping], targetSubscriptions: [subscription] }
const metadata = { table: 'orders', schema: null, columns: ['id','amount','updated_at'], primaryKeys: ['id'], columnDetails: [{ name: 'id', type: 'BIGINT' },{ name: 'amount', type: 'DECIMAL' },{ name: 'updated_at', type: 'DATETIME' }] }
await page.route('**/api/**', async route => {
  const url = new URL(route.request().url()), path = url.pathname.replace('/api', '')
  let body = []
  if (path === '/runtime/events') return route.fulfill({ contentType: 'text/event-stream', body: 'event: connected\ndata: {}\n\n' })
  if (path === '/sources') body = { items: [source] }
  else if (path === '/targets') body = { items: [target] }
  else if (path === '/tasks/summary') body = { total: 1, running: 1, degraded: 0, openAlerts: 0 }
  else if (path === '/tasks') body = { items: [task], total: 1 }
  else if (path === '/tasks/1') body = task
  else if (path.endsWith('/subscription-options')) body = { task, source, targets: [target], tables: [{ ...mapping, sourceColumns: metadata.columns, sourceColumnDetails: metadata.columnDetails }] }
  else if (path.endsWith('/reconciliation-options')) body = { subscription, tables: [{ ...mapping, sourceColumns: metadata.columns, sourceColumnDetails: metadata.columnDetails }] }
  else if (path.endsWith('/logs') || path.endsWith('/delivery-batches')) body = { items: [], total: 0 }
  else if (path.endsWith('/databases')) body = ['sales']
  else if (path.endsWith('/tables')) body = [metadata]
  else if (path === '/settings') body = { segmentSizeMb: 100, retentionDays: 7 }
  else if (path === '/mail/config') body = { enabled:false,host:'',port:587,secure:'starttls',username:'',password:'',fromName:'CDC 监控',fromEmail:'',passwordConfigured:false }
  else if (path === '/mail/history') body = { items:[],total:0 }
  else if (path === '/status') body = { sources: 1, targets: 1, cacheSegments: 24, cacheBytes: 2097152 }
  return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
})
try {
  await page.goto('http://127.0.0.1:5173/tasks')
  await page.getByRole('link', { name: '订单实时同步' }).waitFor()
  assert.equal(await page.locator('.sb-search').count(),0)
  assert.equal(await page.locator('.content h1').count(),0)
  await page.screenshot({ path: `${output}/tasks-light.png` })
  await page.getByRole('button',{name:'更多操作'}).click()
  await page.getByRole('menu').getByRole('button',{name:'删除',exact:true}).waitFor()
  const menuBox=await page.getByRole('menu').boundingBox()
  assert.ok(menuBox.y+menuBox.height<=900)
  assert.ok(await page.getByRole('menu').evaluate(el=>!el.closest('.table-wrap')))
  await page.screenshot({path:`${output}/task-actions-menu.png`})
  await page.keyboard.press('Escape')
  assert.equal(await page.getByRole('menu').count(),0)
  await page.setViewportSize({width:1440,height:400})
  await page.getByRole('button',{name:'更多操作'}).click()
  await page.getByRole('menu').waitFor()
  const shortBox=await page.getByRole('menu').boundingBox()
  assert.ok(shortBox.y>=0&&shortBox.y+shortBox.height<=400)
  await page.keyboard.press('Escape')
  await page.setViewportSize({width:1440,height:900})
  assert.equal(await page.locator('.sidebar').evaluate(el => Math.round(el.getBoundingClientRect().width)), 240)
  await page.getByRole('button', { name: '切换主题' }).click()
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
  await page.screenshot({ path: `${output}/tasks-dark.png` })
  await page.getByRole('button', { name: '切换主题' }).click()
  await page.goto('http://127.0.0.1:5173/tasks/1?tab=subscriptions')
  await page.getByText('订单分析订阅', { exact: true }).waitFor()
  assert.match(await page.locator('.task-connection-flow .src').innerText(), /MySQL.*生产 MySQL/)
  assert.match(await page.locator('.task-connection-flow .sink').innerText(), /StarRocks.*分析 StarRocks/)
  await page.screenshot({ path: `${output}/subscriptions.png` })
  await page.getByRole('button', { name: '编辑核对配置' }).click()
  await page.getByRole('dialog').waitFor()
  await page.locator('.acc-head').click()
  await page.getByRole('dialog').getByText('updated_at', { exact: true }).first().waitFor()
  await page.screenshot({ path: `${output}/reconciliation-config.png` })
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '新增目标订阅' }).click()
  await page.getByRole('dialog').getByText('目标连接', { exact: true }).waitFor()
  assert.equal(await page.getByRole('dialog').getByText('源连接', { exact: true }).count(), 1)
  await page.screenshot({ path: `${output}/subscription-wizard.png` })
  await page.keyboard.press('Escape')
  await page.goto('http://127.0.0.1:5173/tasks/new')
  await page.locator('.wizard-shell').waitFor()
  assert.equal(await page.getByRole('dialog').count(), 0)
  await page.screenshot({ path: `${output}/task-wizard.png` })
  for (const path of ['connections/sources','connections/targets','alerts','settings']) {
    await page.goto(`http://127.0.0.1:5173/${path}`)
    await page.locator('.content').waitFor()
    await page.waitForTimeout(150)
    await page.screenshot({ path: `${output}/${path.replaceAll('/','-')}.png` })
  }
  for (const [width,height] of [[1920,1080],[768,1024],[390,844]]) {
    await page.setViewportSize({ width, height })
    await page.goto('http://127.0.0.1:5173/tasks')
    await page.getByRole('link', { name: '订单实时同步' }).waitFor()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.screenshot({ path: `${output}/tasks-${width}.png` })
    if (width === 390) { await page.getByRole('button', { name: '打开导航' }).click(); await page.getByRole('link', { name: /告警中心/ }).click(); assert.equal(await page.locator('.sidebar').getAttribute('class'), 'sidebar') }
  }
  await page.goto('http://127.0.0.1:5173/alerts')
  assert.equal(await page.locator('.content h1').count(),0)
  await page.locator('.alert-filter').first().waitFor()
  assert.equal(await page.locator('.alert-filter').count(),4)
  assert.equal(await page.locator('.alert-filter').first().getAttribute('aria-pressed'),'true')
  assert.equal(await page.locator('.toolbar').count(),0)
  for(const [width,height] of [[1440,900],[390,844]]) {
    await page.setViewportSize({width,height})
    for(const theme of ['light','dark']) {
      const current=await page.locator('html').getAttribute('data-theme')
      if(current!==theme)await page.getByRole('button',{name:'切换主题'}).click()
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))
      await page.screenshot({path:`${output}/alerts-${theme}-${width}.png`})
    }
  }
  await page.locator('.alert-filter').nth(1).click()
  assert.equal(await page.locator('.alert-filter').nth(1).getAttribute('aria-pressed'),'true')
  await page.getByText('当前筛选下没有告警',{exact:true}).waitFor()
  assert.deepEqual(errors, [])
  await page.goto('http://127.0.0.1:5173/settings')
  await page.getByRole('button',{name:'邮件服务',exact:true}).click()
  await page.getByText('SMTP 服务器',{exact:true}).waitFor()
  assert.equal(await page.locator('.content h1').count(),0)
  const saveBox=await page.getByRole('button',{name:'保存配置',exact:true}).boundingBox()
  const testBox=await page.getByRole('button',{name:'测试连接',exact:true}).boundingBox()
  assert.equal(saveBox.height,testBox.height)
  assert.equal(saveBox.y,testBox.y)
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:900})
    for(const theme of ['light','dark']) {
      if(await page.locator('html').getAttribute('data-theme')!==theme)await page.getByRole('button',{name:'切换主题'}).click()
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))
      await page.screenshot({path:`${output}/mail-settings-${theme}-${width}.png`})
    }
  }
  await page.goto('http://127.0.0.1:5173/alerts')
  await page.getByRole('button',{name:'订阅规则',exact:true}).click()
  assert.equal(await page.locator('.content').getByRole('button',{name:'刷新',exact:true}).count(),1)
  await page.getByRole('button',{name:'新建订阅规则',exact:true}).click()
  await page.getByRole('dialog').waitFor()
  await page.screenshot({path:`${output}/mail-rule-editor-mobile.png`})
  await page.setViewportSize({width:1440,height:900})
  await page.screenshot({path:`${output}/mail-rule-editor-desktop.png`})
  await page.keyboard.press('Escape')
  await page.getByRole('button',{name:'邮件历史',exact:true}).click()
  assert.equal(await page.locator('.content').getByRole('button',{name:'刷新',exact:true}).count(),1)
  await page.getByText('查看实际发送结果与失败记录',{exact:true}).waitFor()
  assert.deepEqual(errors, [])
  console.log('UI smoke passed: pages, themes, modal controls, metadata, four viewport sizes')
} finally { await browser.close() }
