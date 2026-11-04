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
import { blankConfig, cardMeta, toCardPayload } from '../../components/bi/biCards'
import { applyMasonry } from '../../components/bi/biLayout'

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

let seed = 0
const nextKey = () => `k${++seed}`

const isAdmin = computed(() => can('admin'))
const editingCard = computed(() => cards.value.find(card => card._k === editingKey.value) || null)
const published = computed(() => dashboard.value?.status === 'PUBLISHED')

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
  try {
    const [detail, list] = await Promise.all([
      api(`/bi/dashboards/${route.params.id}`),
      api('/datasets?size=100')
    ])
    dashboard.value = detail
    name.value = detail.name
    version.value = detail.version
    datasets.value = list.items || []
    // 卡片 id 在保存后会全部重排，本地用一个稳定的 _k 做选中/取数的键
    cards.value = (detail.cards || []).map(card => ({ ...card, _k: nextKey() }))
    await loadData()
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
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
  // 位置由瀑布流排：新卡片只需要尺寸，追加到末尾即可
  const card = {
    id: null, _k: nextKey(), type: payload.type, title: payload.title, datasetId: payload.datasetId,
    x: 0, y: 0, w: meta.w, h: meta.h, config: blankConfig(payload.type)
  }
  cards.value = [...cards.value, card]
  applyMasonry(cards.value)
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
  if (selectedKey.value === card._k) selectedKey.value = ''
  editingKey.value = ''
  dirty.value = true
}

async function save() {
  if (saving.value) return false
  saving.value = true
  const index = cards.value.findIndex(card => card._k === editingKey.value)
  try {
    // 存之前把瀑布流算出来的坐标写回去，后端校验和只读页拿到的都是不重叠的位置
    applyMasonry(cards.value)
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
      <CardEditor :card="editingCard" :datasets="datasets" @change="markDirty"/>
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

    <div class="bi-editor-body">
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

  <NewCardDialog v-model="dialogOpen" :datasets="datasets" default-type="bar" @create="createCard"/>
</template>
