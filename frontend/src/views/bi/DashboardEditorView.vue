<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CirclePlus, Send, Trash2 } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { can } from '../../auth'
import BiGrid from '../../components/bi/BiGrid.vue'
import CardEditor from '../../components/bi/CardEditor.vue'
import NewCardDialog from '../../components/bi/NewCardDialog.vue'
import DashboardFilterBar from '../../components/bi/DashboardFilterBar.vue'
import FilterWizard from '../../components/bi/FilterWizard.vue'
import { blankConfig, cardMeta, toCardPayload } from '../../components/bi/biCards'
import { normalizeFilter } from '../../components/bi/biFilters'
import { compact, freeSlot, settle } from '../../components/bi/biLayout'

const route = useRoute()
const router = useRouter()

const dashboard = ref(null)
const cards = ref([])
const datasets = ref([])
const dataMap = ref({})
const loadingMap = ref({})
const selectedKey = ref('')
/** 正在编辑的卡片：非空时整页切成卡片编辑器（数据集 / 绘制 / 预览 / 可视化 四栏）。 */
const editingKey = ref('')
const dialogOpen = ref(false)
const name = ref('')
const version = ref(0)
const dirty = ref(false)
const saving = ref(false)
const publishing = ref(false)
const loading = ref(true)

// ---------------------------------------------------------------- 看板筛选器
/** 看板级筛选器：同数据集的卡片取数时会自动带上（后端合并）。 */
const filters = ref([])
const filterWizardOpen = ref(false)
/** 正在编辑的筛选器；null 表示新建。 */
const editingFilter = ref(null)
let filterTimer = null

function openFilterWizard(filter = null) {
  editingFilter.value = filter
  filterWizardOpen.value = true
}

/** 向导提交：新增或替换，然后立即保存并重取数据。 */
async function submitFilter(filter) {
  const next = editingFilter.value
    ? filters.value.map(item => (item.id === filter.id ? filter : item))
    : [...filters.value, filter]
  const saved = await saveFilters(next)
  if (saved) {
    CdcMessage.success(editingFilter.value ? '筛选器已更新' : '筛选器已添加，卡片数据会一起生效')
    await loadData()
  }
}

/**
 * 筛选栏上直接改值：先本地生效（控件不回弹），再防抖保存。
 * 每敲一个字就打一次接口太浪费，而且查询也跟着抖。
 */
function onFiltersChanged(next) {
  filters.value = next
  clearTimeout(filterTimer)
  filterTimer = setTimeout(async () => {
    const saved = await saveFilters(filters.value)
    if (saved) await loadData()
  }, 450)
}

async function saveFilters(next) {
  try {
    await api(`/bi/dashboards/${route.params.id}/filters`, {
      method: 'PUT',
      body: JSON.stringify({ filters: next })
    })
    filters.value = next
    return true
  } catch (e) {
    CdcMessage.error(e.message)
    return false
  }
}

let seed = 0
const nextKey = () => `k${++seed}`

const isAdmin = computed(() => can('admin'))
const editingCard = computed(() => cards.value.find(card => card._k === editingKey.value) || null)
const published = computed(() => dashboard.value?.status === 'PUBLISHED')
/** 本页卡片已经在用的数据集：新建卡片时默认只列这些。 */
const usedDatasetIds = computed(() => [...new Set(cards.value
  .map(card => Number(card.datasetId))
  .filter(id => Number.isFinite(id) && id > 0))])

onMounted(load)
onBeforeUnmount(() => clearTimeout(titleTimer))

/** 有未保存改动时离开要拦一下，否则拖了半天的布局一返回就没了。 */
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await CdcMessageBox.confirm('有未保存的改动，确定离开？', '离开看板', { type: 'warning' })
    return true
  } catch {
    return false
  }
})

let titleTimer = null

async function load() {
  loading.value = true
  let ready = false
  try {
    const [detail, list] = await Promise.all([
      api(`/bi/dashboards/${route.params.id}`),
      api('/datasets?size=100')
    ])
    dashboard.value = detail
    filters.value = (detail.filters || []).map(normalizeFilter).filter(Boolean)
    name.value = detail.name
    version.value = detail.version
    datasets.value = list.items || []
    // 卡片 id 在保存后会全部重排，本地用一个稳定的 _k 做选中/取数的键
    cards.value = (detail.cards || []).map(card => ({ ...card, _k: nextKey() }))
    // 老布局里可能有卡片上方空着一大截（比如删掉上面那张卡之后）：打开就补上去
    compact(cards.value)
    ready = true
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
  // 取数不 await：一张卡查得慢（甚至数据源连不上）时，
  // 整页会一直停在「加载中」，连筛选器和布局都动不了
  if (ready) loadData()
}

async function loadData() {
  try {
    const body = await api(`/bi/dashboards/${route.params.id}/data`)
    const byId = new Map((body.cards || []).map(item => [item.cardId, item]))
    const next = {}
    for (const card of cards.value) {
      if (card.id != null && byId.has(card.id)) next[card._k] = byId.get(card.id)
    }
    dataMap.value = next
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

/** 新建卡片：弹窗里填标题选数据集，建完直接进卡片编辑器。 */
function createCard(payload) {
  const meta = cardMeta(payload.type)
  // 新卡片放进第一个空位；之后可以左右上下自由拖
  const slot = freeSlot(cards.value, meta.w, meta.h)
  const card = {
    id: null, _k: nextKey(), type: payload.type, title: payload.title, datasetId: payload.datasetId,
    x: slot.x, y: slot.y, w: meta.w, h: meta.h, config: blankConfig(payload.type)
  }
  cards.value = [...cards.value, card]
  dialogOpen.value = false
  selectedKey.value = card._k
  editingKey.value = card._k
  dirty.value = true
}

function openCard(key) {
  if (!isAdmin.value) return
  selectedKey.value = key
  editingKey.value = key
}

async function finishCard() {
  // 完成 = 保存 + 回到看板：不让「改完卡片忘了保存整张看板」这种事发生
  if (dirty.value) {
    const ok = await save()
    if (!ok) return
  }
  editingKey.value = ''
}

function backToBoard() {
  editingKey.value = ''
}

async function deleteEditingCard() {
  const card = editingCard.value
  if (!card) return
  try {
    await CdcMessageBox.confirm(`确定删除卡片「${card.title || cardMeta(card.type).label}」？`, '删除卡片', { type: 'warning' })
  } catch {
    return
  }
  cards.value = cards.value.filter(item => item._k !== card._k)
  // 删掉上面那张后，下面的卡立刻补上去，不用等保存
  compact(cards.value)
  if (selectedKey.value === card._k) selectedKey.value = ''
  editingKey.value = ''
  dirty.value = true
}

async function save() {
  if (saving.value) return false
  saving.value = true
  const index = cards.value.findIndex(card => card._k === editingKey.value)
  try {
    // 存之前把重叠的卡推开、再把上方的空洞补掉，
    // 后端校验和只读页拿到的都是干净且紧凑的位置
    settle(cards.value)
    compact(cards.value)
    const body = await api(`/bi/dashboards/${route.params.id}/cards`, {
      method: 'PUT',
      body: JSON.stringify({ version: version.value, cards: cards.value.map(toCardPayload) })
    })
    version.value = body.version
    cards.value = (body.cards || []).map(card => ({ ...card, _k: nextKey() }))
    // 后端是「删光重插」，id 全变了；按**下标**恢复选中，按 id 会让编辑器凭空关掉
    editingKey.value = index >= 0 ? (cards.value[index]?._k || '') : ''
    selectedKey.value = editingKey.value
    dirty.value = false
    await loadData()
    return true
  } catch (e) {
    CdcMessage.error(e.message)
    return false
  } finally {
    saving.value = false
  }
}

async function saveFromHeader() {
  if (await save()) CdcMessage.success('已保存')
}

async function togglePublish() {
  if (!isAdmin.value || publishing.value) return
  publishing.value = true
  // 先记住目标状态：dashboard 更新之后 published 就已经是新值了，提示会反过来
  const next = !published.value
  try {
    if (dirty.value) await save()
    const body = await api(`/bi/dashboards/${route.params.id}/${next ? 'publish' : 'unpublish'}`, { method: 'POST' })
    dashboard.value = { ...dashboard.value, ...body }
    CdcMessage.success(next ? '看板已发布，所有人可见' : '已取消发布，仅管理员可见')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    publishing.value = false
  }
}

async function rename() {
  const value = name.value.trim()
  if (!value || value === dashboard.value?.name) {
    name.value = dashboard.value?.name || ''
    return
  }
  try {
    await api(`/bi/dashboards/${route.params.id}`, {
      method: 'PUT',
      body: JSON.stringify({ name: value, description: dashboard.value?.description || '' })
    })
    dashboard.value = { ...dashboard.value, name: value }
    CdcMessage.success('名称已更新')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

function back() {
  router.push('/bi/dashboards')
}

function markDirty() {
  dirty.value = true
}
</script>

<template>
  <div v-if="loading" class="loading-line">加载中…</div>
  <div v-else-if="!dashboard" class="empty-state">看板不存在或无权访问</div>

  <!-- 卡片编辑：数据集 / 绘制 / 预览 / 可视化 -->
  <div v-else-if="editingCard" class="bi-editor">
    <div class="bi-editor-head">
      <button class="btn btn-ghost btn-sm" aria-label="返回看板" @click="backToBoard">
        <ArrowLeft :size="14"/>返回看板
      </button>
      <input v-model="editingCard.title" class="bi-name" aria-label="卡片标题"/>
      <span class="badge muted">{{ cardMeta(editingCard.type).label }}</span>
      <span v-if="dirty" class="bi-dirty">未保存</span>
      <div class="bi-editor-actions">
        <button class="console-btn" :disabled="saving" @click="deleteEditingCard">
          <Trash2 :size="14"/>删除卡片
        </button>
        <button class="console-btn primary" :disabled="saving" @click="finishCard">
          {{ saving ? '保存中…' : '完成' }}
        </button>
      </div>
    </div>

    <div class="bi-editor-body">
      <CardEditor :card="editingCard" :datasets="datasets" :dashboard-filters="filters" @change="markDirty"/>
    </div>
  </div>

  <!-- 看板编辑 -->
  <div v-else class="bi-editor">
    <div class="bi-editor-head">
      <button class="btn btn-ghost btn-sm" aria-label="返回" @click="back"><ArrowLeft :size="14"/>返回</button>
      <input v-model="name" class="bi-name" aria-label="看板名称" @blur="rename" @keyup.enter="$event.target.blur()"/>
      <span class="badge" :class="published ? 'success' : 'muted'">{{ published ? '已发布' : '草稿' }}</span>
      <span v-if="dirty" class="bi-dirty">未保存</span>
      <div class="bi-editor-actions">
        <button class="console-btn" :disabled="publishing" @click="dialogOpen = true">
          <CirclePlus :size="14"/>新建卡片
        </button>
        <button class="console-btn" :disabled="publishing" @click="togglePublish">
          <Send :size="14"/>{{ published ? '取消发布' : '发布' }}
        </button>
        <button class="console-btn primary" :disabled="!dirty || saving" @click="saveFromHeader">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>

    <div class="bi-editor-body is-board">
      <DashboardFilterBar :filters="filters" :datasets="datasets"
                          @update:filters="onFiltersChanged"
                          @add="openFilterWizard()"
                          @edit="openFilterWizard($event)"/>
      <div class="bi-stage">
        <BiGrid :cards="cards" :selected-key="selectedKey" editable
                :data-map="dataMap" :loading-map="loadingMap"
                @select="selectedKey = $event" @change="markDirty" @open="openCard"/>
        <div v-if="!cards.length" class="empty-state">
          <p>还没有卡片，点右上角「新建卡片」开始</p>
        </div>
      </div>
    </div>
  </div>

  <NewCardDialog v-model="dialogOpen" :datasets="datasets" :used-dataset-ids="usedDatasetIds"
                 default-type="bar" @create="createCard"/>

  <FilterWizard v-model="filterWizardOpen" :filter="editingFilter"
                :datasets="datasets" :cards="cards" @submit="submitFilter"/>
</template>
