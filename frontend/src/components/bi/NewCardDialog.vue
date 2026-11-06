<script setup>
/**
 * 新建卡片：左边挑数据集（按最近修改倒序），右边看这个数据集的字段与统计。
 *
 * <p>列表里的图标按来源区分：库表 / 自定义 SQL 显示数据账户的品牌 LOGO
 * （MySQL、StarRocks…），Excel 导入显示 Excel 图标。
 *
 * <p>左侧同时只能看两块：本页卡片已经在用的数据集（默认）和全部数据集。
 */
import { computed, ref, watch } from 'vue'
import { Calendar, Database, Hash, Search, ToggleLeft, Type } from 'lucide-vue-next'
import DatabaseBrand from '../DatabaseBrand.vue'
import { api } from '../../api'
import { DATASET_LABELS } from './biCards'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  datasets: { type: Array, default: () => [] },
  /** 本页卡片已经在用的数据集 id：新建时默认只看这些。 */
  usedDatasetIds: { type: Array, default: () => [] },
  defaultType: { type: String, default: 'bar' }
})

const emit = defineEmits(['update:modelValue', 'create'])

const title = ref('')
const datasetId = ref(null)
const detail = ref(null)
const stats = ref(null)
const loading = ref(false)
const accounts = ref([])
const keyword = ref('')
/** 列表范围：page=本页在用的数据集，all=全部数据集。 */
const scope = ref('all')

const TYPE_BADGE = { TABLE: '直连库表', SQL: '自定义查询', EXCEL: 'Excel 导入', EXTRACT: '离线数据集' }
const ENGINE_LABELS = {
  MYSQL: 'MySQL', STARROCKS: 'StarRocks', STARROCKS_STREAM_LOAD: 'StarRocks',
  SQLSERVER: 'SQL Server', KAFKA: 'Kafka', EXCEL: 'Excel'
}

const accountTypes = computed(() => new Map(accounts.value.map(item => [Number(item.id), item.accountType])))
const selected = computed(() => props.datasets.find(item => item.id === datasetId.value) || null)

/**
 * 最近改过 / 新建的排最前。
 *
 * <p>时间相同时按 id 倒序（同一次导入会拿到同一秒的时间戳），顺序才稳定。
 */
const sorted = computed(() => [...props.datasets].sort((a, b) => {
  const left = Date.parse(a.updatedAt || a.createdAt || 0) || 0
  const right = Date.parse(b.updatedAt || b.createdAt || 0) || 0
  return right - left || Number(b.id) - Number(a.id)
}))

/** 本页在用的数据集：id 对不上（数据集已被删）的不算。 */
const usedIds = computed(() => {
  const known = new Set(props.datasets.map(item => Number(item.id)))
  const used = new Set()
  for (const id of props.usedDatasetIds) {
    const key = Number(id)
    if (Number.isFinite(key) && known.has(key)) used.add(key)
  }
  return used
})

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return sorted.value.filter(item => {
    const matchScope = scope.value !== 'page' || usedIds.value.has(Number(item.id))
    const matchText = !text || (item.name || '').toLowerCase().includes(text)
    return matchScope && matchText
  })
})

const pageCount = computed(() => usedIds.value.size)
/** 本页一张卡都还没建时不切「页面数据集」，否则列表是空的。 */
const defaultScope = computed(() => (pageCount.value ? 'page' : 'all'))

/** 库表看账户类型，Excel 直接给 Excel 图标 */
function brandOf(item) {
  if (!item) return ''
  if (item.datasetType === 'EXCEL') return 'EXCEL'
  return accountTypes.value.get(Number(item.accountId)) || ''
}

const engineName = computed(() => {
  const brand = brandOf(selected.value)
  return ENGINE_LABELS[brand] || DATASET_LABELS[selected.value?.datasetType] || ''
})

const pathText = computed(() => {
  const item = selected.value
  if (!item) return ''
  if (item.datasetType === 'EXCEL') {
    return ['根目录', 'Excel 文件', detail.value?.file?.name || item.name].filter(Boolean).join(' / ')
  }
  return ['根目录', item.databaseName, item.tableName || '自定义 SQL'].filter(Boolean).join(' / ')
})

const columnCount = computed(() => detail.value?.columns?.length ?? selected.value?.columnCount ?? 0)
const rowCount = computed(() => stats.value?.rows ?? selected.value?.rowCount ?? 0)
const sortedColumns = computed(() => detail.value?.columns || [])

/** 字段类型对应的小图标：文本 Aa、数字 #、日期日历、布尔开关 */
function iconOf(dataType) {
  const type = String(dataType || '').toUpperCase()
  if (/INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|NUMBER|LONG|SHORT|BYTE|REAL/.test(type)) return Hash
  if (/DATE|TIME|YEAR/.test(type)) return Calendar
  if (/BOOL/.test(type)) return ToggleLeft
  return Type
}

watch(() => props.modelValue, async open => {
  if (!open) return
  title.value = ''
  datasetId.value = null
  detail.value = null
  stats.value = null
  keyword.value = ''
  scope.value = defaultScope.value
  if (!accounts.value.length) {
    try {
      const body = await api('/accounts?size=200')
      accounts.value = body.items || []
    } catch {
      accounts.value = []
    }
  }
}, { immediate: true })

watch(datasetId, async id => {
  detail.value = null
  stats.value = null
  if (id == null) return
  loading.value = true
  try {
    detail.value = await api(`/datasets/${id}`)
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
  // 库表数据集的元数据里没有行数，单独算一次
  try {
    stats.value = await api(`/bi/datasets/${id}/stats`)
  } catch {
    stats.value = null
  }
})

function close() {
  emit('update:modelValue', false)
}

function submit() {
  const name = title.value.trim()
  if (!name) return
  if (datasetId.value == null) return
  emit('create', {
    title: name,
    datasetId: datasetId.value,
    type: props.defaultType,
    config: null
  })
}

function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}

const formatCount = value => (Number.isFinite(Number(value)) ? Number(value).toLocaleString('zh-CN') : '—')
</script>

<template>
  <cdc-modal :model-value="modelValue" title="新建卡片" width="720px"
             @update:model-value="emit('update:modelValue', $event)">
    <label class="bi-newcard-title">
      <span>卡片标题</span>
      <input v-model="title" class="input-control" aria-label="卡片标题">
    </label>

    <div class="bi-picker">
      <!-- 左：数据集列表 -->
      <section class="bi-picker-left">
        <div class="bi-picker-tools">
          <div class="bi-picker-search">
            <Search :size="13"/>
            <input v-model="keyword" placeholder="搜索数据集" aria-label="搜索数据集">
          </div>
          <cdc-select v-model="scope" class="bi-picker-type" aria-label="数据集范围">
            <cdc-option value="page" label="页面数据集"/>
            <cdc-option value="all" label="全部"/>
          </cdc-select>
        </div>

        <div class="bi-picker-list" role="listbox" aria-label="数据集">
          <button v-for="item in filtered" :key="item.id" type="button" role="option"
                  class="bi-picker-row" :class="{ 'is-active': item.id === datasetId }"
                  :aria-selected="item.id === datasetId" @click="datasetId = item.id">
            <DatabaseBrand :type="brandOf(item)" class="bi-picker-logo"/>
            <span class="bi-picker-name" :title="item.name">{{ item.name }}</span>
            <span class="bi-pick-badge" :class="'is-' + String(item.datasetType || '').toLowerCase()">
              {{ TYPE_BADGE[item.datasetType] || item.datasetType }}
            </span>
          </button>
          <p v-if="!filtered.length" class="bi-picker-none">
            <template v-if="keyword.trim()">没有匹配的数据集</template>
            <template v-else-if="!datasets.length">还没有数据集，先去数据准备里创建</template>
            <template v-else-if="scope === 'page'">这个看板还没用过数据集，切到「全部」挑选</template>
            <template v-else>还没有数据集</template>
          </p>
        </div>
      </section>

      <!-- 右：字段与统计 -->
      <aside class="bi-picker-right">
        <div v-if="!selected" class="bi-picker-empty">
          <Database :size="26"/>
          <p>选择左侧的数据集，这里会列出它的字段</p>
        </div>
        <template v-else>
          <header class="bi-pick-head">
            <DatabaseBrand :type="brandOf(selected)" class="bi-pick-logo"/>
            <div class="bi-pick-title">
              <h3>{{ selected.name }}</h3>
              <div class="bi-pick-engine">
                <span>{{ engineName }}</span>
                <span class="bi-pick-badge" :class="'is-' + String(selected.datasetType || '').toLowerCase()">
                  {{ TYPE_BADGE[selected.datasetType] || selected.datasetType }}
                </span>
              </div>
            </div>
          </header>

          <div class="bi-pick-path">{{ pathText }}</div>

          <div class="bi-pick-line">
            <span>描述</span>
            <p>{{ selected.description || '暂无描述' }}</p>
          </div>

          <div class="bi-pick-stats">
            <div><span>列数</span><strong>{{ columnCount }}</strong></div>
            <div><span>行数</span><strong>{{ formatCount(rowCount) }}</strong></div>
          </div>

          <div class="bi-pick-line">
            <span>最近数据更新时间</span>
            <p>{{ stamp(selected.updatedAt) }}</p>
          </div>

          <div class="bi-pick-fields">
            <div class="bi-pick-fields-title">字段</div>
            <div v-if="loading" class="form-help">加载字段…</div>
            <div v-else-if="sortedColumns.length" class="bi-pick-field-list">
              <div v-for="column in sortedColumns" :key="column.name" class="bi-pick-field">
                <component :is="iconOf(column.dataType)" :size="12"/>
                <span :title="column.dataType">{{ column.name }}</span>
              </div>
            </div>
            <div v-else class="form-help">这个数据集还没有字段</div>
          </div>
        </template>
      </aside>
    </div>

    <template #footer>
      <button class="btn btn-ghost" @click="close">取消</button>
      <span style="flex:1"/>
      <button class="btn btn-primary" :disabled="!title.trim() || datasetId == null" @click="submit">
        创建并编辑
      </button>
    </template>
  </cdc-modal>
</template>
