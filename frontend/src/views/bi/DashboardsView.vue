<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronRight, CirclePlus, Folder, FolderInput, LayoutDashboard, RefreshCw, Search } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { can } from '../../auth'

const route = useRoute()
const router = useRouter()
const isAdmin = computed(() => can('admin'))

const dashboards = ref([])
const folders = ref([])
const overview = ref({ published: [], viewed: [], drafts: [] })
const loading = ref(false)
const q = ref('')
/** 概览 = 最近发布 / 最近查看 / 草稿；其余是列表模式（全部 / 已发布 / 草稿） */
const tab = ref('overview')
const dialog = ref({ open: false, saving: false, name: '', description: '' })
const move = ref({ open: false, saving: false, item: null, folderId: 0 })

/** 当前目录；null 表示全部/根目录 */
const folderId = computed(() => {
  const raw = route.query.folder
  const id = Number(raw)
  return raw != null && raw !== '' && !Number.isNaN(id) ? id : null
})
const currentFolder = computed(() => folders.value.find(item => item.id === folderId.value) || null)

/** 面包屑：从根一路走到当前目录，点哪一级跳哪一级 */
const breadcrumb = computed(() => {
  const path = []
  let node = currentFolder.value
  let guard = 0
  while (node && guard++ < 20) {
    path.unshift(node)
    const parentId = node.parentId
    node = folders.value.find(item => item.id === parentId) || null
  }
  return path
})

/** 当前目录的直接子目录，点一下就进去 */
const subfolders = computed(() => folders.value.filter(item => (item.parentId ?? null) === folderId.value))

const inOverview = computed(() => tab.value === 'overview' && !folderId.value && !q.value.trim())

const filtered = computed(() => dashboards.value.filter(item => {
  const matchStatus = (tab.value !== 'PUBLISHED' && tab.value !== 'DRAFT') || item.status === tab.value
  const keyword = q.value.trim().toLowerCase()
  const matchText = !keyword || (item.name || '').toLowerCase().includes(keyword)
  return matchStatus && matchText
}))

const counts = computed(() => ({
  all: dashboards.value.length,
  published: dashboards.value.filter(item => item.status === 'PUBLISHED').length,
  draft: dashboards.value.filter(item => item.status === 'DRAFT').length
}))

/** 概览三块。草稿只有管理员能看到，普通用户连入口都不显示。 */
const blocks = computed(() => [
  { key: 'published', title: '最近发布', hint: '发布后全员可见', items: overview.value.published, empty: '还没有发布过看板' },
  { key: 'viewed', title: '最近查看', hint: '你最近打开过的', items: overview.value.viewed, empty: '打开一个看板后会出现在这里' },
  ...(isAdmin.value
    ? [{ key: 'drafts', title: '草稿', hint: '只有管理员可见', items: overview.value.drafts, empty: '没有待完成的草稿' }]
    : [])
])

/** 建立看板时的目录选项：缩进表示层级，0 = 根目录 */
const flatFolders = computed(() => {
  const children = new Map()
  for (const item of folders.value) {
    const key = item.parentId ?? 'root'
    if (!children.has(key)) children.set(key, [])
    children.get(key).push(item)
  }
  const out = []
  const walk = (parent, depth) => {
    for (const item of (children.get(parent) || [])) {
      out.push({ ...item, depth })
      walk(item.id, depth + 1)
    }
  }
  walk('root', 0)
  return out
})

const folderName = id => folders.value.find(item => item.id === Number(id))?.name || ''

onMounted(load)
watch(() => route.query.folder, load)
// 进目录以后「概览」没意义了（最近发布是全局的），直接切到列表
watch(folderId, value => { if (value && tab.value === 'overview') tab.value = 'all' })
// 一搜索也要离开概览，否则搜索结果被三块列表挡住
watch(q, value => { if (value.trim() && tab.value === 'overview') tab.value = 'all' })

async function load() {
  loading.value = true
  try {
    const list = `/bi/dashboards?size=100${folderId.value ? `&folder=${folderId.value}` : ''}`
    const [page, tree, recent] = await Promise.all([
      api(list),
      api('/bi/dashboard-folders'),
      api('/bi/dashboards/overview')
    ])
    dashboards.value = page.items || []
    folders.value = Array.isArray(tree) ? tree : []
    overview.value = {
      published: recent.published || [],
      viewed: recent.viewed || [],
      drafts: recent.drafts || []
    }
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function go(id) {
  router.push(id == null ? '/bi/dashboards' : `/bi/dashboards?folder=${id}`)
}

function openCreate() {
  dialog.value = { open: true, saving: false, name: '', description: '' }
}

async function create() {
  const name = dialog.value.name.trim()
  if (!name) {
    CdcMessage.warning('请填写看板名称')
    return
  }
  dialog.value.saving = true
  try {
    const created = await api('/bi/dashboards', {
      method: 'POST',
      body: JSON.stringify({ name, description: dialog.value.description, folderId: folderId.value })
    })
    dialog.value.open = false
    router.push(`/bi/dashboards/${created.id}/edit`)
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    dialog.value.saving = false
  }
}

async function remove(item) {
  try {
    await CdcMessageBox.confirm(`确定删除看板「${item.name}」？卡片配置会一并删除。`, '删除看板', { type: 'warning' })
    await api(`/bi/dashboards/${item.id}`, { method: 'DELETE' })
    await load()
    CdcMessage.success('看板已删除')
  } catch (e) {
    if (e !== 'cancel') CdcMessage.error(e.message)
  }
}

function openMove(item) {
  move.value = { open: true, saving: false, item, folderId: item.folderId || 0 }
}

async function saveMove() {
  const item = move.value.item
  if (!item) return
  move.value.saving = true
  try {
    await api(`/bi/dashboards/${item.id}/folder`, {
      method: 'PATCH',
      body: JSON.stringify({ folderId: move.value.folderId || null })
    })
    move.value.open = false
    await load()
    CdcMessage.success(move.value.folderId ? `已移动到「${folderName(move.value.folderId)}」` : '已移动到根目录')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    move.value.saving = false
  }
}

/** 列表里只留「月-日 时:分」：完整时间会把卡片挤成两行。 */
function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(5, 16) : '—'
}
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ currentFolder ? currentFolder.name : '数据看板' }}</h1>
      <p v-if="currentFolder">这个目录下的看板{{ subfolders.length ? `，以及 ${subfolders.length} 个子目录` : '' }}。</p>
      <p v-else>用数据集里的字段拼出可视化卡片，发布后供全员查看。</p>
    </div>
    <div class="header-actions">
      <button v-if="isAdmin" class="console-btn primary" @click="openCreate">
        <CirclePlus :size="16"/>新建看板
      </button>
    </div>
  </header>

  <!-- 目录层级 + 子目录快捷入口 -->
  <div class="bi-crumbs">
    <button class="bi-crumb" :class="{ 'is-current': !folderId }" @click="go(null)">全部看板</button>
    <template v-for="node in breadcrumb" :key="node.id">
      <ChevronRight :size="12"/>
      <button class="bi-crumb" :class="{ 'is-current': node.id === folderId }" @click="go(node.id)">{{ node.name }}</button>
    </template>
  </div>
  <div v-if="subfolders.length" class="bi-subfolders">
    <button v-for="item in subfolders" :key="item.id" class="bi-subfolder" @click="go(item.id)">
      <Folder :size="12"/>{{ item.name }}
    </button>
  </div>

  <div class="page-controls">
    <div class="segmented bi-tabs">
      <button :class="{ active: tab === 'overview' }" @click="tab = 'overview'">概览</button>
      <button :class="{ active: tab === 'all' }" @click="tab = 'all'">全部 {{ counts.all }}</button>
      <button :class="{ active: tab === 'PUBLISHED' }" @click="tab = 'PUBLISHED'">已发布 {{ counts.published }}</button>
      <button v-if="isAdmin" :class="{ active: tab === 'DRAFT' }" @click="tab = 'DRAFT'">草稿 {{ counts.draft }}</button>
    </div>
    <div class="search-box">
      <Search :size="14" class="search-icon"/>
      <input v-model="q" placeholder="搜索看板名称" aria-label="搜索看板名称"/>
    </div>
    <span class="toolbar-spacer"/>
    <button class="console-btn" :disabled="loading" @click="load">
      <RefreshCw :size="14" :class="{ spin: loading }"/>刷新
    </button>
  </div>

  <div v-if="loading && !dashboards.length" class="loading-line">加载中…</div>

  <!-- 概览：最近发布 / 最近查看 / 草稿 -->
  <section v-else-if="inOverview" class="bi-overview">
    <div v-for="block in blocks" :key="block.key" class="bi-block">
      <div class="bi-block-head">
        <h2>{{ block.title }}</h2>
        <span>{{ block.hint }}</span>
        <button class="bi-block-more" @click="tab = block.key === 'drafts' ? 'DRAFT' : (block.key === 'published' ? 'PUBLISHED' : 'all')">
          查看全部<ChevronRight :size="11"/>
        </button>
      </div>
      <div v-if="block.items.length" class="bi-mini-row">
        <article v-for="item in block.items" :key="item.id" class="bi-mini"
                 @click="router.push(`/bi/dashboards/${item.id}`)">
          <h4>
            <LayoutDashboard :size="13"/>
            <span class="bi-mini-name">{{ item.name }}</span>
            <span class="badge" :class="item.status === 'PUBLISHED' ? 'success' : 'muted'">
              {{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </span>
          </h4>
          <p>{{ item.description || '暂无描述' }}</p>
          <div class="bi-mini-meta">
            <span>{{ item.cardCount }} 张卡片</span>
            <span v-if="item.folderId"><Folder :size="10"/>{{ folderName(item.folderId) }}</span>
            <span>{{ stamp(item.publishedAt || item.updatedAt) }}</span>
            <button v-if="isAdmin" class="bi-mini-move" title="移动到文件夹" aria-label="移动到文件夹"
                    @click.stop="openMove(item)">
              <FolderInput :size="12"/>
            </button>
          </div>
        </article>
      </div>
      <p v-else class="bi-block-empty">{{ block.empty }}</p>
    </div>
  </section>

  <!-- 列表：目录内 / 搜索 / 状态筛选 -->
  <section v-else class="panel">
    <div v-if="filtered.length" class="bi-list-grid" style="padding:14px">
      <article v-for="item in filtered" :key="item.id" class="bi-list-card">
        <h3>
          <LayoutDashboard :size="15"/>
          <RouterLink class="task-link" :to="`/bi/dashboards/${item.id}`">{{ item.name }}</RouterLink>
          <span class="badge" :class="item.status === 'PUBLISHED' ? 'success' : 'muted'" style="margin-left:auto">
            {{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }}
          </span>
        </h3>
        <p>{{ item.description || '暂无描述' }}</p>
        <div class="bi-list-meta">
          <span>{{ item.cardCount }} 张卡片</span>
          <span v-if="item.folderId"><Folder :size="10"/>{{ folderName(item.folderId) }}</span>
          <span>{{ stamp(item.updatedAt) }}</span>
          <div class="bi-list-actions">
            <RouterLink class="btn btn-ghost btn-sm" :to="`/bi/dashboards/${item.id}`">查看</RouterLink>
            <button v-if="isAdmin" class="btn btn-ghost btn-sm" @click="router.push(`/bi/dashboards/${item.id}/edit`)">编辑</button>
            <button v-if="isAdmin" class="btn btn-ghost btn-sm" @click="openMove(item)">移动到</button>
            <button v-if="isAdmin" class="btn btn-ghost btn-sm danger-text" @click="remove(item)">删除</button>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">
      <LayoutDashboard :size="34"/>
      <p>{{ q ? '没有符合条件的看板' : (currentFolder ? '这个目录还是空的' : '还没有看板，新建一个开始分析') }}</p>
      <button v-if="isAdmin" class="console-btn primary" @click="openCreate">
        <CirclePlus :size="16"/>新建看板
      </button>
    </div>
  </section>

  <cdc-modal v-model="dialog.open" title="新建看板" width="460px">
    <cdc-form>
      <cdc-form-item label="看板名称" required>
        <cdc-input v-model="dialog.name"/>
      </cdc-form-item>
      <cdc-form-item label="描述">
        <cdc-input v-model="dialog.description" type="textarea" :rows="3"/>
      </cdc-form-item>
    </cdc-form>
    <template #footer>
      <cdc-button @click="dialog.open = false">取消</cdc-button>
      <cdc-button type="primary" :loading="dialog.saving" @click="create">创建并编辑</cdc-button>
    </template>
  </cdc-modal>

  <cdc-modal v-model="move.open" title="移动到文件夹" width="420px">
    <cdc-form>
      <cdc-form-item label="目标文件夹">
        <cdc-select v-model="move.folderId">
          <cdc-option :value="0" label="根目录（不归档）"/>
          <cdc-option v-for="item in flatFolders" :key="item.id" :value="item.id"
                      :label="`${'　'.repeat(item.depth)}${item.name}`"/>
        </cdc-select>
      </cdc-form-item>
    </cdc-form>
    <template #footer>
      <cdc-button @click="move.open = false">取消</cdc-button>
      <cdc-button type="primary" :loading="move.saving" @click="saveMove">移动</cdc-button>
    </template>
  </cdc-modal>
</template>
