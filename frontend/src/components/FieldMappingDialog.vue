<script setup>
import { ref, watch } from 'vue'
import { mapByName, mapByOrder } from '../mapping'

const props = defineProps({ modelValue: Boolean, sourceColumns: { type: Array, default: () => [] }, targetColumns: { type: Array, default: () => [] }, mapping: { type: Object, default: () => ({}) }, title: String })
const emit = defineEmits(['update:modelValue', 'apply'])
const working = ref({})
watch(() => props.modelValue, open => { if (open) working.value = { ...props.mapping } })
function sameName() { working.value = mapByName(props.sourceColumns, props.targetColumns) }
function byOrder() { working.value = mapByOrder(props.sourceColumns, props.targetColumns) }
function reset() { working.value = {} }
function apply() { emit('apply', { ...working.value }); emit('update:modelValue', false) }
</script>

<template>
  <cdc-modal :model-value="modelValue" width="min(820px,94vw)" :title="title || '字段映射'" append-to-body @close="emit('update:modelValue', false)">
    <div class="mapping-toolbar">
      <div class="row-actions"><cdc-button size="small" @click="sameName">同名映射</cdc-button><cdc-button size="small" @click="byOrder">顺序映射</cdc-button></div>
      <cdc-button size="small" text type="danger" @click="reset">重置映射</cdc-button>
    </div>
    <div class="mapping-head"><strong>源表字段</strong><strong>目标表字段</strong></div>
    <div class="mapping-rows">
      <div v-for="source in sourceColumns" :key="source" class="mapping-row">
        <span class="mono">{{ source }}</span><span class="mapping-arrow">→</span>
        <cdc-select v-model="working[source]" clearable filterable placeholder="不映射">
          <cdc-option v-for="target in targetColumns" :key="target" :label="target" :value="target" :disabled="Object.entries(working).some(([s,t]) => s !== source && t === target)"/>
        </cdc-select>
      </div>
    </div>
    <template #footer><cdc-button @click="emit('update:modelValue', false)">取消</cdc-button><cdc-button type="primary" @click="apply">应用映射</cdc-button></template>
  </cdc-modal>
</template>

