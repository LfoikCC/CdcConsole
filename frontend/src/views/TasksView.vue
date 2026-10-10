<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { CdcMessage, CdcMessageBox } from '../components/ui/feedback'
import { CirclePlus, ListChecks, Pause, Pencil, Play, RefreshCw, RotateCcw, Square, Trash2 } from 'lucide-vue-next'
import { api } from '../api'
import { useAppStore } from '../stores/app'
import { canRunAction } from '../taskState'

const router = useRouter(), route = useRoute(), store = useAppStore(), loading = ref(false), tasks = ref([]), summary = ref({ total: 0, running: 0, degraded: 0, openAlerts: 0 })
const q = ref(''), status = ref(''), sourceType = ref(''), targetType = ref(''), page = ref(1), total = ref(0), view=ref('all')
let requestId=0
function readQuery(){q.value=String(route.query.q||'');status.value=String(route.query.status||'');sourceType.value=String(route.query.sourceType||'');targetType.value=String(route.query.targetType||'');view.value=String(route.query.view||'all');page.value=Math.max(1,Number(route.query.page)||1)}
readQuery()
watch(()=>route.query,()=>{readQuery();load()})
function selectView(value){view.value=value;status.value='';page.value=1;load()}
function changeStatus(){view.value='all';page.value=1;load()}
function changeFilter(){page.value=1;load()}
const metadataOpen = ref(false), metadata = reactive({ id: null, name: '', description: '' })
const labels = { DRAFT: '草稿', STARTING: '启动中', SNAPSHOTTING: '全量同步', RUNNING: '运行中', PAUSED: '已暂停', STOPPED: '已停止', DEGRADED: '部分异常', FAILED: '失败' }
const runtimeRefresh = () => load()
onMounted(() => { load(); window.addEventListener('cdc-runtime-change', runtimeRefresh) })
onBeforeUnmount(() => window.removeEventListener('cdc-runtime-change', runtimeRefresh))
async function load() {
  const urlQuery={};for(const [key,value] of Object.entries({q:q.value,status:status.value,sourceType:sourceType.value,targetType:targetType.value,view:view.value==='all'?'':view.value,page:page.value===1?'':String(page.value)}))if(value)urlQuery[key]=value
  if(JSON.stringify(Object.entries(urlQuery).sort())!==JSON.stringify(Object.entries(route.query).sort())){await router.replace({query:urlQuery});return}
  const current=++requestId;loading.value=true
  try{const query=new URLSearchParams({q:q.value,page:page.value-1,size:20,view:view.value});if(status.value)query.set('status',status.value);if(sourceType.value)query.set('sourceType',sourceType.value);if(targetType.value)query.set('targetType',targetType.value)
    const [list,sum]=await Promise.all([api('/tasks?'+query),api('/tasks/summary')]);if(current!==requestId)return;tasks.value=list.items;total.value=list.total;summary.value=sum;await store.loadAlerts()
  }catch(e){if(current===requestId)CdcMessage.error(e.message)}finally{if(current===requestId)loading.value=false}
}
function create() { router.push('/tasks/new') }
function edit(row) { router.push(`/tasks/${row.id}/edit`) }
async function action(row, name) { try { if (name === 'stop' || name === 'delete') await CdcMessageBox.confirm(name === 'stop' ? '停止会清除同步位点，下次启动将重新初始化。' : '删除任务后配置不可恢复，运行历史也会删除。', '确认操作', { type: 'warning' }); if (name === 'delete') await api(`/tasks/${row.id}`, { method: 'DELETE' }); else await api(`/tasks/${row.id}/actions/${name}`, { method: 'POST' }); CdcMessage.success('操作已提交'); await load() } catch (e) { if (e !== 'cancel') CdcMessage.error(e.message) } }
function can(row, name) { return canRunAction(row.status, name) }
function rename(row) { Object.assign(metadata, { id: row.id, name: row.name, description: row.description || '' }); metadataOpen.value = true }
async function saveMetadata() { if (!metadata.name.trim()) return CdcMessage.warning('请输入任务名称'); try { await api(`/tasks/${metadata.id}/metadata`, { method: 'PATCH', body: JSON.stringify({ name: metadata.name, description: metadata.description }) }); metadataOpen.value = false; await load(); CdcMessage.success('任务信息已更新') } catch (e) { CdcMessage.error(e.message) } }
</script>

<template>

  <div class="page-controls"><section class="summary-grid"><button v-for="item in [{key:'all',label:'全部任务',count:summary.total,color:''},{key:'running',label:'运行中',count:summary.running,color:'running'},{key:'abnormal',label:'异常任务',count:summary.degraded,color:'warning'},{key:'open-alerts',label:'待处理告警',count:summary.openAlerts,color:'danger'}]" :key="item.key" class="summary-item task-filter" :class="[item.color,{active:view===item.key&&!status}]" :aria-pressed="view===item.key&&!status" @click="selectView(item.key)"><span>{{item.label}}</span><strong>{{item.count}}</strong></button></section><button class="console-btn primary" @click="create"><CirclePlus :size="16"/>新建任务</button></div>
  <section class="panel">
    <div class="toolbar task-filters"><div class="search-box"><input v-model="q" aria-label="搜索任务名称" placeholder="搜索任务名称" @keyup.enter="changeFilter"/><button aria-label="搜索" @click="changeFilter"><RefreshCw :size="14"/></button></div><cdc-select v-model="status" aria-label="状态筛选" @change="changeStatus"><cdc-option label="全部状态" value=""/><cdc-option v-for="(label,key) in labels" :key="key" :label="label" :value="key"/></cdc-select><cdc-select v-model="sourceType" aria-label="源类型筛选" @change="changeFilter"><cdc-option label="全部源类型" value=""/><cdc-option label="MySQL" value="MYSQL"/><cdc-option label="SQL Server" value="SQLSERVER"/><cdc-option label="StarRocks" value="STARROCKS"/></cdc-select><cdc-select v-model="targetType" aria-label="目标类型筛选" @change="changeFilter"><cdc-option label="全部目标类型" value=""/><cdc-option label="MySQL" value="MYSQL"/><cdc-option label="SQL Server" value="SQLSERVER"/><cdc-option label="StarRocks" value="STARROCKS_STREAM_LOAD"/><cdc-option label="Kafka" value="KAFKA"/></cdc-select><span class="toolbar-spacer"/><button class="console-btn" @click="load"><RefreshCw :size="14"/>刷新</button></div>
    <div v-if="loading" class="loading-line" aria-live="polite">正在加载任务...</div>
    <div v-else-if="!tasks.length" class="empty-state"><ListChecks :size="34"/><div>{{summary.total?'当前筛选下没有任务':'还没有同步任务'}}</div><button v-if="!summary.total" class="console-btn primary" @click="create">创建第一个任务</button></div>
    <div v-else class="table-wrap"><table><thead><tr><th>任务</th><th>连接类型</th><th>状态</th><th>同步模式</th><th>表数</th><th>已处理</th><th>最近运行</th><th>延迟</th><th>异常摘要</th><th>操作</th></tr></thead><tbody><tr v-for="row in tasks" :key="row.id"><td><RouterLink class="task-link" :to="'/tasks/'+row.id">{{ row.name }}</RouterLink><div class="task-desc muted">{{ row.description || '暂无描述' }}</div></td><td><div class="flow"><span class="flow-node src">{{row.sourceType}}</span><span class="flow-arrow">→</span><span class="flow-node sink">{{row.targetType==='STARROCKS_STREAM_LOAD'?'StarRocks':row.targetType}}</span></div></td><td><span class="status-badge" :class="'status-'+row.status.toLowerCase()"><i/>{{ labels[row.status] || row.status }}</span></td><td>{{ row.syncMode === 'FULL_INCREMENTAL' ? '全量 + 增量' : '仅增量' }}</td><td class="mono">{{ row.tables.length }}</td><td class="mono">{{ row.tables.reduce((n, x) => n + x.processedRows, 0).toLocaleString() }}</td><td class="mono dim">{{ row.lastRunAt || '-' }}</td><td class="mono">{{ row.lagMs ? (row.lagMs / 1000).toFixed(1) + ' s' : '-' }}</td><td :class="{ 'danger-text': row.errorSummary }">{{ row.errorSummary || '-' }}</td><td><div class="row-actions"><RouterLink class="btn btn-ghost btn-sm" :to="'/tasks/'+row.id">详情</RouterLink><cdc-menu><button :disabled="!can(row,'start')" @click="action(row,'start')">启动</button><button :disabled="!can(row,'pause')" @click="action(row,'pause')">暂停</button><button :disabled="!can(row,'resume')" @click="action(row,'resume')">恢复</button><button :disabled="!can(row,'stop')" @click="action(row,'stop')">停止</button><button :disabled="!can(row,'edit')" @click="edit(row)">编辑</button><button @click="rename(row)">重命名</button><button class="danger" :disabled="!can(row,'edit')" @click="action(row,'delete')">删除</button></cdc-menu></div></td></tr></tbody></table></div>
    <div class="table-footer" v-if="total"><span class="muted">共 {{ total }} 个任务</span><div class="pager"><button class="icon-btn" :disabled="page <= 1" @click="page--;load()">‹</button><span>{{ page }}</span><button class="icon-btn" :disabled="page * 20 >= total" @click="page++;load()">›</button></div></div>
  </section>
  <cdc-modal v-model="metadataOpen" title="任务信息" width="min(460px,94vw)"><cdc-form label-position="top"><cdc-form-item label="任务名称" required><cdc-input v-model="metadata.name" maxlength="160"/></cdc-form-item><cdc-form-item label="描述"><cdc-input v-model="metadata.description" type="textarea" :rows="3" maxlength="2000"/></cdc-form-item></cdc-form><template #footer><cdc-button @click="metadataOpen=false">取消</cdc-button><cdc-button type="primary" @click="saveMetadata">保存</cdc-button></template></cdc-modal>
</template>
