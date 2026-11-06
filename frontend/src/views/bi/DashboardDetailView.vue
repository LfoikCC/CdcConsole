<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Pencil, RefreshCw } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage } from '../../components/ui/feedback'
import { can } from '../../auth'
import BiGrid from '../../components/bi/BiGrid.vue'
import DashboardFilterBar from '../../components/bi/DashboardFilterBar.vue'
import { compact } from '../../components/bi/biLayout'
import { normalizeFilter } from '../../components/bi/biFilters'

const route = useRoute()
const router = useRouter()

const dashboard = ref(null)
const cards = ref([])
const datasets = ref([])
const dataMap = ref({})
const loading = ref(true)
const refreshing = ref(false)
const elapsed = ref(0)
/**
 * 使用者当前选的筛选条件。
 *
 * <p>从看板定义深拷贝一份：查看页上改值只影响自己的查询，
 * 不能把作者配的默认值改掉。
 */
const runtimeFilters = ref([])
let filterTimer = null

const isAdmin = computed(() => can('admin'))
const published = computed(() => dashboard.value?.status === 'PUBLISHED')
const empty = computed(() => !loading.value && !cards.value.length)

let seed = 0

onMounted(load)
onBeforeUnmount(() => clearTimeout(filterTimer))

async function load() {
  loading.value = true
  let ready = false
  try {
    const [detail, list] = await Promise.all([
      api(`/bi/dashboards/${route.params.id}?track=1`),
      api('/datasets?size=100')
    ])
    dashboard.value = detail
    datasets.value = list.items || []
    runtimeFilters.value = (detail.filters || []).map(normalizeFilter).filter(Boolean)
    cards.value = (detail.cards || []).map(card => ({ ...card, _k: `k${++seed}` }))
    // 和编辑页同一套规则：上方空着就往上补，两侧不动
    compact(cards.value)
    ready = true
  } catch (e) {
    dashboard.value = null
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
  // 取数不阻塞渲染：一张卡查得慢时整页不该停在「加载中」
  if (ready) loadData()
}

/** 改筛选器：先本地生效，再防抖重取（值不写回看板定义）。 */
function onFiltersChanged(next) {
  runtimeFilters.value = next
  clearTimeout(filterTimer)
  filterTimer = setTimeout(loadData, 400)
}

async function loadData() {
  refreshing.value = true
  try {
    // POST 带上使用者当前的选择；不传就等于用看板里存的那份
    const body = await api(`/bi/dashboards/${route.params.id}/data`, {
      method: 'POST',
      body: JSON.stringify({ filters: runtimeFilters.value })
    })
    const byId = new Map((body.cards || []).map(item => [item.cardId, item]))
    const next = {}
    for (const card of cards.value) {
      if (card.id != null) next[card._k] = byId.get(card.id) || null
    }
    dataMap.value = next
    elapsed.value = body.elapsedMs || 0
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    refreshing.value = false
  }
}

function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}
</script>

<template>
  <div v-if="loading" class="loading-line">加载中…</div>

  <div v-else-if="!dashboard" class="empty-state">
    <p>看板不存在，或者尚未发布（未发布的看板仅管理员可见）</p>
    <button class="console-btn" @click="router.push('/bi/dashboards')">返回看板列表</button>
  </div>

  <div v-else class="bi-view">
    <header class="page-header">
      <div>
        <h1>{{ dashboard.name }}</h1>
        <p>{{ dashboard.description || '暂无描述' }}</p>
      </div>
      <div class="header-actions">
        <span class="badge" :class="published ? 'success' : 'muted'">{{ published ? '已发布' : '草稿' }}</span>
        <button class="console-btn" :disabled="refreshing" @click="loadData">
          <RefreshCw :size="14" :class="{ spin: refreshing }"/>刷新数据
        </button>
        <button v-if="isAdmin" class="console-btn primary" @click="router.push(`/bi/dashboards/${dashboard.id}/edit`)">
          <Pencil :size="14"/>编辑
        </button>
      </div>
    </header>

    <div v-if="!published && isAdmin" class="alert-bar warning-callout">
      此看板尚未发布，只有管理员能看到；发布后其他人才可见。
    </div>

    <DashboardFilterBar v-if="runtimeFilters.length" :filters="runtimeFilters" :datasets="datasets"
                        readonly @update:filters="onFiltersChanged"/>

    <div v-if="empty" class="empty-state">
      <p>看板还没有卡片，去编辑页添加</p>
      <button v-if="isAdmin" class="console-btn primary" @click="router.push(`/bi/dashboards/${dashboard.id}/edit`)">去编辑</button>
    </div>

    <div v-else class="bi-view-stage">
      <BiGrid :cards="cards" :data-map="dataMap" :editable="false"/>
    </div>

    <div class="bi-list-meta" style="padding:0 2px">
      <span>共 {{ cards.length }} 张卡片</span>
      <span>更新于 {{ stamp(dashboard.updatedAt) }}</span>
      <span v-if="elapsed">取数耗时 {{ elapsed }} ms</span>
      <span style="margin-left:auto">
        <button class="btn btn-ghost btn-sm" @click="router.push('/bi/dashboards')">
          <ArrowLeft :size="13"/>返回列表
        </button>
      </span>
    </div>
  </div>
</template>
