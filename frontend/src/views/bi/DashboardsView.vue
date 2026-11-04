<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CirclePlus, LayoutDashboard, RefreshCw, Search } from 'lucide-vue-next'
import { api } from '../../api'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { can } from '../../auth'

const router = useRouter()
const dashboards = ref([])
const loading = ref(false)
const q = ref('')
const status = ref('')
const dialog = ref({ open: false, saving: false, name: '', description: '' })

const isAdmin = computed(() => can('admin'))

// 一次取全量、本地筛：与数据集页同样的理由——服务端过滤会让四个页签的计数跟着子集变
const filtered = computed(() => dashboards.value.filter(item => {
  const matchStatus = !status.value || item.status === status.value
  const keyword = q.value.trim().toLowerCase()
  const matchText = !keyword || (item.name || '').toLowerCase().includes(keyword)
  return matchStatus && matchText
}))
const counts = computed(() => ({
  all: dashboards.value.length,
  PUBLISHED: dashboards.value.filter(item => item.status === 'PUBLISHED').length,
  DRAFT: dashboards.value.filter(item => item.status === 'DRAFT').length
}))

onMounted(load)

async function load() {
  loading.value = true
  try {
    const body = await api('/bi/dashboards?size=50')
    dashboards.value = body.items || []
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
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
      body: JSON.stringify({ name, description: dialog.value.description })
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

/** 列表里只留「月-日 时:分」：完整时间会把卡片挤成两行。 */
function stamp(value) {
  return value ? String(value).replace('T', ' ').slice(5, 16) : '—'
}
</script>

<template>
  <header class="page-header">
    <div>
      <h1>数据看板</h1>
      <p>用数据集里的字段拼出可视化卡片，发布后供全员查看。</p>
    </div>
    <div class="header-actions">
      <button v-if="isAdmin" class="console-btn primary" @click="openCreate">
        <CirclePlus :size="16"/>新建看板
      </button>
    </div>
  </header>

  <div class="page-controls">
    <section class="summary-grid">
      <button class="summary-item task-filter" :class="{ active: !status }" :aria-pressed="!status" @click="status = ''">
        <span>全部看板</span><strong>{{ counts.all }}</strong>
      </button>
      <button class="summary-item task-filter" :class="{ active: status === 'PUBLISHED' }" :aria-pressed="status === 'PUBLISHED'" @click="status = 'PUBLISHED'">
        <span>已发布</span><strong>{{ counts.PUBLISHED }}</strong>
      </button>
      <button v-if="isAdmin" class="summary-item task-filter" :class="{ active: status === 'DRAFT' }" :aria-pressed="status === 'DRAFT'" @click="status = 'DRAFT'">
        <span>草稿</span><strong>{{ counts.DRAFT }}</strong>
      </button>
    </section>
  </div>

  <section class="panel">
    <div class="toolbar task-filters">
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

    <div v-else-if="filtered.length" class="bi-list-grid" style="padding:14px">
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
          <span>{{ stamp(item.updatedAt) }}</span>
          <div class="bi-list-actions">
            <RouterLink class="btn btn-ghost btn-sm" :to="`/bi/dashboards/${item.id}`">查看</RouterLink>
            <button v-if="isAdmin" class="btn btn-ghost btn-sm" @click="router.push(`/bi/dashboards/${item.id}/edit`)">编辑</button>
            <button v-if="isAdmin" class="btn btn-ghost btn-sm danger-text" @click="remove(item)">删除</button>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">
      <LayoutDashboard :size="34"/>
      <p>{{ q || status ? '没有符合条件的看板' : '还没有看板，新建一个开始分析' }}</p>
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
</template>
