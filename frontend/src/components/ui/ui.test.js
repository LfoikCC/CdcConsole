import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, Fragment } from 'vue'
import { CdcSelect, CdcOption, CdcTable, CdcTableColumn, CdcModal, CdcButton, CdcPagination, CdcMenu } from './index'

describe('native CDC controls', () => {
  it('keeps brand icons in selected controls and names in searchable options', async () => {
    const wrapper=mount(CdcSelect,{props:{modelValue:'MYSQL',filterable:true},global:{stubs:{teleport:true}},slots:{default:()=>[h(CdcOption,{value:'MYSQL',label:'MySQL',brand:'MYSQL'}),h(CdcOption,{value:'KAFKA',label:'Kafka',brand:'KAFKA'})]}})
    expect(wrapper.find('.select-trigger img').attributes('src')).toBe('/brands/mysql-small.png')
    expect(wrapper.find('.select-trigger').text()).not.toContain('MySQL')
    expect(wrapper.find('.select-trigger').attributes('aria-label')).toBe('MySQL')
    await wrapper.find('.select-trigger').trigger('click')
    expect(wrapper.findAll('[role=option]').map(o=>o.text())).toEqual(['MySQL','Kafka'])
    await wrapper.find('input').setValue('kafka')
    expect(wrapper.findAll('[role=option]')).toHaveLength(1)
    await wrapper.find('[role=option]').trigger('click')
    expect(wrapper.emitted('update:modelValue')[0]).toEqual(['KAFKA'])
    wrapper.unmount()
  })
  it('navigates enabled dropdown options with keyboard and returns focus after selection',async()=>{
    const wrapper=mount(CdcSelect,{attachTo:document.body,props:{modelValue:'a'},slots:{default:()=>[h(CdcOption,{value:'a',label:'A',disabled:true}),h(CdcOption,{value:'b',label:'B'}),h(CdcOption,{value:'c',label:'C'})]}})
    const trigger=wrapper.find('.select-trigger')
    await trigger.trigger('keydown',{key:'ArrowDown'})
    expect(document.activeElement.textContent).toBe('B')
    await document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}))
    expect(document.activeElement.textContent).toBe('C')
    document.activeElement.click();await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:modelValue')[0]).toEqual(['c'])
    expect(document.activeElement).toBe(trigger.element);wrapper.unmount()
  })
  it('portals the action menu outside a clipping table container', async () => {
    const wrapper=mount(CdcMenu,{attachTo:document.body,slots:{default:()=>h('button','Delete')}})
    await wrapper.find('.menu-trigger').trigger('click')
    const menu=document.body.querySelector('[role=menu]')
    expect(menu).not.toBeNull()
    expect(wrapper.element.contains(menu)).toBe(false)
    await wrapper.find('.menu-trigger').trigger('keydown',{key:'Escape'})
    expect(document.body.querySelector('[role=menu]')).toBeNull()
    wrapper.unmount()
  })
  it('preserves numeric option values and supports multi selection', async () => {
    const wrapper = mount(CdcSelect, { props: { modelValue: [1], multiple: true }, global: { stubs: { teleport: true } }, slots: { default: () => h(Fragment, [h(CdcOption, { value: 1, label: 'One' }), h(CdcOption, { value: 2, label: 'Two' })]) } })
    await wrapper.find('.select-trigger').trigger('click')
    await wrapper.findAll('[role=option]')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')[0][0]).toEqual([1, 2])
    wrapper.unmount()
  })
  it('renders dynamic columns and business cell slots without losing values', () => {
    const wrapper = mount(CdcTable, { props: { data: [{ id: 1, count: 0 }] }, slots: { default: () => h(Fragment, [h(CdcTableColumn, { prop: 'count', label: 'Count' }), h(CdcTableColumn, { label: 'Custom' }, { default: ({ row }) => `#${row.id}` })]) } })
    expect(wrapper.findAll('td').map(cell => cell.text())).toEqual(['0', '#1'])
  })
  it('closes on Escape and emits a close event', async () => {
    const wrapper = mount(CdcModal, { props: { modelValue: true, title: 'Confirm' }, global: { stubs: { teleport: true } } })
    await wrapper.find('[role=dialog]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:modelValue')[0]).toEqual([false])
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
  it('ignores clicks on the overlay and only closes from the close button', async () => {
    const wrapper = mount(CdcModal, { props: { modelValue: true, title: 'Confirm' }, global: { stubs: { teleport: true } } })
    await wrapper.find('.overlay').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()

    await wrapper.find('.close-x').trigger('click')
    expect(wrapper.emitted('update:modelValue')[0]).toEqual([false])
    wrapper.unmount()
  })
  it('prevents duplicate submissions while a button is loading', () => {
    const wrapper = mount(CdcButton, { props: { loading: true } })
    expect(wrapper.find('button').element.disabled).toBe(true)
  })
  it('keeps pagination disabled at the boundaries', () => {
    const wrapper = mount(CdcPagination, { props: { currentPage: 1, total: 1, pageSize: 20 } })
    expect(wrapper.findAll('button').every(button => button.element.disabled)).toBe(true)
  })
})
