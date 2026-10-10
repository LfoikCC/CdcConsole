// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskConnectionFlow from './TaskConnectionFlow.vue'

describe('task detail connection flow', () => {
  const task = { sourceId: 1, targetId: 2, tables: [{}], targetSubscriptions: [{ id: 10, targetId: 2, name: '主订阅' }, { id: 11, targetId: 3, name: '附加订阅' }] }
  it('resolves actual connection types and all subscriptions without list-only type fields', () => {
    const wrapper = mount(TaskConnectionFlow, { props: { task, sources: [{ id: 1, name: '业务库', dbType: 'MYSQL' }], targets: [{ id: 2, name: '分析库', targetType: 'STARROCKS_STREAM_LOAD' }, { id: 3, name: '报表库', targetType: 'SQLSERVER' }] } })
    expect(wrapper.find('.src').text()).toBe('MySQL · 业务库')
    expect(wrapper.findAll('.sink').map(node => node.text())).toEqual(['StarRocks · 分析库 / 主订阅', 'SQL Server · 报表库 / 附加订阅'])
    expect(wrapper.text()).toContain('1 张源表 · 2 个目标订阅')
  })
  it('shows IDs until connections load and reacts to loaded metadata', async () => {
    const wrapper = mount(TaskConnectionFlow, { props: { task } })
    expect(wrapper.find('.src').text()).toBe('源连接 #1')
    expect(wrapper.find('.sink').text()).toContain('目标连接 #2')
    await wrapper.setProps({ sources: [{ id: '1', name: '业务库', dbType: 'MYSQL' }] })
    expect(wrapper.find('.src').text()).toBe('MySQL · 业务库')
  })
  it('supports legacy tasks without subscriptions', () => {
    const wrapper = mount(TaskConnectionFlow, { props: { task: { sourceId: 1, targetId: 2, sourceType: 'MYSQL', targetType: 'KAFKA', tables: [] } } })
    expect(wrapper.find('.sink').text()).toBe('Kafka · 目标连接 #2')
  })
})
