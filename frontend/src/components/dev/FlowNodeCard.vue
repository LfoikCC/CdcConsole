<script setup>
import { computed } from 'vue'
import { iconOf, metaOf } from './nodeTypes'
import { NODE_H, NODE_W } from './flowGraph'

const props = defineProps({
  node: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  target: { type: Boolean, default: false }
})

const emit = defineEmits(['drag-start', 'select', 'open', 'connect-start'])

const meta = computed(() => metaOf(props.node.type))
const icon = computed(() => iconOf(props.node))
const tone = computed(() => meta.value.tone)
const subtitle = computed(() => meta.value.subtitle(props.node.config || {}))
</script>

<template>
  <div class="fnode" :class="[`fnode-${tone}`, { 'is-selected': selected, 'is-target': target }]"
       :data-node-id="node.id"
       :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${NODE_W}px`, height: `${NODE_H}px` }"
       tabindex="0" role="button"
       :aria-label="`${meta.label} ${node.name || node.id}`"
       @pointerdown.stop="emit('drag-start', $event)"
       @click.stop="emit('select', node.id)"
       @dblclick.stop="emit('open', node.id)"
       @keydown.enter.prevent="emit('open', node.id)">
    <div class="fnode-head">
      <span class="fnode-icon"><component :is="icon" :size="14"/></span>
      <span class="fnode-title">{{ node.name || meta.label }}</span>
    </div>
    <div class="fnode-sub">{{ subtitle }}</div>
    <span class="fnode-port in" aria-hidden="true"/>
    <span class="fnode-port out" aria-hidden="true"
          @pointerdown.stop.prevent="emit('connect-start', $event)"/>
  </div>
</template>
