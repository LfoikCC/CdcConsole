<script setup>
import { computed } from 'vue'
import { CARD_LABELS, cardMeta } from './biCards'
import BiCardView from './BiCardView.vue'

const props = defineProps({
  card: { type: Object, required: true },
  // 不能叫 style：Vue 会把同名的 prop 吃掉，根元素的 inline style 就没了
  rect: { type: Object, default: () => ({}) },
  selected: { type: Boolean, default: false },
  dragging: { type: Boolean, default: false },
  editable: { type: Boolean, default: false },
  data: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'drag-start', 'resize-start', 'open'])

// 没起名就用类型名：只读页如果标题为空、标题栏又被藏起来，卡片就会变成一块没有标识的白板
const title = computed(() => (props.card.title || '').trim() || CARD_LABELS[props.card.type] || '卡片')
const icon = computed(() => cardMeta(props.card.type).icon)

function onHeaderPointerDown(event) {
  // 标题栏的 pointerdown 用了 .stop（否则会触发根节点上的选中），所以这里自己发一次
  emit('select')
  if (!props.editable || event.button !== 0) return
  emit('drag-start', event)
}
</script>

<template>
  <!-- 双击只挂在标题栏（也就是能拖的那块）：卡片正文里有分页、表格滚动，
       在正文里连点两下会被当成「打开编辑器」，点「下一页」也会误触 -->
  <div class="bi-card" :class="{ 'is-selected': selected, 'is-dragging': dragging, 'is-editable': editable }" :style="rect"
       @pointerdown="emit('select')">
    <div class="bi-card-head" title="拖动调整位置，双击编辑" @pointerdown.stop="onHeaderPointerDown"
         @dblclick.stop="emit('open')">
      <component :is="icon" :size="13" class="bi-card-icon"/>
      <span class="bi-card-title">{{ title }}</span>
    </div>
    <div class="bi-card-body">
      <BiCardView :card="card" :data="data" :loading="loading"/>
    </div>
    <template v-if="editable">
      <span class="bi-card-resize e" @pointerdown.stop.prevent="emit('resize-start', $event, 'e')"/>
      <span class="bi-card-resize s" @pointerdown.stop.prevent="emit('resize-start', $event, 's')"/>
      <span class="bi-card-resize se" @pointerdown.stop.prevent="emit('resize-start', $event, 'se')"/>
    </template>
  </div>
</template>
