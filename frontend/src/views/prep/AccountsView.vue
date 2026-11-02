<script setup>
import DatabaseBrand from '../../components/DatabaseBrand.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { CdcMessage, CdcMessageBox } from '../../components/ui/feedback'
import { CirclePlus, PlugZap, Search, Trash2 } from 'lucide-vue-next'
import { api } from '../../api'
import { enabledMeta, badgeClass } from '../../statusMeta'
import { can } from '../../auth'

const accounts = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const q = ref('')
const type = ref('')
const dialog = ref(false)
const form = reactive(blank())

const TYPES = [
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'SQLSERVER', label: 'SQL Server' },
  { value: 'STARROCKS', label: 'StarRocks' },
  { value: 'KAFKA', label: 'Kafka' }
]
const CAPABILITY_LABELS = { SOURCE: 'CDC 源', TARGET: '目标', QUERY: '可查询' }
const isAdmin = computed(() => can('admin'))

const filtered = computed(() => accounts.value.filter(item => {
  const matchType = !type.value || item.accountType === type.value
  const matchText = !q.value || item.name.toLowerCase().includes(q.value.trim().toLowerCase())
  return matchType && matchText
}))

function blank() {
  return {
    id: null, name: '', accountType: 'MYSQL', host: '127.0.0.1', port: 3306,
    databaseName: '', username: 'root', password: '',
    endpoint: 'http://127.0.0.1:18030', queryPort: 19030,
    bootstrapServers: '127.0.0.1:9092', extra: {}, enabled: true
  }
}

async function load() {
  loading.value = true
  try {
    const body = await api('/accounts?size=500')
    accounts.value = body.items || []
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, blank())
  dialog.value = true
}

function openEdit(row) {
  const extra = row.extra || {}
  Object.assign(form, {
    id: row.id,
    name: row.name,
    accountType: row.accountType,
    host: row.host || '127.0.0.1',
    port: row.port || defaultPort(row.accountType),
    databaseName: row.databaseName || '',
    username: row.username || 'root',
    password: '',
    endpoint: extra.endpoint || 'http://127.0.0.1:18030',
    queryPort: extra.queryPort || 19030,
    bootstrapServers: extra.bootstrapServers || '127.0.0.1:9092',
    extra: { ...extra },
    enabled: row.enabled
  })
  dialog.value = true
}

function defaultPort(value) {
  return value === 'SQLSERVER' ? 1433 : value === 'STARROCKS' ? 9030 : 3306
}

function typeChanged() {
  if (form.accountType !== 'STARROCKS') form.port = defaultPort(form.accountType)
}

function payload() {
  const extra = form.accountType === 'STARROCKS'
    ? { endpoint: form.endpoint, queryPort: Number(form.queryPort) }
    : form.accountType === 'KAFKA'
      ? { bootstrapServers: form.bootstrapServers }
      // MySQL / SQL Server 的额外项（如轮询间隔）不在表单里改，原样带回去
      : { ...(form.extra || {}) }
  return {
    name: form.name,
    accountType: form.accountType,
    host: form.accountType === 'KAFKA' || form.accountType === 'STARROCKS' ? null : form.host,
    port: form.accountType === 'KAFKA' || form.accountType === 'STARROCKS' ? null : Number(form.port),
    databaseName: form.accountType === 'KAFKA' ? null : form.databaseName,
    username: form.accountType === 'KAFKA' ? null : form.username,
    password: form.password || null,
    extra,
    enabled: form.enabled
  }
}

function validateConnection() {
  if (form.accountType === 'KAFKA') {
    return String(form.bootstrapServers || '').trim() ? '' : '请填写 Kafka Bootstrap Servers'
  }
  if (form.accountType === 'STARROCKS') {
    if (!/^https?:\/\/.+/i.test(String(form.endpoint || '').trim())) return 'StarRocks 地址需以 http:// 或 https:// 开头'
    return Number(form.queryPort) >= 1 && Number(form.queryPort) <= 65535 ? '' : '查询端口需为 1-65535 的整数'
  }
  if (!String(form.host || '').trim()) return '请填写数据库地址'
  if (!(Number(form.port) >= 1 && Number(form.port) <= 65535)) return '端口需为 1-65535 的整数'
  return String(form.username || '').trim() ? '' : '请填写用户名'
}

function validate() {
  if (!String(form.name || '').trim()) return '请填写账户名称'
  return validateConnection()
}

async function save() {
  const problem = validate()
  if (problem) {
    CdcMessage.warning(problem)
    return
  }
  saving.value = true
  try {
    const path = form.id ? `/accounts/${form.id}` : '/accounts'
    await api(path, { method: form.id ? 'PUT' : 'POST', body: JSON.stringify(payload()) })
    dialog.value = false
    await load()
    CdcMessage.success('数据账户已保存')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  const problem = validateConnection()
  if (problem) {
    CdcMessage.warning(problem)
    return
  }
  testing.value = true
  try {
    const result = await api('/accounts/probe', {
      method: 'POST',
      body: JSON.stringify({ ...payload(), id: form.id })
    })
    const detail = result.detail ? `：${result.detail}` : ''
    result.reachable
      ? CdcMessage.success(`连接成功${detail}${result.tables != null ? `，发现 ${result.tables} 张表` : ''}`)
      : CdcMessage.error(result.error ? `连接失败：${result.error}` : '连接失败')
  } catch (e) {
    CdcMessage.error(e.message)
  } finally {
    testing.value = false
  }
}

async function probe(row) {
  try {
    const result = await api(`/accounts/${row.id}/probe`, { method: 'POST' })
    const detail = result.detail ? `：${result.detail}` : ''
    result.reachable
      ? CdcMessage.success(`连接成功${detail}${result.tables != null ? `，发现 ${result.tables} 张表` : ''}`)
      : CdcMessage.error(result.error ? `连接失败：${result.error}` : '连接失败')
  } catch (e) {
    CdcMessage.error(e.message)
  }
}

async function remove(row) {
  try {
    await CdcMessageBox.confirm(`确定删除数据账户「${row.name}」？被任务或订阅引用时无法删除。`, '删除数据账户', { type: 'warning' })
    await api(`/accounts/${row.id}`, { method: 'DELETE' })
    await load()
    CdcMessage.success('数据账户已删除')
  } catch (e) {
    if (e !== 'cancel') CdcMessage.error(e.message)
  }
}

function address(row) {
  if (row.accountType === 'KAFKA') return (row.extra || {}).bootstrapServers || '-'
  if (row.accountType === 'STARROCKS') return (row.extra || {}).endpoint || '-'
  return row.host ? `${row.host}:${row.port || ''}` : '-'
}

onMounted(load)
</script>

<template>
  <header class="page-header">
    <div>
      <h1>数据账户</h1>
      <p>统一的数据库连接凭证。一个账户既能作为同步源，也能作为写入目标，按类型自动限定可用角色。</p>
    </div>
    <div class="header-actions">
      <button v-if="isAdmin" class="console-btn primary" @click="openCreate"><CirclePlus :size="16"/>新建数据账户</button>
    </div>
  </header>

  <section class="panel">
    <div class="toolbar task-filters">
      <div class="search-box">
        <Search :size="14" class="search-icon"/>
        <input v-model="q" placeholder="搜索账户名称" aria-label="搜索账户名称"/>
      </div>
      <cdc-select v-model="type" placeholder="全部类型" style="width:150px">
        <cdc-option label="全部类型" value=""/>
        <cdc-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value" :brand="item.value"/>
      </cdc-select>
      <span class="toolbar-spacer"/>
      <button class="console-btn" :disabled="loading" @click="load">刷新</button>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>账户</th><th>地址</th><th>数据库</th><th>可用角色</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.id">
            <td>
              <DatabaseBrand :type="row.accountType" :label="row.name"/>
              <div class="task-desc muted">{{ row.username || '—' }}</div>
            </td>
            <td class="mono">{{ address(row) }}</td>
            <td class="mono dim">{{ row.databaseName || '—' }}</td>
            <td>
              <span v-for="capability in row.capabilities" :key="capability" class="account-cap">{{ CAPABILITY_LABELS[capability] || capability }}</span>
            </td>
            <td><span :class="badgeClass(enabledMeta(row.enabled).tone)"><i class="dot"/>{{ enabledMeta(row.enabled).label }}</span></td>
            <td>
              <div class="row-actions">
                <button class="btn btn-ghost btn-sm" @click="probe(row)"><PlugZap :size="13"/>测试</button>
                <cdc-menu>
                  <button v-if="isAdmin" @click="openEdit(row)">编辑</button>
                  <button v-if="isAdmin" class="danger" @click="remove(row)">删除</button>
                </cdc-menu>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && !filtered.length" class="empty-state">
        <p>{{ q || type ? '当前筛选下没有数据账户' : '还没有数据账户' }}</p>
        <button v-if="isAdmin && !q && !type" class="console-btn primary" @click="openCreate">创建第一个数据账户</button>
      </div>
    </div>
    <div class="table-footer"><span class="muted">共 {{ filtered.length }} 个数据账户</span></div>
  </section>

  <cdc-modal v-model="dialog" :title="form.id ? '编辑数据账户' : '新建数据账户'" width="min(680px,94vw)">
    <cdc-form label-position="top">
      <div class="two-cols">
        <cdc-form-item label="账户名称" required><cdc-input v-model="form.name" maxlength="160"/></cdc-form-item>
        <cdc-form-item label="账户类型" required>
          <cdc-select v-model="form.accountType" style="width:100%" @change="typeChanged">
            <cdc-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value" :brand="item.value"/>
          </cdc-select>
        </cdc-form-item>
      </div>

      <template v-if="form.accountType === 'KAFKA'">
        <cdc-form-item label="Bootstrap Servers" required>
          <cdc-input v-model="form.bootstrapServers" placeholder="127.0.0.1:9092"/>
        </cdc-form-item>
      </template>
      <template v-else-if="form.accountType === 'STARROCKS'">
        <div class="two-cols">
          <cdc-form-item label="Stream Load 地址" required><cdc-input v-model="form.endpoint" placeholder="http://127.0.0.1:18030"/></cdc-form-item>
          <cdc-form-item label="查询端口"><cdc-input-number v-model="form.queryPort" :min="1" :max="65535" style="width:100%"/></cdc-form-item>
        </div>
        <div class="two-cols">
          <cdc-form-item label="数据库"><cdc-input v-model="form.databaseName"/></cdc-form-item>
          <cdc-form-item label="用户名"><cdc-input v-model="form.username"/></cdc-form-item>
        </div>
        <cdc-form-item label="密码"><cdc-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空表示不修改' : ''"/></cdc-form-item>
      </template>
      <template v-else>
        <div class="two-cols">
          <cdc-form-item label="主机" required><cdc-input v-model="form.host"/></cdc-form-item>
          <cdc-form-item label="端口"><cdc-input-number v-model="form.port" :min="1" :max="65535" style="width:100%"/></cdc-form-item>
        </div>
        <div class="two-cols">
          <cdc-form-item label="数据库"><cdc-input v-model="form.databaseName"/></cdc-form-item>
          <cdc-form-item label="用户名"><cdc-input v-model="form.username"/></cdc-form-item>
        </div>
        <cdc-form-item label="密码"><cdc-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空表示不修改' : ''"/></cdc-form-item>
      </template>

      <cdc-form-item label="状态"><cdc-switch v-model="form.enabled" active-text="启用"/></cdc-form-item>
      <div class="form-help">账户类型决定可用角色：Kafka 只能作为目标；StarRocks 可查询、可写入，作为源时按轮询读取。</div>
    </cdc-form>
    <template #footer>
      <cdc-button :loading="testing" @click="testConnection"><PlugZap :size="14"/>测试连接</cdc-button>
      <cdc-button @click="dialog=false">取消</cdc-button>
      <cdc-button type="primary" :loading="saving" @click="save">保存</cdc-button>
    </template>
  </cdc-modal>
</template>

<style scoped>
.account-cap {
  display: inline-block;
  margin: 2px 4px 2px 0;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--muted);
  color: var(--muted-fg);
  font-size: 11px;
  white-space: nowrap;
}
</style>
