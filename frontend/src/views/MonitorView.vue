<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { Activity, Zap, Clock, BarChart3, RefreshCw, AlertTriangle } from 'lucide-vue-next'
import { api } from '../api'
import { useAppStore } from '../stores/app'
const store=useAppStore(), range=ref('1h'), live=ref(true), onlyRunning=ref(false), page=ref(1)
const data=ref(null), error=ref(''), loading=ref(false)
const summary=computed(()=>data.value?.summary||{}), items=computed(()=>data.value?.items||[])
const labels={DRAFT:'草稿',STARTING:'启动中',SNAPSHOTTING:'全量同步',RUNNING:'运行中',DEGRADED:'部分异常',FAILED:'失败',PAUSED:'已暂停',STOPPED:'已停止'}
const number=value=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:1})
const date=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'
const delay=value=>value==null?'—':`${number(value/1000)} s`
let timer, autoTimer, disposed=false, active=false, queued=false, queuedAuto=false, generation=0, lastAuto=0
const isCollecting=task=>['STARTING','SNAPSHOTTING','RUNNING','DEGRADED'].includes(task.status)
async function load(automatic=false){
  automatic=automatic===true
  if(disposed)return
  if(active){if(automatic)queuedAuto=true;else queued=true;return}
  if(automatic)lastAuto=Date.now()
  active=true;loading.value=true;const version=generation
  try{const result=await api(`/monitor?range=${range.value}&onlyRunning=${onlyRunning.value}&page=${page.value-1}&size=20`)
    if(!disposed&&version===generation){data.value=result;page.value=result.page+1;error.value=''}
  }catch(e){if(!disposed&&version===generation)error.value=e.message}
  finally{active=false;if(!disposed)loading.value=false;if(queued&&!disposed){queued=false;queuedAuto=false;load()}else if(queuedAuto&&!disposed){queuedAuto=false;runtime()}}
}
function changeRange(value){range.value=value;generation++;load()}
function filter(){page.value=1;generation++;load()}
function turnPage(value){page.value=value;generation++;load()}
function cancelAutomatic(){clearTimeout(autoTimer);autoTimer=undefined;queuedAuto=false}
function toggleLive(){live.value=!live.value;if(live.value)load();else{generation++;queued=false;cancelAutomatic()}}
function runtime(){
  if(disposed||!live.value||document.hidden||autoTimer)return
  autoTimer=setTimeout(()=>{autoTimer=undefined;if(live.value&&!document.hidden)load(true)},Math.max(500,lastAuto+5000-Date.now()))
}
function visibility(){if(document.hidden){cancelAutomatic();generation++}else if(live.value)load()}
function points(trend){
  if(!trend?.length)return ''
  const max=Math.max(1,...trend.map(p=>p.value))
  return trend.map((p,i)=>`${trend.length===1?150:i*300/(trend.length-1)},${38-p.value/max*32}`).join(' ')
}
onMounted(()=>{load();timer=setInterval(runtime,60000);window.addEventListener('cdc-runtime-change',runtime);window.addEventListener('cdc-monitor-change',runtime);document.addEventListener('visibilitychange',visibility)})
onBeforeUnmount(()=>{disposed=true;clearInterval(timer);cancelAutomatic();window.removeEventListener('cdc-runtime-change',runtime);window.removeEventListener('cdc-monitor-change',runtime);document.removeEventListener('visibilitychange',visibility)})
</script>
<template>
  <section class="monitor-page">
    <div class="monitor-controls"><div class="range-tabs" aria-label="监控时间范围"><button v-for="r in ['5m','1h','6h','24h']" :key="r" :class="{active:range===r}" :aria-pressed="range===r" @click="changeRange(r)">{{r}}</button></div><button class="live-chip" :class="{paused:!live}" :aria-pressed="live" @click="toggleLive"><i/>{{live?'实时刷新':'已暂停刷新'}}</button><button class="console-btn" :disabled="loading" @click="load"><RefreshCw :size="14" :class="{spin:loading}"/>刷新</button></div>
    <div v-if="error" class="alert-bar error" role="alert">{{error}}<span v-if="data"> · 已保留最近成功数据</span></div>
    <div v-if="loading&&!data" class="loading-line" role="status">正在加载任务监控…</div>
    <template v-if="data">
      <div v-if="summary.openAlerts" class="global-alert-bar"><AlertTriangle :size="20"/><div><strong>{{summary.openAlerts}} 条待处理告警</strong><p>最近一条：{{summary.latestAlert?.title}} · {{date(summary.latestAlert?.createdAt)}}</p></div><RouterLink to="/alerts" class="btn btn-outline btn-sm">前往处理</RouterLink></div>
      <div class="mon-kpis">
        <div class="mon-kpi"><div class="mon-kpi-label"><Activity :size="14"/>运行中的采集任务</div><div class="mon-kpi-value">{{summary.collecting}}<small>/ {{summary.total}}</small></div><div class="mon-kpi-foot">启动、全量、运行及部分异常任务</div></div>
        <div class="mon-kpi"><div class="mon-kpi-label"><Zap :size="14"/>总投递吞吐</div><div class="mon-kpi-value success-text">{{number(summary.writeRate)}}<small>rows/s</small></div><div class="mon-kpi-foot">有效采样的任务汇总</div></div>
        <div class="mon-kpi"><div class="mon-kpi-label"><Clock :size="14"/>最大延迟</div><div class="mon-kpi-value" :class="{'warning-text':summary.maxLagExceeded}">{{delay(summary.maxLagMs)}}</div><div class="mon-kpi-foot">{{summary.maxLagTask||'暂无有效采样'}}{{summary.maxLagExceeded?' · 超过任务阈值':''}}</div></div>
        <div class="mon-kpi"><div class="mon-kpi-label"><BarChart3 :size="14"/>最近一小时处理</div><div class="mon-kpi-value info-text">{{number(summary.hourRows)}}<small>行</small></div><div class="mon-kpi-foot">{{summary.hourRows==null?'暂无足够采样':'根据累计处理有效增量计算'}}</div></div>
      </div>
      <div class="mon-section-head"><h2>任务实时状态</h2><label class="inline-check"><input type="checkbox" v-model="onlyRunning" @change="filter">仅显示运行中</label></div>
      <div class="mon-grid"><RouterLink v-for="task in items" :key="task.id" :to="`/tasks/${task.id}`" class="mon-card" :class="{alert:task.status==='FAILED',warn:task.status==='DEGRADED'}">
        <div class="mon-card-head"><div><strong>{{task.name}}</strong><p>{{task.source}} → {{task.target}}</p></div><span class="status-badge" :class="`status-${task.status.toLowerCase()}`"><i/>{{labels[task.status]||task.status}}</span></div>
        <div class="mon-card-body"><div><span>投递吞吐</span><strong>{{number(task.writeRate)}}<small v-if="task.writeRate!=null">r/s</small></strong></div><div><span>延迟</span><strong :class="{'warning-text':task.lagMs>task.alertLagSeconds*1000}">{{delay(task.lagMs)}}</strong></div><div><span>1h 处理</span><strong>{{number(task.hourRows)}}</strong></div></div>
        <div class="mon-chart" :class="task.status==='FAILED'?'danger-text':task.status==='DEGRADED'?'warning-text':task.status==='SNAPSHOTTING'?'info-text':'success-text'"><svg v-if="task.trend.length" viewBox="0 0 300 44" preserveAspectRatio="none" role="img" :aria-label="`${task.name} ${range} 投递吞吐趋势`"><polyline :points="points(task.trend)" fill="none" stroke="currentColor" stroke-width="1.6" vector-effect="non-scaling-stroke"/></svg><span v-else class="muted">暂无趋势采样</span></div>
        <div class="mon-card-foot"><span v-if="task.lastError" class="danger-text" :title="task.lastError">{{task.lastError}}</span><span v-else-if="!isCollecting(task)" class="muted">未运行 · 已停止采样</span><span v-else-if="task.stale" class="muted">{{task.sampledAt?'采样已过期':'暂无采样'}}</span><span v-else>最近事件 {{date(task.lastEventAt)}}</span><span class="mono muted" :title="date(task.sampledAt)">采样 {{task.sampledAt?new Date(task.sampledAt).toLocaleTimeString('zh-CN',{hour12:false}):'—'}}</span></div>
      </RouterLink></div>
      <div v-if="!items.length" class="empty-state">{{summary.total?'当前筛选下没有任务':'尚未创建同步任务'}}</div>
      <div v-if="data.total" class="table-footer"><span class="muted">共 {{data.total}} 个任务 · 任务汇总，目标明细请进入任务详情</span><div class="pager"><button class="icon-btn" :disabled="page<=1" aria-label="上一页" @click="turnPage(page-1)">‹</button><span>{{page}}</span><button class="icon-btn" :disabled="page*20>=data.total" aria-label="下一页" @click="turnPage(page+1)">›</button></div></div>
      <section class="mon-events"><header><h2>实时事件流</h2><span class="live-chip" :class="{paused:!live||!store.connected}"><i/>{{!live?'PAUSED':store.connected?'LIVE':'连接中断'}}</span><span class="muted">最近 {{data.events.length}} 条</span></header><div class="mon-events-body"><div v-for="event in data.events" :key="event.id" class="mon-event-row"><time class="mono muted">{{date(event.createdAt)}}</time><span :class="event.level==='ERROR'?'danger-text':event.level==='WARN'?'warning-text':'info-text'">{{event.level}}</span><RouterLink :to="`/tasks/${event.taskId}`">{{event.taskName}}</RouterLink><span :class="{'danger-text':event.level==='ERROR','warning-text':event.level==='WARN'}">{{event.message}}</span></div><div v-if="!data.events.length" class="empty-state">暂无任务事件</div></div></section>
      <p class="monitor-updated muted">最近更新 {{date(data.updatedAt)}} · 指标每 {{number(data.sampleMs/1000)}} 秒采样</p>
    </template>
  </section>
</template>
