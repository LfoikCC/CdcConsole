<script setup>
/**
 * 新建 / 编辑看板筛选器：类型 → 属性配置 → 联动，三步。
 *
 * <p>结构参考观远 BI 的「新建筛选器」，但类型只保留我们查询层能落地的几种。
 */
import { computed, ref, watch } from 'vue'
import {
  CheckSquare, ChevronLeft, CornerDownRight, Crosshair, GitBranch, Type, CalendarDays
} from 'lucide-vue-next'
import { api } from '../../api'
import FilterValues from './FilterValues.vue'
import {
  CONDITION_OPS, FILTER_SCOPES, FILTER_TYPES, QUICK_RANGES, RANGE_OPS,
  blankFilter, filterName, quickRange
} from './biFilters'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 传进来就是编辑，null 就是新建。 */
  filter: { type: Object, default: null },
  datasets: { type: Array, default: () => [] },
  cards: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'submit'])

const step = ref(0)
const draft = ref(blankFilter())
const columns = ref([])
const loading = ref(false)

const ICONS = {
  select: CheckSquare,
  condition: Type,
  range: Crosshair,
  date: CalendarDays,
  combo: GitBranch
}

const open = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})

/** 只能筛看板里真正用到的数据集，选别的没有意义。 */
const usableDatasets = computed(() => {
  const used = new Set(props.cards.map(card => card.datasetId).filter(id => id != null))
  return props.datasets.filter(item => used.has(item.id))
})

/** 同数据集的卡片：联动那一步要在这里挑。 */
const siblingCards = computed(() =>
  props.cards.filter(card => Number(card.datasetId) === Number(draft.value.datasetId)))

const NUMERIC = /INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL|NUMBER/i
const TEMPORAL = /DATE|TIME/i

function columnKind(dataType) {
  if (TEMPORAL.test(String(dataType || ''))) return 'date'
  if (NUMERIC.test(String(dataType || ''))) return 'number'
  return 'text'
}

/** 范围 / 日期筛选器优先列出同类型字段，省得在下拉里翻。 */
const fieldOptions = computed(() => {
  const list = columns.value.map(column => ({ ...column, kind: columnKind(column.dataType) }))
  const wanted = draft.value.type === 'range' ? 'number' : (draft.value.type === 'date' ? 'date' : null)
  if (!wanted) return list
  return [...list.filter(item => item.kind === wanted), ...list.filter(item => item.kind !== wanted)]
})

const currentType = computed(() => FILTER_TYPES.find(item => item.value === draft.value.type))

function reset() {
  step.value = 0
  draft.value = props.filter ? { ...blankFilter(props.filter.type), ...props.filter } : blankFilter()
  if (props.filter) {
    // 编辑已有筛选器：跳过类型选择，直接到属性配置
    step.value = 1
    loadColumns(props.filter.datasetId)
  } else {
    columns.value = []
  }
}

watch(() => props.modelValue, value => {
  if (value) reset()
}, { immediate: true })

async function loadColumns(datasetId) {
  columns.value = []
  if (datasetId == null) return
  loading.value = true
  try {
    const detail = await api(`/datasets/${datasetId}`)
    columns.value = detail.columns || []
  } catch {
    columns.value = []
  } finally {
    loading.value = false
  }
}

function chooseType(type) {
  const next = blankFilter(type)
  next.datasetId = draft.value.datasetId
  draft.value = next
  step.value = 1
}

function setDataset(datasetId) {
  draft.value.datasetId = datasetId
  draft.value.column = ''
  draft.value.name = ''
  draft.value.values = []
  draft.value.conditions = []
  loadColumns(datasetId)
}

function setColumn(column) {
  draft.value.column = column
  // 没手动改过名字就跟着字段走
  if (!draft.value.name || draft.value.name === draft.value.column) draft.value.name = column
}

/** 快捷日期：点一下就把起止算出来，仍然可以再手改。 */
function applyQuick(value) {
  draft.value.quick = value
  const [from, to] = quickRange(value)
  draft.value.from = from
  draft.value.to = to
}

function addCondition() {
  draft.value.conditions = [...(draft.value.conditions || []), { column: '', op: 'CONTAINS', value: '' }]
}

function removeCondition(index) {
  draft.value.conditions = draft.value.conditions.filter((_, i) => i !== index)
}

function toggleCard(cardId) {
  const ids = draft.value.cardIds || []
  draft.value.cardIds = ids.some(id => Number(id) === Number(cardId))
    ? ids.filter(id => Number(id) !== Number(cardId))
    : [...ids, cardId]
}

const canNext = computed(() => {
  const draftValue = draft.value
  if (step.value === 1) {
    if (draftValue.datasetId == null) return false
    if (draftValue.type === 'combo') return true
    return !!draftValue.column
  }
  return true
})

function next() {
  if (step.value === 0) {
    if (!draft.value.type) return
    step.value = 1
    return
  }
  step.value = Math.min(2, step.value + 1)
}

function back() {
  step.value = Math.max(0, step.value - 1)
}

function submit() {
  const result = { ...draft.value }
  if (!result.name) result.name = filterName(result)
  // 单选只留一个值，否则界面上看着选了一个、查询却按好几个过滤
  if (result.type === 'select' && !result.multiple) {
    result.values = (result.values || []).slice(0, 1)
  }
  emit('submit', result)
  open.value = false
}
</script>

<template>
  <cdc-modal v-model="open" :title="filter ? '编辑筛选器' : '新建筛选器'" width="720px">
    <cdc-steps :active="step">
      <cdc-step title="类型"/>
      <cdc-step title="属性配置"/>
      <cdc-step title="联动"/>
    </cdc-steps>

    <!-- 第一步：类型，参照观远的图标宫格 -->
    <div v-if="step === 0" class="bi-filter-types">
      <button v-for="item in FILTER_TYPES" :key="item.value" type="button"
              class="bi-filter-type" :class="{ active: draft.type === item.value }"
              @click="chooseType(item.value)">
        <component :is="ICONS[item.value]" :size="22"/>
        <span>{{ item.label }}</span>
      </button>
      <p class="bi-filter-type-hint">{{ currentType?.hint }}</p>
    </div>

    <!-- 第二步：属性配置 -->
    <div v-else-if="step === 1" class="bi-filter-form">
      <div class="bi-filter-row">
        <span class="bi-filter-label">数据集</span>
        <cdc-select :model-value="draft.datasetId" style="flex:1" placeholder="选择数据集"
                    @update:model-value="setDataset">
          <cdc-option v-for="item in usableDatasets" :key="item.id" :label="item.name" :value="item.id"/>
        </cdc-select>
      </div>

      <div v-if="draft.type !== 'combo'" class="bi-filter-row">
        <span class="bi-filter-label">字段</span>
        <cdc-select :model-value="draft.column" filterable style="flex:1"
                    :placeholder="loading ? '加载字段…' : '选择字段'"
                    @update:model-value="setColumn">
          <cdc-option v-for="column in fieldOptions" :key="column.name"
                      :label="`${column.name}  ·  ${column.dataType}`" :value="column.name"/>
        </cdc-select>
      </div>

      <!-- 选择：单选/多选 + 排除 + 取值 -->
      <template v-if="draft.type === 'select'">
        <div class="bi-filter-row">
          <span class="bi-filter-label">选择类型</span>
          <cdc-radio-group :model-value="draft.multiple ? 'multi' : 'single'"
                           @update:model-value="value => (draft.multiple = value === 'multi')">
            <cdc-radio-button value="single">单选</cdc-radio-button>
            <cdc-radio-button value="multi">多选</cdc-radio-button>
          </cdc-radio-group>
          <span class="bi-filter-inline">
            <cdc-checkbox v-model="draft.exclude">排除所选</cdc-checkbox>
          </span>
        </div>
        <div class="bi-filter-row">
          <span class="bi-filter-label">取值</span>
          <div style="flex:1">
            <FilterValues :dataset-id="draft.datasetId" :column="draft.column" :multiple="draft.multiple"
                          :model-value="draft.values" @update:model-value="value => (draft.values = value)"/>
            <div class="form-help">不选表示不过滤；最多展示 200 个候选值。</div>
          </div>
        </div>
      </template>

      <!-- 条件：匹配方式 + 关键词 -->
      <template v-else-if="draft.type === 'condition'">
        <div class="bi-filter-row">
          <span class="bi-filter-label">匹配方式</span>
          <cdc-select v-model="draft.op" style="flex:1">
            <cdc-option v-for="item in CONDITION_OPS" :key="item.value" :label="item.label" :value="item.value"/>
          </cdc-select>
        </div>
        <div class="bi-filter-row">
          <span class="bi-filter-label">关键词</span>
          <cdc-input v-model="draft.keyword" placeholder="留空则不过滤" style="flex:1"/>
        </div>
      </template>

      <!-- 范围：比较方式 + 起止 -->
      <template v-else-if="draft.type === 'range'">
        <div class="bi-filter-row">
          <span class="bi-filter-label">比较方式</span>
          <cdc-select v-model="draft.valueOp" style="flex:1">
            <cdc-option v-for="item in RANGE_OPS" :key="item.value" :label="item.label" :value="item.value"/>
          </cdc-select>
        </div>
        <div v-if="draft.valueOp === 'BETWEEN'" class="bi-filter-row">
          <span class="bi-filter-label">区间</span>
          <input v-model="draft.from" class="input-control" type="number" aria-label="起始值" placeholder="起始值">
          <span class="bi-filter-tilde">~</span>
          <input v-model="draft.to" class="input-control" type="number" aria-label="结束值" placeholder="结束值">
        </div>
        <div v-else-if="draft.valueOp !== 'IS NULL' && draft.valueOp !== 'IS NOT NULL'" class="bi-filter-row">
          <span class="bi-filter-label">数值</span>
          <input v-model="draft.from" class="input-control" type="number" aria-label="数值" placeholder="输入数值">
        </div>
      </template>

      <!-- 日期：快捷 + 自定义区间 -->
      <template v-else-if="draft.type === 'date'">
        <div class="bi-filter-row">
          <span class="bi-filter-label">快捷</span>
          <div class="bi-quick-ranges">
            <button v-for="item in QUICK_RANGES" :key="item.value" type="button"
                    class="bi-quick-chip" :class="{ active: draft.quick === item.value }"
                    @click="applyQuick(item.value)">{{ item.label }}</button>
          </div>
        </div>
        <div class="bi-filter-row">
          <span class="bi-filter-label">区间</span>
          <input v-model="draft.from" class="input-control" type="date" aria-label="开始日期">
          <span class="bi-filter-tilde">~</span>
          <input v-model="draft.to" class="input-control" type="date" aria-label="结束日期">
        </div>
      </template>

      <!-- 组合条件：多行，全部且 -->
      <template v-else-if="draft.type === 'combo'">
        <div class="bi-filter-row">
          <span class="bi-filter-label">条件</span>
          <div class="bi-combo-list">
            <div v-for="(item, index) in draft.conditions" :key="index" class="bi-combo-row">
              <cdc-select :model-value="item.column" filterable placeholder="字段"
                          @update:model-value="value => (item.column = value)">
                <cdc-option v-for="column in fieldOptions" :key="column.name"
                            :label="column.name" :value="column.name"/>
              </cdc-select>
              <cdc-select :model-value="item.op" placeholder="条件"
                          @update:model-value="value => (item.op = value)">
                <cdc-option v-for="op in CONDITION_OPS" :key="op.value" :label="op.label" :value="op.value"/>
              </cdc-select>
              <cdc-input v-model="item.value" placeholder="值"/>
              <button type="button" class="bi-filter-x" aria-label="移除条件" @click="removeCondition(index)">
                <CornerDownRight :size="12"/>
              </button>
            </div>
            <button type="button" class="btn btn-ghost btn-sm" @click="addCondition">+ 添加条件</button>
            <div class="form-help">多个条件之间是「且」的关系。</div>
          </div>
        </div>
      </template>

      <div class="bi-filter-row">
        <span class="bi-filter-label">名称</span>
        <cdc-input v-model="draft.name" :placeholder="draft.column || '筛选器名称'" style="flex:1"/>
      </div>
    </div>

    <!-- 第三步：联动 -->
    <div v-else class="bi-filter-form">
      <div class="bi-filter-row">
        <span class="bi-filter-label">作用范围</span>
        <cdc-radio-group v-model="draft.scope">
          <cdc-radio-button v-for="item in FILTER_SCOPES" :key="item.value" :value="item.value">
            {{ item.label }}
          </cdc-radio-button>
        </cdc-radio-group>
      </div>

      <div v-if="draft.scope === 'cards'" class="bi-filter-row">
        <span class="bi-filter-label">卡片</span>
        <div class="bi-filter-cards">
          <label v-for="card in siblingCards" :key="card._k || card.id" class="bi-filter-card">
            <input type="checkbox" :checked="(draft.cardIds || []).some(id => Number(id) === Number(card.id))"
                   @change="toggleCard(card.id)">
            <span>{{ card.title || card.type }}</span>
          </label>
          <p v-if="!siblingCards.length" class="form-help">这个数据集还没有别的卡片。</p>
        </div>
      </div>

      <p class="form-help">
        筛选器只作用于<strong>同数据集</strong>的卡片：换数据集不会影响别人的看板。
      </p>
    </div>

    <template #footer>
      <button v-if="step > 0" class="btn btn-ghost" @click="back">
        <ChevronLeft :size="13"/>上一步
      </button>
      <span style="flex:1"/>
      <button class="btn btn-ghost" @click="open = false">取消</button>
      <button v-if="step < 2" class="btn btn-primary" :disabled="!canNext" @click="next">下一步</button>
      <button v-else class="btn btn-primary" :disabled="!draft.datasetId" @click="submit">完成</button>
    </template>
  </cdc-modal>
</template>
