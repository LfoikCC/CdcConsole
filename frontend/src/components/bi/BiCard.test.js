import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BiCard from './BiCard.vue'
import { components } from '../ui'

const card = { type: 'table', title: '明细', datasetId: 7, config: { pagination: true, pageSize: 50 } }

function mountCard(extra = {}) {
  return mount(BiCard, {
    props: { card, editable: true, data: { columns: ['名称'], rows: [['行0']] }, ...extra },
    global: { components }
  })
}

describe('BiCard', () => {
  it('opens the editor only when the draggable header is double-clicked', async () => {
    const wrapper = mountCard()

    // 正文里有分页、滚动，连点两下不该把编辑器弹出来
    await wrapper.find('.bi-card-body').trigger('dblclick')
    expect(wrapper.emitted('open')).toBeUndefined()

    await wrapper.find('.bi-card-head').trigger('dblclick')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('starts dragging from the header only', async () => {
    const wrapper = mountCard()

    await wrapper.find('.bi-card-body').trigger('pointerdown', { button: 0 })
    expect(wrapper.emitted('drag-start')).toBeUndefined()

    await wrapper.find('.bi-card-head').trigger('pointerdown', { button: 0 })
    expect(wrapper.emitted('drag-start')).toHaveLength(1)
  })
})
