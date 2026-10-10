<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { CdcMessage } from '../components/ui/feedback'
import { Bell, Check, CheckCheck, RefreshCw } from 'lucide-vue-next'
import { api } from '../api'
import { useAppStore } from '../stores/app'
import MailSubscriptions from '../components/MailSubscriptions.vue'

const store=useAppStore(), alerts=ref([]), loading=ref(false), status=ref('')
const tab=ref('alerts')
const mailPanel=ref()
const refreshCurrent=()=>tab.value==='alerts'?load():mailPanel.value?.load()
const filters=[{value:'',label:'全部',color:'warning'},{value:'OPEN',label:'待处理',key:'open'},{value:'ACKNOWLEDGED',label:'已确认',key:'ack'},{value:'RESOLVED',label:'已解决',key:'resolved',color:'running'}]
const visibleAlerts=computed(()=>status.value?alerts.value.filter(row=>row.status===status.value):alerts.value)
const labels={TASK_FAILURE:'任务失败',TABLE_FAILURE:'表同步失败',DDL_CHANGE:'表结构变化',CHECKPOINT_EXPIRED:'位点失效',DATA_DELAY:'数据延迟',RECONCILIATION_MISMATCH:'对账不一致'}
const statusLabels={OPEN:'待处理',ACKNOWLEDGED:'已确认',RESOLVED:'已解决'}
const statusColors={OPEN:'warning',ACKNOWLEDGED:'info',RESOLVED:'success'}
const typeColors={TASK_FAILURE:'danger',TABLE_FAILURE:'danger',DDL_CHANGE:'info',CHECKPOINT_EXPIRED:'warning',DATA_DELAY:'warning',RECONCILIATION_MISMATCH:'danger'}
const date=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'-'
const counts=computed(()=>({open:alerts.value.filter(x=>x.status==='OPEN').length,ack:alerts.value.filter(x=>x.status==='ACKNOWLEDGED').length,resolved:alerts.value.filter(x=>x.status==='RESOLVED').length}))
const refresh=()=>load()
onMounted(()=>{load();window.addEventListener('cdc-runtime-change',refresh)})
onBeforeUnmount(()=>window.removeEventListener('cdc-runtime-change',refresh))
async function load(){loading.value=true;try{alerts.value=await api('/alerts');await store.loadAlerts()}catch(e){CdcMessage.error(e.message)}finally{loading.value=false}}
async function action(row,name){try{await api(`/alerts/${row.id}/${name}`,{method:'POST'});CdcMessage.success(name==='acknowledge'?'告警已确认':'告警已解决');await load()}catch(e){CdcMessage.error(e.message)}}
</script>

<template>

  <div class="page-controls"><div class="tabs" aria-label="告警中心分区"><button class="tab" :class="{active:tab==='alerts'}" @click="tab='alerts'">告警列表</button><button class="tab" :class="{active:tab==='rules'}" @click="tab='rules'">订阅规则</button><button class="tab" :class="{active:tab==='history'}" @click="tab='history'">邮件历史</button></div><cdc-button @click="refreshCurrent"><RefreshCw :size="15"/>刷新</cdc-button></div>
  <MailSubscriptions v-if="tab==='rules'" ref="mailPanel" hide-refresh/><MailSubscriptions v-else-if="tab==='history'" ref="mailPanel" hide-refresh history/>
  <section v-if="tab==='alerts'" class="summary-grid" aria-label="告警状态筛选"><button v-for="filter in filters" :key="filter.value" type="button" class="summary-item alert-filter" :class="[filter.color,{selected:status===filter.value}]" :aria-pressed="status===filter.value" @click="status=filter.value"><span>{{filter.label}}</span><strong>{{filter.key?counts[filter.key]:alerts.length}}</strong></button></section>
  <section v-if="tab==='alerts'" class="panel"><cdc-table v-loading="loading" :data="visibleAlerts"><cdc-table-column label="类型" width="140"><template #default="{row}"><cdc-tag :type="typeColors[row.type]||'info'" size="small">{{labels[row.type]||row.type}}</cdc-tag></template></cdc-table-column><cdc-table-column label="告警" min-width="300"><template #default="{row}"><strong>{{row.title}}</strong><div class="muted alert-message">{{row.message}}</div></template></cdc-table-column><cdc-table-column label="任务" width="110"><template #default="{row}"><router-link :to="'/tasks/'+row.taskId">#{{row.taskId}}</router-link></template></cdc-table-column><cdc-table-column label="发生时间" min-width="190"><template #default="{row}"><span class="mono">{{date(row.createdAt)}}</span></template></cdc-table-column><cdc-table-column label="状态" width="140"><template #default="{row}"><cdc-tag :type="statusColors[row.status]">{{statusLabels[row.status]||row.status}}</cdc-tag></template></cdc-table-column><cdc-table-column label="操作" width="180"><template #default="{row}"><div class="row-actions"><cdc-button v-if="row.status==='OPEN'" size="small" @click="action(row,'acknowledge')"><Check :size="14"/>确认</cdc-button><cdc-button v-if="row.status!=='RESOLVED'" size="small" type="primary" plain @click="action(row,'resolve')"><CheckCheck :size="14"/>解决</cdc-button></div></template></cdc-table-column><template #empty><div class="empty-state"><Bell :size="34"/><div>{{status?'当前筛选下没有告警':'当前没有告警'}}</div></div></template></cdc-table></section>
</template>

<style scoped>
.alert-filter { cursor:pointer; transition:background .15s,border-color .15s; }
.alert-filter:hover { background:var(--muted); }
.alert-filter.selected { border-color:var(--foreground); background:var(--muted); }
.alert-filter.selected span { color:var(--foreground); }
.alert-filter:focus-visible { outline:2px solid var(--foreground); outline-offset:3px; }
</style>
