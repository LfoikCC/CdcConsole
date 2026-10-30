<script setup>
import DatabaseBrand from '../components/DatabaseBrand.vue'
import { subscriptionTableStatus, subscriptionPhase, initializationLabel } from '../subscriptionState'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CdcMessage, CdcMessageBox } from '../components/ui/feedback'
import { createChart, chartStyle, chartAxis } from '../chartStyle'
import { ArrowLeft, Pause, Play, RefreshCw, RotateCcw, Square, LayoutDashboard, MapPin, BarChart3, Layers, ScrollText, Package, Table2, GitBranch, Bell, ShieldCheck } from 'lucide-vue-next'
import { api } from '../api'
import { useAppStore } from '../stores/app'
import SubscriptionWizard from '../components/SubscriptionWizard.vue'
import ReconciliationConfigDialog from '../components/ReconciliationConfigDialog.vue'
import { canRunAction } from '../taskState'
import { instanceStatusMeta, reconciliationMeta, discrepancyTone, badgeClass } from '../statusMeta'
import TaskConnectionFlow from '../components/TaskConnectionFlow.vue'
import MailSubscriptions from '../components/MailSubscriptions.vue'
import CheckpointPanel from '../components/CheckpointPanel.vue'
import RecoveryDialog from '../components/RecoveryDialog.vue'
import { can as permitted } from '../auth'

const route=useRoute(), router=useRouter(), id=Number(route.params.id), task=ref(null), active=ref(String(route.query.tab||'overview')), loading=ref(false)
const detailGroups=[{title:'运行监控',items:[['overview','概览'],['checkpoints','位点'],['metrics','指标'],['instances','运行实例'],['logs','日志'],['delivery','投递批次']]},{title:'任务配置',items:[['tables','表配置'],['subscriptions','目标订阅'],...(permitted('admin')?[['notifications','通知设置']]:[])]},{title:'数据质量',items:[['reconciliation','数据核对']]}]
const detailIcons={overview:LayoutDashboard,checkpoints:MapPin,metrics:BarChart3,instances:Layers,logs:ScrollText,delivery:Package,tables:Table2,subscriptions:GitBranch,notifications:Bell,reconciliation:ShieldCheck}
const recoveryOpen=ref(false),recoverySourceTable=ref(null)
const validTabs=detailGroups.flatMap(group=>group.items.map(item=>item[0]))
if(!validTabs.includes(active.value))active.value='overview'
watch(()=>route.query.tab,value=>{active.value=validTabs.includes(value)?value:'overview'})
watch(active,value=>{router.replace({query:{...route.query,tab:value}});tabChanged()})
const instances=ref([]), logs=ref([]), logPage=ref(1), logTotal=ref(0), reconciliations=ref([]), metrics=ref([]), range=ref('24h'), chartEl=ref(), wizard=ref(false)
const deliveryBatches=ref({items:[],total:0,page:0,size:50,totalPages:0}), deliveryPage=ref(1), deliveryStatus=ref('')
const reconciliationDialog=ref(false), submittingReconciliation=ref(false), selectedRun=ref(null), selectedRunLoading=ref(false)
const discrepancies=ref({items:[],total:0,page:0,size:50,totalPages:0}), discrepancyType=ref(''), discrepancyPage=ref(1)
const subscriptionWizard=ref(false), subscriptionReconciliation=ref(null), reconciliationConfigDialog=ref(false), selectedSubscription=ref(null)
const editingSubscription=ref(null)
const connectionStore=useAppStore()
function targetName(subscription){return connectionStore.targets.find(item=>item.id===subscription.targetId)?.name||`#${subscription.targetId}`}
function batchTarget(row){return targetSubscriptions.value.find(subscription=>subscription.tables.some(binding=>binding.mappingId===row.mappingId))?.name||`Mapping #${row.mappingId}`}
const reconciliationStep=ref(0), reconciliationScope=ref('')
const reconciliationForm=reactive({mode:'INCREMENTAL',tableIds:[],range:[],drillDown:true})
let chart
const labels={DRAFT:'草稿',STARTING:'启动中',SNAPSHOTTING:'全量同步',RUNNING:'运行中',RETRYING:'重试中',PAUSED:'已暂停',STOPPED:'已停止',DEGRADED:'部分异常',FAILED:'失败'}
const deliveryLabels={STAGED:'待投递',DELIVERING:'投递中',RETRY_WAIT:'等待重试',SUCCEEDED:'成功',FAILED:'失败',CANCELLED:'已取消'}
const reconciliationStatus={QUEUED:'排队中',RUNNING:'执行中',MATCHED:'一致',MISMATCHED:'不一致',PARTIAL_FAILED:'部分失败',FAILED:'失败'}
const modeLabels={INCREMENTAL:'增量窗口',RANGE:'指定范围',FULL:'全量'}
const triggerLabels={MANUAL:'手动',SCHEDULED:'定时',AFTER_SNAPSHOT:'全量同步后'}
const discrepancyLabels={TARGET_MISSING:'目标缺失',TARGET_REDUNDANT:'目标冗余',FIELD_MISMATCH:'字段不一致',DELETE_RESIDUE:'删除残留'}
const canEdit=computed(()=>canRunAction(task.value?.status,'edit'))
const totalRows=computed(()=>task.value?.tables.reduce((n,x)=>n+x.processedRows,0)||0)
const totalErrors=computed(()=>task.value?.tables.reduce((n,x)=>n+x.errorCount,0)||0)
const maxLag=computed(()=>Math.max(0,...(task.value?.tables.map(x=>x.lastLatencyMs||0)||[])))
const reconciliationTables=computed(()=>subscriptionReconciliation.value?.tables.map(x=>x.mapping).filter(x=>x.reconciliation?.enabled)||task.value?.tables.filter(x=>x.reconciliation?.enabled)||[])
const targetSubscriptions=computed(()=>task.value?.targetSubscriptions||[])
const currentSubscriptions=computed(()=>targetSubscriptions.value.filter(row=>row.status!=='CANCELLED'))
const cancelledSubscriptions=computed(()=>targetSubscriptions.value.filter(row=>row.status==='CANCELLED'))
const subscriptionBusy=ref(null)
const runtimeRefresh=()=>load(false)

onMounted(()=>{load();window.addEventListener('cdc-runtime-change',runtimeRefresh);window.addEventListener('cdc-theme-change',draw);window.addEventListener('resize',resizeChart)})
onBeforeUnmount(()=>{window.removeEventListener('cdc-runtime-change',runtimeRefresh);window.removeEventListener('cdc-theme-change',draw);window.removeEventListener('resize',resizeChart);chart?.dispose()})
watch([metrics,chartEl],()=>nextTick(draw),{deep:true})

async function load(spin=true){if(spin)loading.value=true;try{task.value=await api(`/tasks/${id}`);await loadTab()}catch(e){CdcMessage.error(e.message)}finally{loading.value=false}}
async function loadTab(){const status=deliveryStatus.value?`&status=${deliveryStatus.value}`:'';const calls=[api(`/tasks/${id}/instances`),api(`/tasks/${id}/logs?page=${logPage.value-1}&size=50`),api(`/tasks/${id}/reconciliations`),api(`/tasks/${id}/metrics?range=${range.value}`),api(`/tasks/${id}/delivery-batches?page=${deliveryPage.value-1}&size=50${status}`)];const [runs,logPageResult,recon,samples,batches]=await Promise.all(calls);instances.value=runs;logs.value=logPageResult.items;logTotal.value=logPageResult.total;reconciliations.value=[...recon].sort((a,b)=>new Date(b.startedAt).getTime()-new Date(a.startedAt).getTime()||b.id-a.id);if(subscriptionReconciliation.value)await loadReconciliations();metrics.value=samples;deliveryBatches.value=batches}
function subscriptionStatus(row){return {ACTIVE:'运行中',INITIALIZING_SNAPSHOT:'正在全量初始化',CATCHING_UP:'正在追平增量',PAUSED:'已暂停',CANCELLED:'已取消',FAILED:'初始化失败'}[row.status]||row.status}
function editReconciliationConfig(subscription){selectedSubscription.value=subscription;reconciliationConfigDialog.value=true}
async function subscriptionAction(row,action){
  if(subscriptionBusy.value!==null)return
  subscriptionBusy.value=row.id
  try{
    if(action==='cancel')await CdcMessageBox.confirm('取消后停止该目标的后续投递，并移入已取消订阅历史；其他目标不受影响。历史记录保留，可重新启用。','确认取消订阅')
    if(row.status==='CANCELLED')await CdcMessageBox.confirm('重新启用将执行目标专属全量初始化并追平增量，不影响其他目标。按主键覆盖，不清空目标表；源端已删除的历史残留行不会自动清除。','重新启用订阅',{type:'warning'})
    await api(`/tasks/${id}/subscriptions/${row.id}/actions/${action}`,{method:'POST'})
    CdcMessage.success(row.status==='CANCELLED'?'重新启用已提交，将执行全量初始化':action==='cancel'?'目标订阅已移入历史':action==='pause'?'目标订阅已暂停':action==='retry-initialization'?'已重新发起初始化':'目标订阅已恢复')
    await load(false)
  }catch(e){if(e!=='cancel')CdcMessage.error(e.message)}finally{subscriptionBusy.value=null}
}
function createSubscriber(){editingSubscription.value=null;subscriptionWizard.value=true}
function editSubscription(row){editingSubscription.value=row;subscriptionWizard.value=true}
async function action(name){try{if(name==='stop')await CdcMessageBox.confirm('停止会清除同步位点，下次启动将重新初始化。','确认停止',{type:'warning'});await api(`/tasks/${id}/actions/${name}`,{method:'POST'});CdcMessage.success('操作已提交');await load()}catch(e){if(e!=='cancel')CdcMessage.error(e.message)}}
function openReconciliation(subscription=null){subscriptionReconciliation.value=subscription?.id?subscription:null;reconciliationScope.value=subscriptionReconciliation.value?String(subscription.id):'';reconciliationStep.value=0;reconciliationForm.mode='INCREMENTAL';reconciliationForm.tableIds=[];reconciliationForm.range=[];reconciliationForm.drillDown=true;reconciliationDialog.value=true}
async function selectReconciliationScope(value){subscriptionReconciliation.value=targetSubscriptions.value.find(item=>String(item.id)===String(value))||null;reconciliationForm.tableIds=[];selectedRun.value=null;discrepancies.value={items:[],total:0};await loadReconciliations()}
async function loadReconciliations(){const path=subscriptionReconciliation.value?`/tasks/${id}/subscriptions/${subscriptionReconciliation.value.id}/reconciliations`:`/tasks/${id}/reconciliations`;const records=await api(path);reconciliations.value=[...records].sort((a,b)=>new Date(b.startedAt)-new Date(a.startedAt)||b.id-a.id)}
function nextReconciliation(){if(reconciliationStep.value===0&&reconciliationForm.mode==='RANGE'&&(!reconciliationForm.range?.[0]||!reconciliationForm.range?.[1]||new Date(reconciliationForm.range[0])>=new Date(reconciliationForm.range[1])))return CdcMessage.warning('请选择有效的起止时间');if(reconciliationStep.value===1&&!reconciliationTables.value.length)return CdcMessage.warning('当前目标没有已配置数据核对的表');reconciliationStep.value++}
async function submitReconciliation(){
  if(!reconciliationTables.value.length)return CdcMessage.warning('当前任务没有已配置数据核对的表')
  if(reconciliationForm.mode==='RANGE'&&(!reconciliationForm.range||reconciliationForm.range.length!==2))return CdcMessage.warning('请选择核对时间范围')
  if(reconciliationForm.mode==='FULL')try{await CdcMessageBox.confirm('全量核对可能对源端和目标端产生较高查询负载，建议在低峰期执行。','确认全量核对',{type:'warning'})}catch{return}
  const body={mode:reconciliationForm.mode,tableIds:reconciliationForm.tableIds,drillDown:reconciliationForm.drillDown}
  if(reconciliationForm.mode==='RANGE'){body.startAt=new Date(reconciliationForm.range[0]).toISOString();body.endAt=new Date(reconciliationForm.range[1]).toISOString()}
  submittingReconciliation.value=true
  try{const path=subscriptionReconciliation.value?`/tasks/${id}/subscriptions/${subscriptionReconciliation.value.id}/reconciliations`:`/tasks/${id}/reconciliations`;const result=await api(path,{method:'POST',body:JSON.stringify(body)});reconciliationDialog.value=false;CdcMessage.success(`核对任务已提交（运行 ID ${result.id}）`);await loadTab()}
  catch(e){CdcMessage.error(e.message)}finally{submittingReconciliation.value=false}
}
async function showRun(run){selectedRunLoading.value=true;selectedRun.value=null;discrepancyPage.value=1;discrepancyType.value='';try{selectedRun.value=await api(`/tasks/${id}/reconciliations/${run.id}`);await loadDiscrepancies()}catch(e){CdcMessage.error(e.message)}finally{selectedRunLoading.value=false}}
async function loadDiscrepancies(){if(!selectedRun.value)return;const type=discrepancyType.value?`&type=${discrepancyType.value}`:'';discrepancies.value=await api(`/tasks/${id}/reconciliations/${selectedRun.value.run.id}/discrepancies?page=${discrepancyPage.value-1}&size=50${type}`)}
function tableName(tableId){const row=task.value?.tables.find(x=>x.id===tableId);return row?[row.sourceDatabase,row.sourceSchema,row.sourceTable].filter(Boolean).join('.'):String(tableId)}
function formatWindow(run){if(run.mode==='FULL')return '全量';if(!run.windowStart||!run.windowEnd)return '-';return `${new Date(run.windowStart).toLocaleString()} — ${new Date(run.windowEnd).toLocaleString()}`}
function formatDateTime(value){return value?new Date(value).toLocaleString():'-'}
function formatValue(value){if(value===null||value===undefined)return 'NULL';return typeof value==='object'?JSON.stringify(value):String(value)}
async function retryBatch(row){try{await api(`/tasks/${id}/delivery-batches/${row.batchId}/retry`,{method:'POST'});CdcMessage.success('失败批次已重新进入投递队列');await loadTab()}catch(e){CdcMessage.error(e.message)}}
function resizeChart(){chart?.resize()}
function draw(){
  if(!chartEl.value||active.value!=='metrics')return
  if(chart&&chart.getDom()!==chartEl.value){chart.dispose();chart=null}
  chart ||= createChart(chartEl.value)
  chart.setOption({...chartStyle(),
    xAxis:{...chartAxis(),type:'time',splitLine:{show:false}},
    yAxis:[{...chartAxis(),type:'value',name:'累计行数',minInterval:1},{...chartAxis(),type:'value',name:'延迟 ms',splitLine:{show:false}}],
    series:[{name:'累计处理行数',type:'line',showSymbol:metrics.value.length===1,symbolSize:5,lineStyle:{width:2},data:metrics.value.map(x=>[x.sampledAt,x.processedRows])},
      {name:'延迟 (ms)',type:'line',yAxisIndex:1,showSymbol:metrics.value.length===1,symbolSize:5,lineStyle:{width:2},data:metrics.value.map(x=>[x.sampledAt,x.lagMs])}]
  },true)
}
function tabChanged(){if(active.value==='metrics')nextTick(draw)}
</script>

<template>
  <div v-loading="loading" class="task-detail-page">
    <div class="task-detail-heading">
    <div v-if="task" class="crumbs"><RouterLink to="/tasks">同步任务</RouterLink><span>/</span><span class="cur">{{task.name}}</span></div>
  <header class="page-header"><div><cdc-button text class="back" @click="router.push('/tasks')"><ArrowLeft :size="16"/>返回任务列表</cdc-button><h1>{{task?.name||'任务详情'}}</h1><p>{{task?.description||'暂无描述'}}</p></div><div v-if="task" class="header-actions"><cdc-button v-if="canRunAction(task.status,'start')" v-permit="'operate'" @click="action('start')"><Play :size="15"/>启动</cdc-button><cdc-button v-if="canRunAction(task.status,'pause')" v-permit="'operate'" @click="action('pause')"><Pause :size="15"/>暂停</cdc-button><cdc-button v-if="canRunAction(task.status,'resume')" v-permit="'operate'" @click="action('resume')"><RotateCcw :size="15"/>恢复</cdc-button><cdc-button v-if="canRunAction(task.status,'stop')" v-permit="'operate'" @click="action('stop')"><Square :size="15"/>停止</cdc-button><cdc-button :disabled="!canEdit" v-permit="'admin'" @click="router.push(`/tasks/${id}/edit`)">编辑配置</cdc-button></div></header>
    <div v-if="task?.tables.some(x=>x.status==='FAILED')" class="warning-callout detail-warning">部分表运行异常，健康表仍在继续同步。请在“表配置”和“日志”中查看失败原因。</div>
    <section v-if="task" class="detail-summary">
      <TaskConnectionFlow :task="task" :sources="connectionStore.sources" :targets="connectionStore.targets"/><div class="task-strip"><div><span>状态</span><strong class="status-tag" :class="'status-'+task.status.toLowerCase()">{{labels[task.status]}}</strong></div><div><span>同步模式</span><strong>{{task.syncMode==='FULL_INCREMENTAL'?'全量 + 增量':'仅增量'}}</strong></div><div><span>数据表</span><strong>{{task.tables.length}}</strong></div><div><span>任务时区</span><strong>{{task.timeZone}}</strong></div><div><span>配置版本</span><strong>v{{task.version}}</strong></div></div>
    </section>
    </div>
    <section v-if="task" class="panel detail-panel">
      <div class="detail-layout">
        <nav class="detail-nav" aria-label="任务详情"><section v-for="(group,index) in detailGroups" :key="group.title" :aria-labelledby="`detail-group-${index}`"><h2 :id="`detail-group-${index}`" class="dnav-label">{{group.title}}</h2><button v-for="item in group.items" :key="item[0]" type="button" class="dnav-item" :class="{active:active===item[0]}" :aria-current="active===item[0]?'page':undefined" @click="active=item[0]"><component :is="detailIcons[item[0]]" :size="14" aria-hidden="true"/>{{item[1]}}</button></section></nav>
        <div class="detail-main"><select class="detail-mobile-nav" aria-label="任务分区" v-model="active"><optgroup v-for="group in detailGroups" :key="group.title" :label="group.title"><option v-for="item in group.items" :key="item[0]" :value="item[0]">{{item[1]}}</option></optgroup></select>
        <MailSubscriptions v-if="active==='notifications'" :task-id="id"/>
        <CheckpointPanel v-if="active==='checkpoints'" :task="task"/>
        <div v-if="active==='tables'" class="tab-toolbar"><cdc-button v-permit="'operate'" size="small" @click="recoverySourceTable=null;recoveryOpen=true"><RotateCcw :size="14"/>选择目标表重跑</cdc-button></div>
        <RecoveryDialog v-model="recoveryOpen" :task="task" :source-table-id="recoverySourceTable" @submitted="active='checkpoints';load(false)"/>
        <section v-if="active==='overview'"><div class="overview-content"><section class="summary-grid"><div class="summary-item running"><span>累计处理</span><strong>{{totalRows.toLocaleString()}}</strong></div><div class="summary-item"><span>当前最大延迟</span><strong>{{maxLag}} ms</strong></div><div class="summary-item warning"><span>错误次数</span><strong>{{totalErrors}}</strong></div><div class="summary-item"><span>最近事件</span><strong class="small-value">{{task.tables.map(x=>x.lastEventAt).filter(Boolean).sort().at(-1)||'-'}}</strong></div></section><div class="section-title"><h2>运行实例</h2></div><cdc-table :data="instances.slice(0,6)"><cdc-table-column prop="id" label="实例 ID" width="100"/><cdc-table-column label="状态" width="130"><template #default="{row}"><span :class="badgeClass(instanceStatusMeta(row.status).tone)"><i class="dot"/>{{instanceStatusMeta(row.status).label}}</span></template></cdc-table-column><cdc-table-column prop="startedAt" label="开始时间" min-width="190"/><cdc-table-column prop="endedAt" label="结束时间" min-width="190"/><cdc-table-column prop="message" label="说明" min-width="220"/></cdc-table></div></section>
        <section v-if="active==='tables'"><cdc-table :data="task.tables" border><cdc-table-column label="源表" min-width="230"><template #default="{row}"><span class="mono">{{[row.sourceDatabase,row.sourceSchema,row.sourceTable].filter(Boolean).join('.')}}</span></template></cdc-table-column><cdc-table-column label="目标表" min-width="180"><template #default="{row}"><span class="mono">{{[row.targetSchema,row.targetTable].filter(Boolean).join('.')}}</span></template></cdc-table-column><cdc-table-column label="字段" width="90"><template #default="{row}">{{Object.keys(row.columns).length}}</template></cdc-table-column><cdc-table-column label="核对" min-width="180"><template #default="{row}"><span v-if="row.reconciliation?.enabled">{{row.reconciliation.sourceTimeColumn}} · {{row.reconciliation.fields.length}} 字段</span><span v-else>-</span></template></cdc-table-column><cdc-table-column label="主键" min-width="130"><template #default="{row}">{{row.keyColumns.join(', ')}}</template></cdc-table-column><cdc-table-column label="状态" width="120"><template #default="{row}"><span class="status-tag" :class="'status-'+row.status.toLowerCase()">{{row.status}}</span></template></cdc-table-column><cdc-table-column prop="processedRows" label="已处理" width="100"/><cdc-table-column label="最近错误" min-width="220"><template #default="{row}"><span class="danger-text">{{row.lastError||'-'}}</span></template></cdc-table-column><cdc-table-column label="操作" width="90"><template #default="{row}"><cdc-button v-permit="'operate'" size="small" @click="recoverySourceTable=row.id;recoveryOpen=true"><RotateCcw :size="14"/>重跑</cdc-button></template></cdc-table-column></cdc-table></section>
        <section v-if="active==='instances'"><cdc-table :data="instances"><cdc-table-column prop="id" label="实例 ID" width="100"/><cdc-table-column label="状态" width="140"><template #default="{row}"><span :class="badgeClass(instanceStatusMeta(row.status).tone)"><i class="dot"/>{{instanceStatusMeta(row.status).label}}</span></template></cdc-table-column><cdc-table-column prop="startedAt" label="开始时间" min-width="190"/><cdc-table-column prop="endedAt" label="结束时间" min-width="190"/><cdc-table-column prop="message" label="说明" min-width="260"/></cdc-table></section>
        <section v-if="active==='metrics'"><div class="metric-toolbar"><cdc-radio-group v-model="range" size="small" @change="loadTab"><cdc-radio-button value="1h">1 小时</cdc-radio-button><cdc-radio-button value="6h">6 小时</cdc-radio-button><cdc-radio-button value="24h">24 小时</cdc-radio-button><cdc-radio-button value="7d">7 天</cdc-radio-button></cdc-radio-group><cdc-button size="small" @click="loadTab"><RefreshCw :size="14"/>刷新</cdc-button></div><div ref="chartEl" class="chart"></div></section>
        <section v-if="active==='logs'"><cdc-table :data="logs"><cdc-table-column prop="createdAt" label="时间" min-width="190"/><cdc-table-column prop="level" label="级别" width="90"/><cdc-table-column prop="tableId" label="表 ID" width="90"/><cdc-table-column prop="message" label="消息" min-width="450"/></cdc-table><div class="log-pagination"><cdc-pagination v-model:current-page="logPage" :total="logTotal" :page-size="50" layout="total, prev, pager, next" @current-change="loadTab"/></div></section>
        <section v-if="active==='delivery'">
          <div class="tab-toolbar"><cdc-select v-model="deliveryStatus" clearable placeholder="全部状态" style="width:180px" @change="deliveryPage=1;loadTab()"><cdc-option v-for="(label,value) in deliveryLabels" :key="value" :label="label" :value="value"/></cdc-select><cdc-button @click="loadTab"><RefreshCw :size="14"/>刷新</cdc-button></div>
          <cdc-table :data="deliveryBatches.items"><cdc-table-column prop="batchId" label="批次 ID" min-width="270"><template #default="{row}"><span class="mono">{{row.batchId}}</span></template></cdc-table-column><cdc-table-column label="目标订阅" min-width="170"><template #default="{row}">{{batchTarget(row)}}</template></cdc-table-column><cdc-table-column label="状态" width="110"><template #default="{row}"><cdc-tag :type="row.status==='SUCCEEDED'?'success':row.status==='FAILED'?'danger':row.status==='RETRY_WAIT'?'warning':'info'">{{deliveryLabels[row.status]||row.status}}</cdc-tag></template></cdc-table-column><cdc-table-column prop="eventCount" label="事件数" width="100"/><cdc-table-column prop="attemptCount" label="尝试" width="80"/><cdc-table-column label="创建时间" min-width="180"><template #default="{row}">{{formatDateTime(row.createdAt)}}</template></cdc-table-column><cdc-table-column prop="lastError" label="最近错误" min-width="260"/><cdc-table-column label="操作" width="90"><template #default="{row}"><cdc-button v-if="row.status==='FAILED'" link type="primary" v-permit="'operate'" @click.stop="retryBatch(row)">重试</cdc-button></template></cdc-table-column></cdc-table>
          <div class="log-pagination"><cdc-pagination v-model:current-page="deliveryPage" :total="deliveryBatches.total" :page-size="50" layout="total, prev, pager, next" @current-change="loadTab"/></div>
        </section>
        <section v-if="active==='subscriptions'">
          <div class="tab-toolbar"><div><strong>目标订阅</strong><p class="muted">源端按表只采集一次；每个目标独立初始化、投递和数据核对。</p></div><cdc-button type="primary" v-permit="'admin'" @click="createSubscriber">新增目标订阅</cdc-button></div>
          <cdc-empty v-if="!currentSubscriptions.length" :description="cancelledSubscriptions.length?'没有有效目标订阅，已取消记录保留在下方历史区':'尚未配置目标订阅'" :image-size="70"/>
          <section v-for="subscription in currentSubscriptions" :key="subscription.id" class="subscription-card">
            <div class="subscription-card-head"><div><strong>{{subscription.name}}</strong><p>订阅 #{{subscription.id}} · {{subscription.tables.length}} 张目标表 · {{subscriptionStatus(subscription)}}</p><p v-if="subscription.initializedAt">上次初始化完成：{{formatDateTime(subscription.initializedAt)}}</p></div></div><dl class="subscription-meta"><div><dt>目标连接</dt><dd><DatabaseBrand :type="connectionStore.targets.find(t=>t.id===subscription.targetId)?.targetType" :label="targetName(subscription)"/></dd></div><div><dt>目标表数量</dt><dd>{{subscription.tables.length}}</dd></div><div><dt>待投递事件</dt><dd>{{subscription.pendingEvents?.toLocaleString()??'-'}}</dd></div><div><dt>待投递容量</dt><dd>{{subscription.pendingBytes!=null?(subscription.pendingBytes/1024/1024).toFixed(2)+' MB':'-'}}</dd></div></dl><div class="subscription-actions" :inert="subscriptionBusy!==null"><cdc-button size="small" v-permit="'admin'" @click="editSubscription(subscription)">编辑投递映射</cdc-button><cdc-button size="small" v-permit="'admin'" @click="editReconciliationConfig(subscription)">编辑核对配置</cdc-button><cdc-button size="small" v-permit="'operate'" @click="openReconciliation(subscription)">数据核对</cdc-button><cdc-button v-if="subscription.status==='ACTIVE'" size="small" type="warning" v-permit="'operate'" @click="subscriptionAction(subscription,'pause')">暂停</cdc-button><cdc-button v-else-if="subscription.status==='PAUSED'" size="small" type="primary" v-permit="'operate'" @click="subscriptionAction(subscription,'resume')">恢复</cdc-button><cdc-button v-else-if="subscription.status==='FAILED'" size="small" type="danger" v-permit="'operate'" @click="subscriptionAction(subscription,'retry-initialization')">重试初始化</cdc-button><cdc-button v-if="subscription.status!=='CANCELLED'" size="small" type="danger" v-permit="'admin'" @click="subscriptionAction(subscription,'cancel')">取消订阅</cdc-button></div>
            <cdc-alert v-if="subscription.lastError" type="error" :title="subscription.lastError" :closable="false" show-icon class="subscription-error"/>
            <cdc-table :data="subscription.tables" border size="small"><cdc-table-column label="源表" min-width="200"><template #default="{row}"><span class="mono">{{[row.mapping.sourceDatabase,row.mapping.sourceSchema,row.mapping.sourceTable].filter(Boolean).join('.')}}</span></template></cdc-table-column><cdc-table-column label="目标表" min-width="200"><template #default="{row}"><span class="mono">{{[row.mapping.targetSchema,row.mapping.targetTable].filter(Boolean).join('.')}}</span></template></cdc-table-column><cdc-table-column label="投递状态" width="150"><template #default="{row}"><cdc-tag :type="subscriptionTableStatus(subscription,row)==='ACTIVE'?'success':subscriptionTableStatus(subscription,row)==='FAILED'?'danger':subscriptionTableStatus(subscription,row)==='PAUSED'?'warning':'info'">{{subscriptionPhase(subscriptionTableStatus(subscription,row))}}</cdc-tag></template></cdc-table-column><cdc-table-column label="初始化进度" width="160"><template #default="{row}"><span :title="row.initializationCompletedAt?formatDateTime(row.initializationCompletedAt):''">{{initializationLabel({...row,status:subscriptionTableStatus(subscription,row)})}}</span></template></cdc-table-column><cdc-table-column label="数据核对" width="130"><template #default="{row}"><span :class="badgeClass(reconciliationMeta(row.mapping.reconciliation?.enabled).tone)"><i class="dot"/>{{reconciliationMeta(row.mapping.reconciliation?.enabled).label}}</span></template></cdc-table-column><cdc-table-column prop="initializationError" label="最近错误" min-width="200"/></cdc-table>
          </section>

          <details v-if="cancelledSubscriptions.length" class="subscription-history">
            <summary>已取消订阅历史（{{cancelledSubscriptions.length}}）</summary>
            <div class="alert-bar">已取消订阅不再投递，以下积压为取消后保留的历史待处理数据，不代表正在同步。保留记录便于追溯，不会自动重新启用。</div>
            <section v-for="subscription in cancelledSubscriptions" :key="subscription.id" class="subscription-card">
              <div class="subscription-card-head"><div><strong>{{subscription.name}}</strong><p>订阅 #{{subscription.id}} · 已取消</p><p v-if="subscription.initializedAt">上次初始化完成：{{formatDateTime(subscription.initializedAt)}}（历史记录）</p></div>
                <cdc-button size="small" type="primary" v-permit="'operate'" :loading="subscriptionBusy===subscription.id" :disabled="subscriptionBusy!==null||!['STARTING','SNAPSHOTTING','RUNNING','DEGRADED'].includes(task.status)" @click="subscriptionAction(subscription,'resume')"><Play :size="14"/>重新启用</cdc-button>
              </div>
              <p v-if="!['STARTING','SNAPSHOTTING','RUNNING','DEGRADED'].includes(task.status)" class="muted">请先启动或恢复任务，再重新启用此订阅。</p>
              <dl class="subscription-meta"><div><dt>目标连接</dt><dd><DatabaseBrand :type="connectionStore.targets.find(t=>t.id===subscription.targetId)?.targetType" :label="targetName(subscription)"/></dd></div><div><dt>目标表数量</dt><dd>{{subscription.tables.length}}</dd></div><div><dt>历史待处理事件（不再投递）</dt><dd>{{subscription.pendingEvents?.toLocaleString()??'-'}}</dd></div><div><dt>历史待处理容量</dt><dd>{{subscription.pendingBytes!=null?(subscription.pendingBytes/1024/1024).toFixed(2)+' MB':'-'}}</dd></div></dl>
              <cdc-table :data="subscription.tables"><cdc-table-column label="源表"><template #default="{row}">{{[row.mapping.sourceDatabase,row.mapping.sourceSchema,row.mapping.sourceTable].filter(Boolean).join('.')}}</template></cdc-table-column><cdc-table-column label="目标表"><template #default="{row}">{{[row.mapping.targetSchema,row.mapping.targetTable].filter(Boolean).join('.')}}</template></cdc-table-column><cdc-table-column label="投递状态"><template #default><cdc-tag type="info">已取消</cdc-tag></template></cdc-table-column><cdc-table-column label="历史初始化"><template #default="{row}">{{initializationLabel({...row,status:'CANCELLED'})}}</template></cdc-table-column></cdc-table>
            </section>
          </details>
        </section>
        <section v-if="active==='reconciliation'">
          <div class="tab-toolbar tab-toolbar-actions"><cdc-select v-model="reconciliationScope" clearable placeholder="默认目标" style="max-width:240px" @change="selectReconciliationScope"><cdc-option v-for="subscription in targetSubscriptions" :key="subscription.id" :label="subscription.name" :value="String(subscription.id)"/></cdc-select><cdc-button @click="loadTab"><RefreshCw :size="14"/>刷新</cdc-button><cdc-button type="primary" :disabled="!reconciliationTables.length" v-permit="'operate'" @click="openReconciliation(subscriptionReconciliation)">发起核对</cdc-button></div>
          <cdc-alert v-if="!reconciliationTables.length" title="尚未配置数据核对规则" type="info" :closable="false" show-icon/>
          <cdc-table :data="reconciliations" highlight-current-row @row-click="showRun">
            <cdc-table-column prop="id" label="运行 ID" width="95"/><cdc-table-column label="核对时间" min-width="180"><template #default="{row}">{{formatDateTime(row.startedAt)}}</template></cdc-table-column><cdc-table-column label="模式" width="120"><template #default="{row}">{{modeLabels[row.mode]||row.mode}}</template></cdc-table-column><cdc-table-column label="触发" width="100"><template #default="{row}">{{triggerLabels[row.triggerSource]||row.triggerSource}}</template></cdc-table-column><cdc-table-column label="窗口" min-width="280"><template #default="{row}">{{formatWindow(row)}}</template></cdc-table-column><cdc-table-column label="状态" width="110"><template #default="{row}"><cdc-tag :type="row.status==='MATCHED'?'success':row.status==='MISMATCHED'?'danger':row.status.includes('FAILED')?'warning':'info'">{{reconciliationStatus[row.status]||row.status}}</cdc-tag></template></cdc-table-column><cdc-table-column prop="mismatchCount" label="明细差异" width="100"/><cdc-table-column prop="message" label="说明" min-width="160"/>
          </cdc-table>
          <section v-if="selectedRun" class="run-detail" v-loading="selectedRunLoading">
            <div class="section-title"><h2>运行 {{selectedRun.run.id}} 结果</h2><span>{{formatWindow(selectedRun.run)}}</span></div>
            <div v-for="detail in selectedRun.details" :key="detail.id" class="table-result">
              <div class="table-result-head"><strong>{{tableName(detail.tableId)}}</strong><cdc-tag :type="detail.status==='MATCHED'?'success':detail.status==='FAILED'?'warning':'danger'">{{reconciliationStatus[detail.status]||detail.status}}</cdc-tag><span>源 {{detail.sourceRows}} / 目标 {{detail.targetRows}}</span></div>
              <div class="difference-cards"><div><span>目标缺失</span><strong>{{detail.targetMissing}}</strong></div><div><span>目标冗余</span><strong>{{detail.targetRedundant}}</strong></div><div><span>字段不一致</span><strong>{{detail.fieldMismatch}}</strong></div><div><span>删除残留</span><strong>{{detail.deleteResidue}}</strong></div></div>
              <cdc-table :data="detail.fields" size="small" border><cdc-table-column prop="sourceColumn" label="源字段" min-width="130"/><cdc-table-column prop="targetColumn" label="目标字段" min-width="130"/><cdc-table-column prop="logicalType" label="类型" width="100"/><cdc-table-column prop="metric" label="指标" width="120"/><cdc-table-column prop="sourceValue" label="源值" min-width="150"/><cdc-table-column prop="targetValue" label="目标值" min-width="150"/><cdc-table-column label="一致" width="80"><template #default="{row}"><cdc-tag size="small" :type="row.matched?'success':'danger'">{{row.matched?'是':'否'}}</cdc-tag></template></cdc-table-column></cdc-table>
            </div>
            <div class="discrepancy-toolbar"><strong>差异明细</strong><cdc-select v-model="discrepancyType" clearable placeholder="全部类型" style="width:180px" @change="discrepancyPage=1;loadDiscrepancies()"><cdc-option v-for="(label,value) in discrepancyLabels" :key="value" :label="label" :value="value"/></cdc-select></div>
            <cdc-table :data="discrepancies.items" border><cdc-table-column label="表" min-width="160"><template #default="{row}">{{tableName(row.tableId)}}</template></cdc-table-column><cdc-table-column label="类型" width="130"><template #default="{row}"><span :class="badgeClass(discrepancyTone(row.type))"><i class="dot"/>{{discrepancyLabels[row.type]||row.type}}</span></template></cdc-table-column><cdc-table-column label="主键" min-width="180"><template #default="{row}"><span class="mono">{{formatValue(row.key)}}</span></template></cdc-table-column><cdc-table-column prop="fieldName" label="字段" min-width="120"/><cdc-table-column label="源值" min-width="160"><template #default="{row}">{{formatValue(row.sourceValue)}}</template></cdc-table-column><cdc-table-column label="目标值" min-width="160"><template #default="{row}">{{formatValue(row.targetValue)}}</template></cdc-table-column></cdc-table>
            <div class="log-pagination"><cdc-pagination v-model:current-page="discrepancyPage" :total="discrepancies.total" :page-size="50" layout="total, prev, pager, next" @current-change="loadDiscrepancies"/></div>
          </section>
        </section>
      </div></div>
    </section>
  </div>
  <cdc-modal v-model="reconciliationDialog" title="发起数据核对" width="min(820px,94vw)">
    <cdc-steps :active="reconciliationStep"><cdc-step title="模式与范围"/><cdc-step title="目标与表"/><cdc-step title="确认提交"/></cdc-steps>
    <cdc-form label-position="top">
      <template v-if="reconciliationStep===0"><cdc-form-item label="核对模式"><cdc-radio-group v-model="reconciliationForm.mode"><cdc-radio-button value="INCREMENTAL">增量窗口</cdc-radio-button><cdc-radio-button value="RANGE">指定范围</cdc-radio-button><cdc-radio-button value="FULL">全量</cdc-radio-button></cdc-radio-group></cdc-form-item>
      <cdc-form-item v-if="reconciliationForm.mode==='RANGE'" label="时间范围" required><cdc-date-picker v-model="reconciliationForm.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" style="width:100%"/></cdc-form-item>
      </template><template v-if="reconciliationStep===1"><cdc-form-item label="目标订阅"><cdc-select v-model="reconciliationScope" clearable placeholder="默认目标" @change="selectReconciliationScope"><cdc-option v-for="subscription in targetSubscriptions" :key="subscription.id" :label="subscription.name" :value="String(subscription.id)"/></cdc-select></cdc-form-item><cdc-form-item label="核对表"><cdc-select v-model="reconciliationForm.tableIds" multiple collapse-tags placeholder="全部已配置表" style="width:100%"><cdc-option v-for="row in reconciliationTables" :key="row.id" :label="[row.sourceDatabase,row.sourceSchema,row.sourceTable].filter(Boolean).join('.')" :value="row.id"/></cdc-select></cdc-form-item>
</template><template v-if="reconciliationStep===2"><div class="source-summary"><p>目标：{{subscriptionReconciliation?.name||'默认目标'}}</p><p>模式：{{modeLabels[reconciliationForm.mode]}} · {{reconciliationForm.tableIds.length||reconciliationTables.length}} 张表</p></div><cdc-alert v-if="reconciliationForm.mode==='FULL'" type="warning" title="全量核对会扫描源端和目标端，请确认查询负载与执行时间。"/>
      <cdc-form-item label="异常明细定位"><cdc-switch v-model="reconciliationForm.drillDown" active-text="启用" inactive-text="关闭"/></cdc-form-item>
    </template></cdc-form>
    <template #footer><cdc-button @click="reconciliationDialog=false">取消</cdc-button><cdc-button v-if="reconciliationStep>0" @click="reconciliationStep--">上一步</cdc-button><cdc-button v-if="reconciliationStep<2" type="primary" @click="nextReconciliation">下一步</cdc-button><cdc-button v-else type="primary" :loading="submittingReconciliation" @click="submitReconciliation">提交核对</cdc-button></template>
  </cdc-modal>

  <SubscriptionWizard v-if="task" v-model="subscriptionWizard" :task-id="id" :subscription="editingSubscription" @saved="load"/>
  <ReconciliationConfigDialog v-if="selectedSubscription" v-model="reconciliationConfigDialog" :task-id="id" :subscription="selectedSubscription" @saved="load"/>
</template>
