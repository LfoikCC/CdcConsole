import { defineComponent, h, ref, watch, nextTick, Teleport, Fragment, provide, inject, onMounted, onBeforeUnmount } from 'vue'
import { X, ChevronDown, ChevronLeft, ChevronRight, LoaderCircle } from 'lucide-vue-next'

function nodes(children) {
  return (children || []).flatMap(node => node.type === Fragment ? nodes(node.children) : [node]).filter(node => typeof node.type !== 'symbol')
}
const modelProps = { modelValue: null, disabled: Boolean }
const updateEvents = ['update:modelValue', 'change']
const control = (emit, value) => { emit('update:modelValue', value); emit('change', value) }

export const CdcButton = defineComponent({
  inheritAttrs: false,
  props: { type: String, loading: Boolean, disabled: Boolean, size: String, text: Boolean, link: Boolean, circle: Boolean, plain: Boolean },
  setup: (props, { slots, attrs }) => () => h('button', { ...attrs, type: 'button', disabled: props.disabled || props.loading,
    class: ['btn', props.type === 'primary' && !props.plain ? '' : 'btn-outline', (props.text || props.link) && 'btn-ghost', ['danger','success','warning','info'].includes(props.type) && `btn-${props.type}`, props.size === 'small' && 'btn-sm', props.circle && 'icon-btn', attrs.class], 'aria-busy': props.loading },
  [props.loading && h(LoaderCircle, { size: 14, class: 'spin' }), slots.default?.()])
})

let modalSequence = 0
export const CdcModal = defineComponent({
  inheritAttrs: false,
  props: { modelValue: Boolean, title: String, width: String, beforeClose: Function }, emits: ['update:modelValue', 'close'],
  setup(props, { slots, emit, attrs }) {
    const panel = ref(), titleId = `modal-title-${++modalSequence}`
    let previousFocus
    const close = () => { const done = () => { emit('update:modelValue', false); emit('close') }; props.beforeClose ? props.beforeClose(done) : done() }
    watch(() => props.modelValue, async open => {
      if (open) { previousFocus = document.activeElement; await nextTick(); panel.value?.focus() }
      else previousFocus?.focus?.()
    }, { immediate: true })
    function keydown(event) {
      if (event.key === 'Escape') { event.stopPropagation(); close() }
      if (event.key !== 'Tab') return
      const focusable = Array.from(panel.value.querySelectorAll('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]')).filter(el => el.getClientRects().length)
      const first = focusable[0], last = focusable.at(-1)
      if (!first) { event.preventDefault(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) { event.preventDefault(); first.focus() }
    }
    return () => props.modelValue ? h(Teleport, { to: 'body' }, h('div', { class: 'overlay show', onClick: e => e.target === e.currentTarget && close() },
      h('section', { ...attrs, ref: panel, role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId, tabindex: -1, class: ['modal', attrs.class], style: { width: props.width || '520px' }, onKeydown: keydown }, [
        h('header', { class: 'modal-head' }, [h('h2', { id: titleId, class: 'modal-title' }, props.title), h('button', { class: 'close-x', type: 'button', onClick: close, 'aria-label': '关闭' }, h(X, { size: 16 }))]),
        h('div', { class: 'modal-body' }, slots.default?.()), slots.footer && h('footer', { class: 'modal-foot' }, slots.footer())
      ]))) : null
  }
})

export const CdcInput = defineComponent({
  inheritAttrs: false, props: { ...modelProps, type: String, rows: [String, Number], showPassword: Boolean }, emits: updateEvents,
  setup(props, { emit, attrs }) {
    const visible = ref(false)
    return () => h('div', { class: 'input-control' }, [h(props.type === 'textarea' ? 'textarea' : 'input', { ...attrs, type: props.type === 'password' && visible.value ? 'text' : props.type || 'text', rows: props.rows,
      value: props.modelValue ?? '', disabled: props.disabled, onInput: e => emit('update:modelValue', e.target.value), onChange: e => emit('change', e.target.value) }),
    props.showPassword && h('button', { type: 'button', class: 'password-toggle', 'aria-label': visible.value ? '隐藏密码' : '显示密码', onClick: () => visible.value = !visible.value }, visible.value ? '隐藏' : '显示')])
  }
})
export const CdcInputNumber = defineComponent({ props: { ...modelProps, min: Number, max: Number }, emits: updateEvents,
  setup: (props, { emit }) => () => h('input', { type: 'number', value: props.modelValue, min: props.min, max: props.max, disabled: props.disabled,
    onInput: e => emit('update:modelValue', e.target.value === '' ? null : Number(e.target.value)), onChange: e => control(emit, e.target.value === '' ? null : Number(e.target.value)) }) })
export const CdcOption = defineComponent({ props: { label: String, value: null, disabled: Boolean }, setup: () => () => null })
export const CdcSelect = defineComponent({
  inheritAttrs: false, props: { ...modelProps, multiple: Boolean, clearable: Boolean, filterable: Boolean, placeholder: String }, emits: updateEvents,
  setup(props, { slots, attrs, emit }) {
    const query = ref(''), open = ref(false), root = ref(), popup = ref(), position = ref({})
    function outside(event) { if (!root.value?.contains(event.target) && !popup.value?.contains(event.target)) open.value = false }
    function closeOnScroll(event) { if (!popup.value?.contains(event.target)) open.value = false }
    onMounted(() => { document.addEventListener('pointerdown', outside); window.addEventListener('scroll', closeOnScroll, true); window.addEventListener('resize', closeOnScroll) })
    onBeforeUnmount(() => { document.removeEventListener('pointerdown', outside); window.removeEventListener('scroll', closeOnScroll, true); window.removeEventListener('resize', closeOnScroll) })
    watch(open, value => { if (value && root.value) { const box = root.value.getBoundingClientRect(); position.value = { position: 'fixed', left: `${Math.max(8, Math.min(box.left, innerWidth - Math.max(box.width,160) - 8))}px`, top: `${Math.max(8,Math.min(box.bottom + 4, innerHeight - 280))}px`, width: `${Math.max(box.width,160)}px`, right: 'auto', zIndex: 400 } } })
    function options() { return nodes(slots.default?.()).filter(n => n.type === CdcOption).map(n => n.props || {}) }
    const selected = value => props.multiple ? (props.modelValue || []).includes(value) : props.modelValue === value
    function choose(option) {
      if (option.disabled) return
      const value = props.multiple ? selected(option.value) ? props.modelValue.filter(v => v !== option.value) : [...(props.modelValue || []), option.value] : option.value
      control(emit, value); if (!props.multiple) { open.value = false; nextTick(()=>root.value?.querySelector('.select-trigger')?.focus()) }
    }
    return () => {
      const all = options(), labels = all.filter(o => selected(o.value)).map(o => o.label ?? String(o.value))
      return h('div', { ...attrs, ref: root, class: ['select-control', attrs.class] }, [
        h('button', { class: 'select-trigger', type: 'button', disabled: props.disabled, 'aria-expanded': open.value, 'aria-haspopup': 'listbox', onClick: () => { open.value = !open.value; query.value = '' },
          onKeydown: e => { if (e.key === 'Escape') { e.stopPropagation(); open.value = false } if (['ArrowDown','ArrowUp'].includes(e.key)) { e.preventDefault(); open.value = true; nextTick(() => { const choices=popup.value?.querySelectorAll('.select-list button:not(:disabled)'); (e.key==='ArrowUp'?choices?.[choices.length-1]:choices?.[0])?.focus() }) } } },
        [h('span', { class: !labels.length && 'dim' }, labels.join('、') || props.placeholder || '请选择'), h(ChevronDown, { size: 12 })]),
        open.value && h(Teleport, { to: 'body' }, h('div', { ref: popup, class: 'select-popover', style: position.value, onKeydown: e => { if (e.key === 'Escape') { e.stopPropagation(); open.value = false; root.value.querySelector('.select-trigger').focus() } if (e.key === 'Tab') { open.value = false; root.value.querySelector('.select-trigger').focus() } } }, [
          props.filterable && h('input', { value: query.value, placeholder: '搜索', 'aria-label': '筛选选项', onInput: e => query.value = e.target.value }),
          h('div', { class: 'select-list', role: 'listbox', 'aria-multiselectable': props.multiple, onKeydown: e => { if (e.key === 'Escape') { open.value = false; root.value.querySelector('.select-trigger').focus() } if (['ArrowDown','ArrowUp'].includes(e.key)) { e.preventDefault(); const buttons = Array.from(e.currentTarget.querySelectorAll('button:not(:disabled)')); const index = buttons.indexOf(document.activeElement); buttons[(index + (e.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus() } } }, [
            props.clearable && h('button', { type: 'button', onClick: () => { control(emit, props.multiple ? [] : ''); open.value = false } }, '清除选择'),
            ...all.filter(o => !query.value || String(o.label || o.value).toLowerCase().includes(query.value.toLowerCase())).map(o => h('button', { type: 'button', role: 'option', 'aria-selected': selected(o.value), disabled: o.disabled, class: selected(o.value) && 'selected', onClick: () => choose(o) }, [props.multiple && h('span', { class: 'option-check' }, selected(o.value) ? '✓' : ''), o.label || String(o.value)]))
          ])
        ]))
      ])
    }
  }
})
export const CdcForm = defineComponent({ setup: (_, { slots }) => () => h('div', { class: 'cdc-form' }, slots.default?.()) })
let fieldSequence = 0
export const CdcFormItem = defineComponent({ props: { label: String, required: Boolean }, setup(props, { slots }) { const id = `field-label-${++fieldSequence}`; return () => h('div', { class: 'form-field', role: 'group', 'aria-labelledby': props.label ? id : undefined }, [props.label && h('span', { id, class: 'field-label' }, [props.label, props.required && h('span', { class: 'req' }, ' *')]), slots.default?.()]) } })
export const CdcCheckbox = defineComponent({ props: modelProps, emits: updateEvents, setup: (props, { slots, emit }) => () => h('label', { class: 'inline-check' }, [h('input', { type: 'checkbox', checked: !!props.modelValue, disabled: props.disabled, onChange: e => control(emit, e.target.checked) }), h('span', slots.default?.())]) })
export const CdcSwitch = defineComponent({ props: { ...modelProps, activeText: String, inactiveText: String }, emits: updateEvents, setup: (props, { emit }) => () => h('label', { class: 'switch-control' }, [h('input', { type: 'checkbox', role: 'switch', checked: !!props.modelValue, disabled: props.disabled, onChange: e => control(emit, e.target.checked) }), h('span', props.modelValue ? props.activeText : props.inactiveText || props.activeText)]) })
const radioKey = Symbol('radio')
export const CdcRadioGroup = defineComponent({ props: modelProps, emits: updateEvents, setup(props, { slots, emit }) { provide(radioKey, { props, emit }); return () => h('div', { class: 'segmented', role: 'group' }, slots.default?.()) } })
export const CdcRadioButton = defineComponent({ props: { value: null }, setup(props, { slots }) { const group = inject(radioKey); return () => h('button', { type: 'button', class: group.props.modelValue === props.value && 'active', 'aria-pressed': group.props.modelValue === props.value, onClick: () => control(group.emit, props.value) }, slots.default?.()) } })

export const CdcTag = defineComponent({ props: { type: String }, setup: (props, { slots }) => () => h('span', { class: ['badge', `badge-${props.type || 'muted'}`] }, [h('i', { class: 'dot' }), slots.default?.()]) })
export const CdcAlert = defineComponent({ props: { type: String, title: String, description: String }, setup: (props, { slots }) => () => h('div', { class: ['alert-bar', props.type || 'info'], role: props.type === 'error' ? 'alert' : 'status' }, [h('div', [h('strong', props.title), props.description && h('p', props.description), slots.default?.()])]) })
export const CdcEmpty = defineComponent({ props: { description: String }, setup: (props, { slots }) => () => h('div', { class: 'empty-state' }, [props.description || '暂无数据', slots.default?.()]) })
export const CdcDivider = defineComponent({ setup: (_, { slots }) => () => h('div', { class: 'form-section-title' }, slots.default?.()) })
export const CdcTableColumn = defineComponent({ setup: () => () => null })
export const CdcTable = defineComponent({
  props: { data: { type: Array, default: () => [] }, maxHeight: [String, Number], height: [String, Number] }, emits: ['row-click'],
  setup: (props, { slots, emit }) => () => {
    const columns = nodes(slots.default?.()).filter(n => n.type === CdcTableColumn)
    return h('div', { class: 'table-wrap', style: { maxHeight: props.maxHeight ? `${props.maxHeight}px` : undefined } }, h('table', [
      h('thead', h('tr', columns.map(n => h('th', { style: { width: n.props?.width ? `${n.props.width}px` : undefined, minWidth: n.props?.['min-width'] ? `${n.props['min-width']}px` : undefined } }, n.props?.label)))),
      h('tbody', props.data.length ? props.data.map((row, index) => h('tr', { key: row.id ?? index, onClick: () => emit('row-click', row) }, columns.map(n => h('td', n.children?.default ? n.children.default({ row, $index: index }) : row[n.props?.prop] ?? '-')))) : h('tr', h('td', { colspan: columns.length || 1 }, slots.empty?.() || h(CdcEmpty))))
    ]))
  }
})
export const CdcPagination = defineComponent({ props: { currentPage: { type: Number, default: 1 }, total: Number, pageSize: { type: Number, default: 20 } }, emits: ['update:currentPage', 'current-change'], setup(props, { emit }) { const change = page => { emit('update:currentPage', page); emit('current-change', page) }; return () => h('div', { class: 'pagination' }, [h('span', { class: 'dim' }, `共 ${props.total || 0} 条`), h('button', { class: 'icon-btn', disabled: props.currentPage <= 1, 'aria-label': '上一页', onClick: () => change(props.currentPage - 1) }, h(ChevronLeft, { size: 14 })), h('span', `${props.currentPage} / ${Math.max(1, Math.ceil((props.total || 0) / props.pageSize))}`), h('button', { class: 'icon-btn', disabled: props.currentPage * props.pageSize >= (props.total || 0), 'aria-label': '下一页', onClick: () => change(props.currentPage + 1) }, h(ChevronRight, { size: 14 }))]) } })
export const CdcCollapse = defineComponent({ setup: (_, { slots }) => () => h('div', { class: 'accordion' }, slots.default?.()) })
export const CdcCollapseItem = defineComponent({ props: { title: String }, setup(props, { slots }) { const open = ref(false); return () => h('section', { class: 'acc' }, [h('button', { class: 'acc-head', type: 'button', 'aria-expanded': open.value, onClick: () => open.value = !open.value }, [h(ChevronRight, { size: 12, style: { transform: open.value ? 'rotate(90deg)' : '' } }), slots.title?.() || props.title]), open.value && h('div', { class: 'acc-body' }, slots.default?.())]) } })
export const CdcStep = defineComponent({ setup: () => () => null })
export const CdcSteps = defineComponent({ props: { active: Number }, setup: (props, { slots }) => () => h('ol', { class: 'steps' }, nodes(slots.default?.()).map((node, index) => h('li', { class: [index === props.active && 'active', index < props.active && 'done'], 'aria-current': index === props.active ? 'step' : undefined }, [h('span', { class: 'step-number' }, String(index + 1)), h('span', node.props?.title)]))) })
export const CdcTabPane = defineComponent({ setup: (_, { slots }) => () => slots.default?.() })
export const CdcTabs = defineComponent({ props: { modelValue: String }, emits: ['update:modelValue', 'tab-change'], setup: (props, { slots, emit }) => () => { const panes = nodes(slots.default?.()).filter(n => n.type === CdcTabPane); const current = props.modelValue || panes[0]?.props?.name; return h('div', { class: 'tabs' }, [h('div', { class: 'tab-list', role: 'tablist' }, panes.map(n => h('button', { type: 'button', role: 'tab', 'aria-selected': current === n.props?.name, class: current === n.props?.name && 'active', onClick: () => { emit('update:modelValue', n.props.name); emit('tab-change', n.props.name) } }, n.props?.label))), h('div', { role: 'tabpanel' }, panes.find(n => n.props?.name === current)?.children?.default?.())]) } })
export const CdcDatePicker = defineComponent({ props: modelProps, emits: updateEvents, setup(props, { emit }) { const format = date => { if (!date) return ''; const d = new Date(date); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}T${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}` }; return () => h('div', { class: 'date-range' }, [0,1].map(index => h('input', { type: 'datetime-local', 'aria-label': index ? '结束时间' : '开始时间', value: format(props.modelValue?.[index]), onChange: e => { const range = [...(props.modelValue || [])]; range[index] = e.target.value ? new Date(e.target.value) : null; control(emit, range) } }))) } })

export const CdcMenu = defineComponent({
  setup(_, { slots }) {
    const open = ref(false), root = ref(), popup = ref(), position = ref({ visibility: 'hidden' })
    function close(restoreFocus = false) { open.value = false; if (restoreFocus) root.value?.querySelector('.menu-trigger').focus() }
    function place() {
      if (!root.value || !popup.value) return
      const trigger = root.value.getBoundingClientRect(), menu = popup.value.getBoundingClientRect(), margin = 8
      const below = innerHeight - trigger.bottom - margin
      const height = Math.min(menu.height, innerHeight - margin * 2)
      const top = menu.height > below && trigger.top > below ? trigger.top - height - 4 : trigger.bottom + 4
      position.value = { position: 'fixed', left: `${Math.max(margin,Math.min(trigger.right-menu.width,innerWidth-menu.width-margin))}px`, top: `${Math.max(margin,Math.min(top,innerHeight-height-margin))}px`, right: 'auto', zIndex: 400, maxHeight: `${innerHeight-margin*2}px`, overflowY: 'auto', visibility: 'visible' }
    }
    watch(open, async value => { if (value) { position.value = { position:'fixed',visibility:'hidden' }; await nextTick(); place() } })
    function outside(event) { if (!root.value?.contains(event.target) && !popup.value?.contains(event.target)) close() }
    function scroll(event) { if (!popup.value?.contains(event.target)) close() }
    function focusOut(event) { if (!root.value?.contains(event.relatedTarget) && !popup.value?.contains(event.relatedTarget)) close() }
    function keydown(event) {
      if (event.key === 'Escape') { event.stopPropagation(); close(true) }
      if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) {
        event.preventDefault(); open.value = true
        nextTick(() => { const buttons=Array.from(popup.value?.querySelectorAll('button:not(:disabled)')||[]),index=buttons.indexOf(document.activeElement); const next=event.key==='Home'?0:event.key==='End'?buttons.length-1:index<0?(event.key==='ArrowUp'?buttons.length-1:0):(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length; buttons[next]?.focus() })
      }
    }
    onMounted(() => { document.addEventListener('pointerdown',outside); window.addEventListener('scroll',scroll,true); window.addEventListener('resize',place) })
    onBeforeUnmount(() => { document.removeEventListener('pointerdown',outside); window.removeEventListener('scroll',scroll,true); window.removeEventListener('resize',place) })
    return () => h('div', { class: 'menu-wrap', ref: root, onFocusout: focusOut, onKeydown: keydown }, [
      h('button', { type:'button',class:'icon-btn menu-trigger','aria-label':'更多操作','aria-expanded':open.value,'aria-haspopup':'menu',onClick:()=>open.value=!open.value }, '⋯'),
      open.value && h(Teleport,{to:'body'},h('div',{ref:popup,class:'menu show',role:'menu',style:position.value,onFocusout:focusOut,onKeydown:keydown,onClick:event=>{if(event.target.closest('button:not(:disabled)'))close(true)}},slots.default?.()))
    ])
  }
})

export const components = { CdcButton, CdcModal, CdcInput, CdcInputNumber, CdcOption, CdcSelect, CdcForm, CdcFormItem, CdcCheckbox, CdcSwitch, CdcRadioGroup, CdcRadioButton, CdcTag, CdcAlert, CdcEmpty, CdcDivider, CdcTable, CdcTableColumn, CdcPagination, CdcCollapse, CdcCollapseItem, CdcStep, CdcSteps, CdcTabPane, CdcTabs, CdcDatePicker, CdcMenu }
export function installUi(app) {
  Object.entries(components).forEach(([name, component]) => app.component(name, component))
  app.directive('loading', { mounted: setLoading, updated: setLoading })
}
function setLoading(el, binding) { el.classList.toggle('is-loading', !!binding.value); el.setAttribute('aria-busy', String(!!binding.value)) }
