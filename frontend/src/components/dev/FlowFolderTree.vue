<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronDown, ChevronRight, Folder, FolderPlus } from 'lucide-vue-next'
import { api } from '../../api'
import { can } from '../../auth'
import { CdcMessage, CdcMessageBox } from '../ui/feedback'

/**
 * 数据流文件夹树，渲染在左侧导航里。
 *
 * <p>选中的文件夹写进 URL（`/dev/flows?folder=`），这样刷新、分享、后退都能还原；
 * 树本身不持有「当前选中」状态，完全由路由决定。
 */
const route = useRoute()
const router = useRouter()
const isAdmin = computed(() => can('admin'))

const folders = ref([])
const expanded = ref(new Set())
const dialog = ref(false)
const form = reactive({ id: null, name: '', parentId: null })

/** 没有 folder 参数 = 根目录（对应上面的「数据流」入口），数字表示具体文件夹 */
const current = computed(() => {
  const raw = route.query.folder
  if (raw === 'root') return 'root'
  const id = Number(raw)
  return raw != null && raw !== '' && !Number.isNaN(id) ? id : ''
})

async function load() {
  try {
    const body = await api('/flow-folders')
    folders.value = Array.isArray(body) ? body : []
    // 有子目录的默认展开，否则新建子文件夹后用户看不到它
    const parents = new Set(folders.value.map(f => f.parentId).filter(v => v != null))
    const next = new Set()
    for (const id of expanded.value) {
      if (folders.value.some(f => f.id === id)) next.add(id)
    }
    parents.forEach(id => next.add(id))
    expanded.value = next
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

/** 扁平化成行：算好深度与是否有子级，避免为树单独写递归组件。 */
const rows = computed(() => {
  const children = new Map()
  for (const f of folders.value) {
    const key = f.parentId ?? 'root'
    if (!children.has(key)) children.set(key, [])
    children.get(key).push(f)
  }
  const out = []
  const walk = (parent, depth) => {
    for (const f of children.get(parent) || []) {
      const hasChildren = children.has(f.id)
      out.push({ ...f, depth, hasChildren })
      if (hasChildren && expanded.value.has(f.id)) walk(f.id, depth + 1)
    }
  }
  walk('root', 0)
  return out
})

function select(value) {
  router.push(value === '' ? '/dev/flows' : `/dev/flows?folder=${value}`)
}

function toggle(row) {
  const next = new Set(expanded.value)
  if (next.has(row.id)) next.delete(row.id)
  else next.add(row.id)
  expanded.value = next
}

function openCreate(parentId = null) {
  form.id = null
  form.name = ''
  form.parentId = parentId
  dialog.value = true
}

function openRename(row) {
  form.id = row.id
  form.name = row.name
  form.parentId = row.parentId
  dialog.value = true
}

async function save() {
  const name = form.name.trim()
  if (!name) {
    CdcMessage.warning('请填写文件夹名称')
    return
  }
  try {
    if (form.id) {
      await api(`/flow-folders/${form.id}`, { method: 'PUT', body: JSON.stringify({ name }) })
    } else {
      await api('/flow-folders', { method: 'POST', body: JSON.stringify({ name, parentId: form.parentId }) })
    }
    dialog.value = false
    await load()
    CdcMessage.success(form.id ? '文件夹已重命名' : '文件夹已创建')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function remove(row) {
  try {
    await CdcMessageBox.confirm(
      `删除文件夹「${row.name}」？其中的子文件夹与数据流会上移到上一级，不会被删除。`, '删除文件夹')
  } catch {
    return
  }
  try {
    await api(`/flow-folders/${row.id}`, { method: 'DELETE' })
    // 正在看这个文件夹就退回「全部」，否则会停在一个已经不存在的筛选上
    if (current.value === row.id) select('')
    await load()
    CdcMessage.success('文件夹已删除')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="sb-folders">
    <div v-for="row in rows" :key="row.id" class="sb-folder-row" :class="{ active: current === row.id }"
         :style="{ paddingLeft: `${6 + row.depth * 12}px` }">
      <button v-if="row.hasChildren" class="sb-folder-toggle" :aria-label="expanded.has(row.id) ? '折叠' : '展开'"
              @click.stop="toggle(row)">
        <ChevronDown v-if="expanded.has(row.id)" :size="12"/>
        <ChevronRight v-else :size="12"/>
      </button>
      <span v-else class="sb-folder-toggle"/>
      <button class="sb-folder-pick" @click="select(row.id)">
        <Folder :size="13"/><span class="sb-folder-name">{{ row.name }}</span>
      </button>
      <cdc-menu v-if="isAdmin" class="sb-folder-actions" label="文件夹操作">
        <button role="menuitem" @click="openCreate(row.id)">新建子文件夹</button>
        <button role="menuitem" @click="openRename(row)">重命名</button>
        <button role="menuitem" class="danger" @click="remove(row)">删除文件夹</button>
      </cdc-menu>
    </div>

    <button v-if="isAdmin" class="sb-folder-add" @click="openCreate(null)">
      <FolderPlus :size="13"/><span>新建文件夹</span>
    </button>
  </div>

  <cdc-modal v-model="dialog" :title="form.id ? '重命名文件夹' : '新建文件夹'">
    <cdc-form>
      <cdc-form-item label="文件夹名称" required>
        <cdc-input v-model="form.name" @keyup.enter="save"/>
      </cdc-form-item>
    </cdc-form>
    <template #footer>
      <cdc-button @click="dialog = false">取消</cdc-button>
      <cdc-button type="primary" @click="save">保存</cdc-button>
    </template>
  </cdc-modal>
</template>
