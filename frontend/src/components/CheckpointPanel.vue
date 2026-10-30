<script setup>
import { computed,nextTick,onMounted,onBeforeUnmount,ref,watch } from 'vue'
import { createChart, chartStyle, chartAxis } from '../chartStyle'
import { RefreshCw,RotateCcw,Stethoscope } from 'lucide-vue-next'
import { api } from '../api'
import { can } from '../auth'
import RecoveryDialog from './RecoveryDialog.vue'
const props=defineProps({task:Object}),data=ref({tables:[]}),history=ref([]),jobs=ref([]),range=ref('6h'),busy=ref(false),error=ref(''),diagnostics=ref([]),dialog=ref(false),chartEl=ref()
const statuses={QUEUED:'排队',INITIALIZING:'全量初始化',REPLAYING:'回放中',CATCHING_UP:'追平中',PAUSED:'已暂停',SUCCEEDED:'成功',FAILED:'失败',CANCELLED:'已取消'}
const diagnosisLabels={UNKNOWN:'无法验证',HEALTHY:'检查通过',LOG_PURGED:'源日志已清理',SOURCE_CHANGED:'源身份变化',PERMISSION_DENIED:'权限不足',NETWORK_ERROR:'网络失败',AUTH_FAILED:'认证失败',LOCAL_FILE_ERROR:'本地文件缺失或损坏'}
const groups=computed(()=>[...new Map(data.value.tables.map(t=>[t.CAPTURE_GROUP_ID,t])).values()])
let chart,request=0,timer
const date=v=>v?new Date(v).toLocaleString():'未知'
const position=v=>v||'未知'
async function load(){const id=++request;busy.value=true;try{const [current,points,recoveries]=await Promise.all([api(`/tasks/${props.task.id}/checkpoints`),api(`/tasks/${props.task.id}/checkpoints/history?range=${range.value}`),api(`/tasks/${props.task.id}/recoveries`)]);if(id===request){data.value=current;history.value=points;jobs.value=recoveries;error.value='';await nextTick();draw()}}catch(e){if(id===request)error.value=e.message}finally{if(id===request)busy.value=false}}
async function diagnose(){busy.value=true;try{diagnostics.value=await api(`/tasks/${props.task.id}/checkpoints/diagnose`,{method:'POST'});await load()}catch(e){error.value=e.message}finally{busy.value=false}}
async function action(job,name){try{await api(`/tasks/${props.task.id}/recoveries/${job.ID}/actions/${name}`,{method:'POST'});await load()}catch(e){error.value=e.message}}
function draw(){
  if(!chartEl.value)return
  if(!chartEl.value.clientWidth)return
  chart ||= createChart(chartEl.value)
  const ids=[...new Set(history.value.map(x=>x.SUBSCRIPTION_TABLE_ID))]
  const names=Object.fromEntries(data.value.tables.map(t=>[t.SUBSCRIPTION_TABLE_ID,t.SUBSCRIPTION_NAME+' / '+t.SOURCE_TABLE]))
  chart.setOption({
    ...chartStyle(),
    xAxis:{...chartAxis(),type:'time',splitLine:{show:false}},
    yAxis:[{...chartAxis(),type:'value',name:'积压事件',minInterval:1},{...chartAxis(),type:'value',name:'延迟 / 等待 ms',splitLine:{show:false}}],
    series:ids.flatMap(id=>[
      {name:(names[id]||String(id))+' 采集延迟',type:'line',yAxisIndex:1,showSymbol:false,data:history.value.filter(x=>x.SUBSCRIPTION_TABLE_ID===id).map(x=>[x.SAMPLED_AT,x.CAPTURE_LAG_MS])},
      {name:names[id]||String(id),type:'line',showSymbol:false,data:history.value.filter(x=>x.SUBSCRIPTION_TABLE_ID===id).map(x=>[x.SAMPLED_AT,x.PENDING_EVENTS])},
      {name:(names[id]||String(id))+' 等待',type:'line',yAxisIndex:1,showSymbol:false,lineStyle:{type:'dashed'},data:history.value.filter(x=>x.SUBSCRIPTION_TABLE_ID===id).map(x=>[x.SAMPLED_AT,x.DELIVERY_WAIT_MS])}
    ]).map(series=>({...series,showSymbol:series.data.length===1,symbolSize:5,lineStyle:{width:2,...series.lineStyle}}))
  },true)
}
function resize(){chart?.resize()}
function refresh(){clearTimeout(timer);timer=setTimeout(load,500)}
watch(range,load)
onMounted(()=>{load();window.addEventListener('cdc-runtime-change',refresh);window.addEventListener('cdc-monitor-change',refresh);window.addEventListener('cdc-theme-change',draw);window.addEventListener('resize',resize)})
onBeforeUnmount(()=>{request++;clearTimeout(timer);chart?.dispose();window.removeEventListener('cdc-runtime-change',refresh);window.removeEventListener('cdc-monitor-change',refresh);window.removeEventListener('cdc-theme-change',draw);window.removeEventListener('resize',resize)})
</script>
<template><section><div class="tab-toolbar"><cdc-radio-group v-model="range"><cdc-radio-button value="1h">1 小时</cdc-radio-button><cdc-radio-button value="6h">6 小时</cdc-radio-button><cdc-radio-button value="24h">24 小时</cdc-radio-button></cdc-radio-group><span class="toolbar-spacer"/><cdc-button size="small" :loading="busy" @click="load"><RefreshCw :size="14"/>刷新</cdc-button><cdc-button v-if="can()" size="small" :disabled="busy" @click="diagnose"><Stethoscope :size="14"/>诊断</cdc-button><cdc-button v-if="can()" size="small" @click="dialog=true"><RotateCcw :size="14"/>按时间回放 / 重跑</cdc-button></div><div class="alert-bar">采集位点与目标投递位点独立展示。事件数仅统计可确认的本地积压；未知时间不推算，源端空闲不代表持续落后。</div><div v-if="error" class="alert-bar error" role="alert">{{error}}</div><div class="checkpoint-cards"><article v-for="g in groups" :key="g.CAPTURE_GROUP_ID" class="checkpoint-card"><h3>源采集组 #{{g.CAPTURE_GROUP_ID}} · {{g.SOURCE_TABLE}}</h3><span class="badge badge-info">{{data.sourceType==='STARROCKS'?'快照差异轮询':data.sourceType==='SQLSERVER'?'Commit / Change LSN':'Binlog File / Position / Row'}}</span><div class="checkpoint-value">{{position(g.CAPTURE_POSITION_JSON)}}</div><div class="checkpoint-meta">最后持久化采集：{{date(g.lastCapture?.CAPTURED_TO)}}<br/>源事件时间：{{date(g.lastCapture?.SOURCE_EVENT_AT)}}<br/>源端最新位置：{{g.diagnosis?.latest||'未知（点击诊断检查）'}}<br/>保留区间：{{g.availableFrom?date(g.availableFrom)+' 至 '+date(g.availableTo):'尚无可验证的本地历史'}}<br/>心跳：{{date(g.heartbeatAt)}}</div></article></div><div class="table-wrap checkpoint-table"><table><thead><tr><th>目标订阅 / 源表</th><th>投递位点</th><th>本地待投递</th><th>最老积压</th><th>目标完成时间</th></tr></thead><tbody><tr v-for="t in data.tables" :key="t.SUBSCRIPTION_TABLE_ID"><td>{{t.SUBSCRIPTION_NAME}}<div class="dim">{{t.SOURCE_TABLE}}</div></td><td class="mono">{{position(t.DELIVERY_POSITION_JSON)}}</td><td class="mono">{{t.backlog.EVENTS}} 事件 / {{t.backlog.BATCHES}} 批次<br/>{{t.backlog.BYTES}} B</td><td>{{date(t.backlog.OLDEST)}}</td><td>{{date(t.DELIVERY_AT)}}</td></tr><tr v-if="!data.tables.length"><td colspan="5" class="empty-state">暂无位点数据</td></tr></tbody></table></div><h3 class="section-heading">位点健康趋势</h3><div v-if="!history.length" class="empty-state">暂无历史采样，任务活动时每分钟记录一次</div><div v-show="history.length" ref="chartEl" class="checkpoint-chart"/><div v-for="d in diagnostics" :key="d.captureGroupId" class="diagnosis-box"><strong>{{d.database}}.{{d.table}} · {{diagnosisLabels[d.status]||d.status}}</strong><p>{{d.reason||'源端只读查询已完成；位点有效性以保留范围及源身份比对为准。'}}</p><div class="mono dim">最早：{{d.earliest||'无法验证'}} / 最新：{{d.latest||'未知'}} · {{date(d.checkedAt)}}</div><p>{{d.suggestion||'无法验证时请检查连接权限和日志保留配置，不会自动跳过缺失日志。'}}</p></div><section class="checkpoint-jobs"><h3 class="section-heading">恢复作业</h3><div class="table-wrap"><table><thead><tr><th>作业</th><th>目标表映射</th><th>阶段</th><th>独立处理量 / 游标</th><th>错误</th><th>操作</th></tr></thead><tbody><tr v-for="j in jobs" :key="j.ID"><td class="mono">{{j.ID}}<div class="dim">{{date(j.CREATED_AT)}}</div></td><td>#{{j.SUBSCRIPTION_TABLE_ID}}</td><td><span class="badge" :class="j.STATUS==='FAILED'?'badge-danger':j.STATUS==='SUCCEEDED'?'badge-success':'badge-info'">{{statuses[j.STATUS]||j.STATUS}}</span></td><td class="mono">{{j.PROCESSED_EVENTS}} / {{j.CURSOR_SEQUENCE}}</td><td>{{j.LAST_ERROR||'—'}}</td><td><div v-if="can()" class="row-actions"><cdc-button v-if="!['SUCCEEDED','CANCELLED','FAILED','PAUSED'].includes(j.STATUS)" size="small" @click="action(j,'pause')">暂停</cdc-button><cdc-button v-if="j.STATUS==='PAUSED'" size="small" @click="action(j,'resume')">恢复</cdc-button><cdc-button v-if="j.STATUS==='FAILED'" size="small" @click="action(j,'retry')">重试</cdc-button></div></td></tr><tr v-if="!jobs.length"><td colspan="6" class="empty-state">暂无恢复作业</td></tr></tbody></table></div></section><RecoveryDialog v-model="dialog" :task="task" default-mode="TIME" @submitted="load"/></section></template>
