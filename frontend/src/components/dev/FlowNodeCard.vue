<script setup>
import { computed } from 'vue'
import { acceptsInput, canBeUpstream, iconOf, metaOf, needsTwoInputs } from './nodeTypes'
import { NODE_H, NODE_W } from './flowGraph'

const props = defineProps({
  node: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  target: { type: Boolean, default: false }
})

const emit = defineEmits(['drag-start', 'select', 'open', 'connect-start'])

const meta = computed(() => metaOf(props.node.type))
const icon = computed(() => iconOf(props.node))
const twoInputs = computed(() => needsTwoInputs(props.node))
// 输入类节点（数据库/SQL/数据集输入）没有上游，画入端口只会让人以为能连进去
const hasInput = computed(() => acceptsInput(props.node))
// 输出类节点是终点，画右位点会让人以为还能往下接
const hasOutput = computed(() => canBeUpstream(props.node))
const tone = computed(() => meta.value.tone)
const subtitle = computed(() => meta.value.subtitle(props.node.config || {}))
const tooltip = computed(() => {
  const name = props.node.name || meta.value.label
  return subtitle.value ? `${name} · ${subtitle.value}` : name
})
</script>

<template>
  <div class="fnode" :class="[`fnode-${tone}`, { 'is-selected': selected, 'is-target': target, 'is-two-input': twoInputs }]"
       :data-node-id="node.id"
       :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${NODE_W}px`, height: `${NODE_H}px` }"
       :title="tooltip"
       tabindex="0" role="button"
       :aria-label="`${meta.label} ${node.name || node.id}`"
       @pointerdown.stop="emit('drag-start', $event)"
       @click.stop="emit('select', node.id)"
       @dblclick.stop="emit('open', node.id)"
       @keydown.enter.prevent="emit('open', node.id)">
    <span class="fnode-icon"><component :is="icon" :size="22"/></span>
    <span class="fnode-title">{{ node.name || meta.label }}</span>
    <span v-if="hasInput" class="fnode-port in" aria-hidden="true"/>
    <span v-if="hasInput && twoInputs" class="fnode-port in lower" aria-hidden="true"/>
    <span v-if="hasOutput" class="fnode-port out" aria-hidden="true"
          @pointerdown.stop.prevent="emit('connect-start', $event)"/>
  </div>
</template>
