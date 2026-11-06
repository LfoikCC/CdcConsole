import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { components } from '../../components/ui'
import { identity } from '../../auth'

vi.mock('../../api', () => ({ api: vi.fn() }))
vi.mock('../../components/ui/feedback', () => ({
  CdcMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  CdcMessageBox: { confirm: vi.fn() }
}))

import { api } from '../../api'
import DashboardsView from './DashboardsView.vue'

const published = { id: 1, name: '销售日报', description: '每天更新', status: 'PUBLISHED', cardCount: 4, folderId: null, publishedAt: '2026-11-05T09:30:00Z' }
const viewed = { id: 2, name: '库存看板', description: '', status: 'PUBLISHED', cardCount: 3, folderId: 9, publishedAt: '2026-11-04T08:00:00Z' }
const draft = { id: 3, name: '未完成看板', description: '', status: 'DRAFT', cardCount: 1, folderId: 9, updatedAt: '2026-11-06T10:00:00Z' }
const folders = [{ id: 9, name: '供应链', parentId: null }, { id: 10, name: '华东', parentId: 9 }]

let wrapper
let router

async function mountView(path = '/bi/dashboards') {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/bi/dashboards', component: DashboardsView },
    { path: '/bi/dashboards/:id', component: { template: '<div/>' } },
    { path: '/bi/dashboards/:id/edit', component: { template: '<div/>' } }
  ] })
  router.push(path)
  await router.isReady()
  wrapper = mount(DashboardsView, { global: { components, plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  identity.user = { role: 'ADMIN' }
  api.mockReset()
  api.mockImplementation(async (path) => {
    if (path.startsWith('/bi/dashboards?size=100')) {
      const inFolder = path.includes('folder=9')
      return { items: inFolder ? [viewed, draft] : [published, viewed, draft] }
    }
    if (path === '/bi/dashboard-folders') return folders
    if (path === '/bi/dashboards/overview') return { published: [published], viewed: [viewed], drafts: [draft] }
    return {}
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('看板列表页', () => {
  it('默认展示最近发布、最近查看、草稿三块', async () => {
    await mountView()
    const text = wrapper.text()
    expect(text).toContain('最近发布')
    expect(text).toContain('最近查看')
    expect(text).toContain('草稿')
    // 三块各自的内容都在
    expect(text).toContain('销售日报')
    expect(text).toContain('库存看板')
    expect(text).toContain('未完成看板')
    // 概览模式下不渲染列表网格
    expect(wrapper.find('.bi-list-grid').exists()).toBe(false)
  })

  it('从侧栏进入目录后按目录取数并显示面包屑', async () => {
    await mountView('/bi/dashboards?folder=9')
    expect(api.mock.calls.some(([path]) => path === '/bi/dashboards?size=100&folder=9')).toBe(true)
    // 面包屑：全部看板 / 供应链
    const crumbs = wrapper.findAll('.bi-crumb').map(item => item.text())
    expect(crumbs).toEqual(['全部看板', '供应链'])
    // 进目录后自动切到列表，概览三块不显示
    expect(wrapper.find('.bi-overview').exists()).toBe(false)
    expect(wrapper.findAll('.bi-list-card')).toHaveLength(2)
  })

  it('切到「全部」页签显示列表，搜索会离开概览', async () => {
    await mountView()
    await wrapper.findAll('.bi-tabs button').find(item => item.text().startsWith('全部')).trigger('click')
    expect(wrapper.findAll('.bi-list-card')).toHaveLength(3)
    expect(wrapper.find('.bi-overview').exists()).toBe(false)

    await wrapper.find('.search-box input').setValue('库存')
    expect(wrapper.findAll('.bi-list-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('库存看板')
  })

  it('普通用户看不到草稿入口', async () => {
    identity.user = { role: 'VIEWER' }
    await mountView()
    const text = wrapper.text()
    expect(text).toContain('最近发布')
    // 没有草稿页签，也没有草稿块
    expect(wrapper.findAll('.bi-tabs button').map(item => item.text()).some(label => label.startsWith('草稿'))).toBe(false)
    expect(text).not.toContain('未完成看板')
  })
})
