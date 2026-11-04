<script setup>
import { computed, ref, watch } from 'vue'
import { Database } from 'lucide-vue-next'
import { api } from '../../api'
import { DATASET_LABELS } from './biCards'

/** 新建卡片弹窗：填标题、选数据集，右侧预览这个数据集的字段与更新时间。 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  datasets: { type: Array, default: () => [] },
  defaultType: { type: String, default: 'bar' }
})

const emit = defineEmits(['update:modelValue', 'create'])

const title = ref('')
const datasetId = ref(null)
const detail = ref(null)
const stats = ref(null)
const loading = ref(false)

const selected = computed(() => props.datasets.find(item => item.id === datasetId.value) || null)

watch(() => props.modelValue, open => {
  if (!open) return
  title.value = ''
  datasetId.value = null
  detail.value = null
  stats.value = null
})

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
</script>

<template>
  <cdc-modal :model-value="modelValue" title="新建卡片" width="720px" @update:model-value="emit('update:modelValue', $event)">
    <div class="bi-picker">
      <div class="bi-picker-left">
        <cdc-form>
          <cdc-form-item label="卡片标题" required>
            <cdc-input v-model="title" aria-label="卡片标题"/>
          </cdc-form-item>
          <cdc-form-item label="数据集" required>
            <cdc-select v-model="datasetId" filterable style="width:100%">
              <cdc-option v-for="item in datasets" :key="item.id"
                          :label="`${item.name}（${DATASET_LABELS[item.datasetType] || item.datasetType}）`"
                          :value="item.id"/>
            </cdc-select>
          </cdc-form-item>
        </cdc-form>
      </div>

      <aside class="bi-picker-right">
        <div v-if="!selected" class="bi-picker-empty">
          <Database :size="26"/>
        </div>
        <template v-else>
          <h4>{{ selected.name }}</h4>
          <div class="bi-picker-tags">
            <span class="badge muted">{{ DATASET_LABELS[selected.datasetType] || selected.datasetType }}</span>
            <span class="muted mono">{{ [selected.databaseName, selected.tableName].filter(Boolean).join('.') || '平台存储' }}</span>
          </div>
          <dl class="bi-picker-grid">
            <div><dt>字段数</dt><dd>{{ selected.columnCount ?? detail?.columns?.length ?? '—' }}</dd></div>
            <div><dt>行数</dt><dd>{{ stats?.rows ?? (selected.rowCount || '—') }}</dd></div>
            <div class="wide"><dt>最近更新</dt><dd>{{ stamp(selected.updatedAt) }}</dd></div>
          </dl>
          <div v-if="loading" class="form-help">加载字段…</div>
          <div v-else-if="detail?.columns?.length" class="bi-picker-fields">
            <div class="bi-picker-fields-title">字段</div>
            <div class="bi-picker-field-list">
              <span v-for="column in detail.columns" :key="column.name" class="bi-picker-field">{{ column.name }}</span>
            </div>
          </div>
          <div v-else class="form-help">这个数据集还没有字段</div>
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
