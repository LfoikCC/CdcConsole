<script setup>
/**
 * 选择筛选器的取值控件：候选值来自数据集的 distinct 值。
 *
 * <p>面板参照观远的选择筛选器：搜索 + 全选 / 排除 / 已选计数 + 列表 + 清空/完成。
 * 「排除」原来放在筛选栏上，挤掉了下拉本身的宽度，字也比栏里大一号，看着不协调，
 * 现在收进面板，字号统一到 12px。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronDown, Search } from 'lucide-vue-next'
import { api } from '../../api'

const props = defineProps({
  datasetId: { type: [Number, String], default: null },
  column: { type: String, default: '' },
  multiple: { type: Boolean, default: true },
  modelValue: { type: Array, default: () => [] },
  exclude: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'update:exclude'])

const open = ref(false)
const query = ref('')
const panel = ref(null)
const trigger = ref(null)
const pos = ref({ left: -9999, top: -9999 })
const options = ref([])
const truncated = ref(false)
const loading = ref(false)

async function load() {
  options.value = []
  truncated.value = false
  if (props.datasetId == null || !props.column) return
  loading.value = true
  try {
    const result = await api(`/bi/datasets/${props.datasetId}/values`
      + `?column=${encodeURIComponent(props.column)}&limit=200`)
    options.value = result.values || []
    truncated.value = !!result.truncated
  } catch {
    options.value = []
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const word = query.value.trim().toLowerCase()
  return word ? options.value.filter(item => item.toLowerCase().includes(word)) : options.value
})

// 单选只认一个值：从多选切过来时 modelValue 里可能还留着好几个
const selected = computed(() => {
  const values = (props.modelValue || []).map(String)
  return props.multiple ? values : values.slice(0, 1)
})

/** 触发器上的文案：没选就是「全部」，选多了显示前两项 + 数量。 */
const label = computed(() => {
  const values = selected.value
  if (!values.length) return '全部'
  const head = values.slice(0, 2).join('、')
  return values.length > 2 ? `${head} 等 ${values.length} 项` : head
})

const allChecked = computed(() =>
  filtered.value.length > 0 && filtered.value.every(item => selected.value.includes(item)))

/** 单选：选中即生效、立刻收起（多选才需要留在面板里连续勾）。 */
function pick(value) {
  emit('update:modelValue', value ? [value] : [])
  open.value = false
}

function toggleValue(value) {
  const values = selected.value
  const next = values.includes(value) ? values.filter(item => item !== value) : [...values, value]
  emit('update:modelValue', next)
}

/** 全选只作用于当前搜索结果，不然搜索完点全选会把看不见的也勾上。 */
function toggleAll() {
  if (allChecked.value) {
    const visible = new Set(filtered.value)
    emit('update:modelValue', selected.value.filter(item => !visible.has(item)))
    return
  }
  const next = new Set(selected.value)
  filtered.value.forEach(item => next.add(item))
  emit('update:modelValue', [...next])
}

function clear() {
  emit('update:modelValue', [])
}

function toggleOpen() {
  if (open.value) {
    open.value = false
    return
  }
  query.value = ''
  open.value = true
  nextTick(() => {
    const el = panel.value
    const anchor = trigger.value
    if (!el || !anchor) return
    const box = anchor.getBoundingClientRect()
    const edge = 8
    const width = el.offsetWidth || 240
    const height = el.offsetHeight || 260
    const left = Math.max(edge, Math.min(box.left, window.innerWidth - width - edge))
    const below = box.bottom + 4
    const top = below + height <= window.innerHeight - edge
      ? below
      : Math.max(edge, box.top - height - 4)
    pos.value = { left, top }
  })
}

function onPointerDown(event) {
  if (!open.value) return
  if (event.target?.closest?.('.bi-fv-panel, .bi-fv-trigger')) return
  open.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown)
  document.removeEventListener('keydown', onKeydown)
})

// 换字段就重新拉候选值
watch(() => [props.datasetId, props.column], load, { immediate: true })
</script>

<template>
  <button ref="trigger" type="button" class="bi-fv-trigger" :class="{ 'is-open': open }"
          :aria-expanded="open" :title="label" @click="toggleOpen">
    <span>{{ label }}</span>
    <!-- 单选是「输入框 + 放大镜」的形态，多选是下拉箭头 -->
    <Search v-if="!multiple" :size="11"/>
    <ChevronDown v-else :size="11"/>
  </button>

  <Teleport to="body">
    <div v-if="open" ref="panel" class="bi-fv-panel" :class="{ 'is-single': !multiple }"
         :style="{ left: `${pos.left}px`, top: `${pos.top}px` }">
      <div class="bi-fv-search">
        <Search :size="12"/>
        <input v-model="query" placeholder="搜索" aria-label="搜索取值">
      </div>

      <div v-if="multiple" class="bi-fv-tools">
        <label class="bi-fv-check">
          <input type="checkbox" :checked="allChecked" :disabled="!filtered.length" @change="toggleAll">
          <span>全选</span>
        </label>
        <label class="bi-fv-check">
          <input type="checkbox" :checked="exclude" @change="emit('update:exclude', !exclude)">
          <span>排除</span>
        </label>
        <span class="bi-fv-count">已选 {{ selected.length }}</span>
      </div>

      <div class="bi-fv-list">
        <!-- 单选：第一项「全部」＝不过滤，选中项浅底高亮 -->
        <button v-if="!multiple" type="button" class="bi-fv-item is-single"
                :class="{ 'is-checked': !selected.length }" @click="pick('')">
          <span>全部</span>
        </button>
        <template v-for="value in filtered" :key="value">
          <label v-if="multiple" class="bi-fv-item" :class="{ 'is-checked': selected.includes(value) }">
            <input type="checkbox" :checked="selected.includes(value)" @change="toggleValue(value)">
            <span>{{ value }}</span>
          </label>
          <button v-else type="button" class="bi-fv-item is-single"
                  :class="{ 'is-checked': selected.includes(value) }" @click="pick(value)">
            <span>{{ value }}</span>
          </button>
        </template>
        <p v-if="!filtered.length" class="bi-fv-empty">
          {{ loading ? '加载中…' : (options.length ? '没有匹配的取值' : '暂无可选值') }}
        </p>
        <p v-else-if="truncated" class="bi-fv-empty">候选值较多，只列出前 200 个。</p>
      </div>

      <div v-if="multiple" class="bi-fv-foot">
        <button type="button" class="btn btn-ghost btn-sm" @click="clear">清空</button>
        <span style="flex:1"/>
        <button type="button" class="btn btn-primary btn-sm" @click="open = false">完成</button>
      </div>
      <div v-else class="bi-fv-foot is-single">
        <label class="bi-fv-check">
          <input type="checkbox" :checked="exclude" @change="emit('update:exclude', !exclude)">
          <span>排除</span>
        </label>
        <span style="flex:1"/>
        <button type="button" class="btn btn-ghost btn-sm" @click="open = false">关闭</button>
      </div>
    </div>
  </Teleport>
</template>
