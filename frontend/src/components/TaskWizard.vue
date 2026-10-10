<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { CdcMessage, CdcMessageBox } from './ui/feedback'
import { CheckCircle2, Database, Link2, SlidersHorizontal } from 'lucide-vue-next'
import { api } from '../api'
import { useAppStore } from '../stores/app'
import FieldMappingDialog from './FieldMappingDialog.vue'

const props = defineProps({ modelValue: Boolean, task: Object, subscription: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue', 'saved'])
const store = useAppStore()
const step = ref(0), dirty = ref(false), loading = ref(false), validating = ref(false)
const databases = ref([]), sourceTables = ref([]), targetTables = ref([]), targetTopics = ref([]), currentDatabase = ref('')
const report = ref(null), mappingOpen = ref(false), mappingIndex = ref(-1)
const form = reactive(blank())
const confirmedSourceId = ref(null), confirmedTargetId = ref(null)
const initializing = ref(false)

function blank() { return { id:null,name:'',description:'',sourceId:null,targetId:null,additionalTargetIds:[],syncMode:'FULL_INCREMENTAL',ddlPolicy:'DETECT_AND_BLOCK',timeZone:'Asia/Shanghai',version:0,alertLagSeconds:60,reconciliationCron:'',reconciliationDelayMinutes:120,reconciliationWindowMinutes:60,reconcileAfterSnapshot:true,tables:[] } }
watch(() => props.modelValue, async open => {
  if (!open) return
  initializing.value = true
  try {
    await store.loadConnections()
    Object.assign(form, blank(), props.task ? JSON.parse(JSON.stringify(props.task)) : {})
    form.tables ||= []
    step.value = 0; report.value = null; dirty.value = false
    if (form.sourceId) await loadSourceMetadata(true)
    if (form.targetId) await loadTargetMetadata()
  } catch (error) { CdcMessage.error(error.message) }
  finally {
    confirmedSourceId.value = form.sourceId
    confirmedTargetId.value = form.targetId
    await nextTick()
    dirty.value = false
    initializing.value = false
  }
}, { immediate: true })
watch(form, () => { if (props.modelValue && !initializing.value) dirty.value = true }, { deep:true })
const source = computed(() => store.sources.find(x => x.id === form.sourceId))
const target = computed(() => store.targets.find(x => x.id === form.targetId))
const isKafka = computed(() => target.value?.targetType === 'KAFKA')
const targetOptions = computed(() => targetTables.value.map(x => ({ ...x, key:(x.schema ? x.schema+'.' : '')+x.table })))
const mappedCount = computed(() => form.tables.filter(x => Object.keys(x.columns || {}).length).length)

async function loadSourceMetadata(keep = false) {
  if (!form.sourceId) return
  loading.value = true
  if (!keep) { databases.value=[]; sourceTables.value=[] }
  try {
    databases.value = await api(`/sources/${form.sourceId}/databases`)
    if (!keep) form.tables = []
    if (keep && form.tables.length) {
      for (const database of [...new Set(form.tables.map(x=>x.sourceDatabase))]) {
        const metadata=await api(`/sources/${form.sourceId}/tables?database=${encodeURIComponent(database)}`)
        form.tables.filter(x=>x.sourceDatabase===database).forEach(row=>{const meta=metadata.find(x=>x.table===row.sourceTable&&(x.schema||null)===(row.sourceSchema||null));row.sourceColumns=meta?.columns||[];row.sourceColumnDetails=meta?.columnDetails||[];row.reconciliation ||= disabledReconciliation();if(meta?.primaryKeys?.length && !row.keyColumns?.length)row.keyColumns=meta.primaryKeys})
      }
    }
    currentDatabase.value = form.tables[0]?.sourceDatabase || databases.value[0] || ''
    if (currentDatabase.value) await loadDatabase(currentDatabase.value)
  } finally { loading.value = false }
}
async function loadDatabase(database) {
  currentDatabase.value = database
  sourceTables.value = await api(`/sources/${form.sourceId}/tables?database=${encodeURIComponent(database)}`)
}
async function loadTargetMetadata() {
  if (!form.targetId) return
  targetTables.value=[]; targetTopics.value=[]
  if (isKafka.value) {
    targetTables.value=[]
    targetTopics.value=await api(`/targets/${form.targetId}/topics`)
    form.tables.forEach(row=>{row.targetColumns=row.sourceColumns||[];row.targetColumnDetails=row.sourceColumnDetails||[]})
  } else {
    targetTopics.value=[]
    targetTables.value=await api(`/targets/${form.targetId}/tables`)
    form.tables.forEach(row=>{const meta=targetOptions.value.find(x=>x.table===row.targetTable&&(x.schema||null)===(row.targetSchema||null));row.targetSelection=meta?.key||'';row.targetColumns=meta?.columns||[];row.targetColumnDetails=meta?.columnDetails||[];row.reconciliation ||= disabledReconciliation()})
  }
}
function selected(meta) { return form.tables.some(x => x.sourceDatabase === currentDatabase.value && x.sourceSchema === (meta.schema || null) && x.sourceTable === meta.table) }
function toggleTable(meta, checked) {
  const index = form.tables.findIndex(x => x.sourceDatabase === currentDatabase.value && x.sourceSchema === (meta.schema || null) && x.sourceTable === meta.table)
  if (!checked && index >= 0) form.tables.splice(index,1)
  if (checked && index < 0) form.tables.push({ sourceDatabase:currentDatabase.value,sourceSchema:meta.schema||null,sourceTable:meta.table,targetSchema:null,targetTable:'',targetSelection:'',columns:{},keyColumns:meta.primaryKeys||[],includeOperation:false,includeCapturedAt:false,operationColumn:'_cdc_operation',capturedAtColumn:'_cdc_captured_at',capturedAtFormat:'TIMESTAMP',operationValues:{CREATE:'1',UPDATE:'2',DELETE:'3',READ:'4'},snapshotProfile:'LIGHT',incrementalProfile:'STEADY',reconciliation:disabledReconciliation(),sourceColumns:meta.columns||[],sourceColumnDetails:meta.columnDetails||[],targetColumns:isKafka.value?(meta.columns||[]):[],targetColumnDetails:isKafka.value?(meta.columnDetails||[]):[] })
}
function selectedTargetChanged(row, value) {
  const meta = targetOptions.value.find(x => x.key === value)
  if (!meta) return
  row.targetSchema = meta.schema || null; row.targetTable = meta.table; row.targetColumns = meta.columns || [];row.targetColumnDetails=meta.columnDetails||[]
  row.columns = Object.fromEntries((row.sourceColumns || []).filter(x => row.targetColumns.includes(x)).map(x => [x,x]))
  normalizeReconciliation(row)
}
function selectedTopicChanged(row) {
  row.targetSchema=null
  row.targetColumns=row.sourceColumns||[]
  if (!Object.keys(row.columns||{}).length)
    row.columns=Object.fromEntries(row.targetColumns.map(column=>[column,column]))
}
function openMapping(index) { mappingIndex.value=index; mappingOpen.value=true }
function applyMapping(value) { form.tables[mappingIndex.value].columns=value; normalizeReconciliation(form.tables[mappingIndex.value]) }
function disabledReconciliation(){return {enabled:false,sourceTimeColumn:null,targetTimeColumn:null,fields:[]}}
function detail(row,column,side='source'){return (side==='source'?row.sourceColumnDetails:row.targetColumnDetails)?.find(x=>x.name===column)||{}}
function logicalType(type='') { const x=String(type).toUpperCase();if(/INT|DECIMAL|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL/.test(x))return 'NUMBER';if(/DATE|TIME|YEAR/.test(x))return 'DATETIME';if(/BOOL|BIT/.test(x))return 'BOOLEAN';if(/CHAR|TEXT|STRING|JSON/.test(x))return 'STRING';return 'OTHER' }
function metricsFor(type){return type==='NUMBER'?['SUM','MIN','MAX','NULL_RATE']:type==='DATETIME'?['MIN','MAX','NULL_RATE']:type==='BOOLEAN'?['TRUE_COUNT','FALSE_COUNT','NULL_RATE']:['NULL_RATE']}
function defaultMetrics(type){return type==='NUMBER'?['SUM']:type==='DATETIME'?['MIN','MAX']:type==='BOOLEAN'?['TRUE_COUNT','FALSE_COUNT']:['NULL_RATE']}
function timeColumns(row){return (row.sourceColumns||[]).filter(x=>logicalType(detail(row,x).type)==='DATETIME'&&row.columns?.[x])}
function reconcilableColumns(row){return (row.sourceColumns||[]).filter(x=>row.columns?.[x]&&x!==row.operationColumn&&x!==row.capturedAtColumn)}
function normalizeReconciliation(row){row.reconciliation ||= disabledReconciliation();if(row.reconciliation.sourceTimeColumn)row.reconciliation.targetTimeColumn=row.columns?.[row.reconciliation.sourceTimeColumn]||null;row.reconciliation.fields=(row.reconciliation.fields||[]).filter(x=>row.columns?.[x.sourceColumn]).map(x=>({...x,targetColumn:row.columns[x.sourceColumn]}))}
function timeChanged(row){row.reconciliation.targetTimeColumn=row.columns?.[row.reconciliation.sourceTimeColumn]||null}
function fieldChecked(row,column){return (row.reconciliation.fields||[]).some(x=>x.sourceColumn===column)}
function toggleReconField(row,column,checked){const fields=row.reconciliation.fields||=[];const index=fields.findIndex(x=>x.sourceColumn===column);if(!checked&&index>=0)fields.splice(index,1);if(checked&&index<0){const type=logicalType(detail(row,column).type);fields.push({sourceColumn:column,targetColumn:row.columns[column],logicalType:type,metrics:defaultMetrics(type)})}}
async function connectionChanged(kind) {
  const previous = kind === 'source' ? confirmedSourceId.value : confirmedTargetId.value
  if (form.tables.length) {
    try { await CdcMessageBox.confirm('切换连接会清空已选择的表，是否继续？','连接已变更',{type:'warning'}) }
    catch {
      if (kind === 'source') form.sourceId = previous
      else form.targetId = previous
      return
    }
    form.tables=[]
  }
  if (kind === 'source') confirmedSourceId.value = form.sourceId
  else confirmedTargetId.value = form.targetId
  try { kind === 'source' ? await loadSourceMetadata() : await loadTargetMetadata() }
  catch (error) { CdcMessage.error(error.message) }
}
async function next() {
  if (step.value===0 && (!form.sourceId || !form.targetId)) return CdcMessage.warning('请选择源连接和目标连接')
  if (step.value===1 && !form.tables.length) return CdcMessage.warning('请至少选择一张源表')
  if (step.value===1 && form.tables.some(x => !x.targetTable)) return CdcMessage.warning('请为每张源表选择目标表或 Topic')
  if (step.value===2 && form.tables.some(x => !Object.keys(x.columns||{}).length)) return CdcMessage.warning('请完成每张表的字段映射')
  if (step.value===2 && form.tables.some(x => x.reconciliation?.enabled && (!x.reconciliation.sourceTimeColumn || !x.reconciliation.targetTimeColumn))) return CdcMessage.warning('请为已启用核对的表选择业务变更时间字段')
  if (step.value===2 && form.tables.some(x => x.reconciliation?.enabled && !x.reconciliation.fields?.length)) return CdcMessage.warning('请为已启用核对的表至少选择一个核数字段')
  step.value++
  if (step.value===3) await validate()
}
async function validate() {
  validating.value=true
  try { report.value=await api('/tasks/validate',{method:'POST',body:JSON.stringify(payload())}) }
  catch(e){ report.value={valid:false,errors:[e.message],warnings:[],tables:[]} }
  finally{validating.value=false}
}
function payload() {
  const cleanTables=form.tables.map(({sourceColumns,sourceColumnDetails,targetColumns,targetColumnDetails,targetSelection,...x})=>x)
  const { additionalTargetIds,...task }=form
  return {...task,tables:cleanTables,status:form.status||'DRAFT'}
}
async function save(start) {
  if (!form.name.trim()) return CdcMessage.warning('请输入任务名称')
  if(start){await validate();if(!report.value?.valid)return CdcMessage.error('请先处理预检错误')}
  loading.value=true
  try {
    const extras=[...new Set((form.additionalTargetIds||[]).filter(targetId=>targetId!==form.targetId))]
    const path=form.id?`/tasks/${form.id}`:`/tasks?start=false`
    const created=await api(path,{method:form.id?'PUT':'POST',body:JSON.stringify(payload())})
    const taskId=form.id||created.id
    if(!form.id&&extras.length){
      const createdTask=await api(`/tasks/${taskId}`)
      for(const targetId of extras){
        const target=store.targets.find(item=>item.id===targetId)
        const rows=form.tables.map(sourceRow=>{
          const saved=createdTask.tables.find(item=>item.sourceDatabase===sourceRow.sourceDatabase&&(item.sourceSchema||null)===(sourceRow.sourceSchema||null)&&item.sourceTable===sourceRow.sourceTable)
          return {sourceTaskTableId:saved?.id,targetSchema:sourceRow.targetSchema,targetTable:sourceRow.targetTable,columns:sourceRow.columns,includeOperation:sourceRow.includeOperation,operationColumn:sourceRow.operationColumn,includeCapturedAt:sourceRow.includeCapturedAt,capturedAtColumn:sourceRow.capturedAtColumn,capturedAtFormat:sourceRow.capturedAtFormat,operationValues:sourceRow.operationValues,reconciliation:sourceRow.reconciliation}
        })
        await api(`/tasks/${taskId}/subscriptions`,{method:'POST',body:JSON.stringify({name:`${form.name} · ${target?.name||'附加目标'}`,targetId,tables:rows})})
      }
    }
    if(start) await api(`/tasks/${taskId}/actions/start`,{method:'POST'})
    dirty.value=false; emit('update:modelValue',false); emit('saved'); CdcMessage.success(start?'任务已保存并启动':'任务已保存')
  }catch(e){CdcMessage.error(e.message)}finally{loading.value=false}
}
async function close(done) {
  if(!dirty.value){done();return}
  try{await CdcMessageBox.confirm('当前配置尚未保存，确认退出？','放弃修改',{type:'warning'});dirty.value=false;done()}catch{}
}
async function confirmLeave(){if(!dirty.value)return true;try{await CdcMessageBox.confirm('当前配置尚未保存，确认退出？','放弃修改');dirty.value=false;return true}catch{return false}}
defineExpose({confirmLeave})
function sourceLabel(row){return [row.sourceDatabase,row.sourceSchema,row.sourceTable].filter(Boolean).join('.')}
function targetLabel(row){return [row.targetSchema,row.targetTable].filter(Boolean).join('.')}
</script>

<template>
  <section v-if="modelValue" class="wizard-shell">
    <header class="wizard-heading"><cdc-button text @click="close(()=>emit('update:modelValue',false))">返回</cdc-button><h1>{{form.id?'编辑同步任务':'新建同步任务'}}</h1></header>
    <cdc-steps :active="step" finish-status="success" align-center class="wizard-steps">
      <cdc-step title="连接与模式"/><cdc-step title="选择数据表"/><cdc-step title="映射与策略"/><cdc-step title="预检与保存"/>
    </cdc-steps>
    <div class="wizard-body" :class="{'wizard-compact':step===0||step===3}" v-loading="loading">
      <section v-if="step===0" class="wizard-section narrow">
        <h3><Link2 :size="18"/>连接与同步模式</h3>
        <cdc-alert v-if="props.subscription" class="subscription-note" type="info" :closable="false" show-icon title="此任务将订阅现有源采集组" description="源表的增量变更只采集一次；新目标拥有独立投递进度、重试和暂停状态。若未保留可复用快照批次，请先完成该目标的基线初始化后再启用订阅。"/>
        <cdc-form label-position="top">
          <div class="two-cols">
            <cdc-form-item label="源连接" required><cdc-select v-model="form.sourceId" filterable placeholder="选择源连接" style="width:100%" @change="connectionChanged('source')"><cdc-option v-for="x in store.sources.filter(x=>x.enabled)" :key="x.id" :label="x.name+' · '+x.dbType" :value="x.id"/></cdc-select></cdc-form-item>
            <cdc-form-item label="目标连接" required><cdc-select v-model="form.targetId" filterable placeholder="选择目标连接" style="width:100%" @change="connectionChanged('target')"><cdc-option v-for="x in store.targets.filter(x=>x.enabled)" :key="x.id" :label="x.name+' · '+x.targetType" :value="x.id"/></cdc-select></cdc-form-item>
          </div>
          <cdc-form-item v-if="!form.id" label="附加目标订阅"><cdc-select v-model="form.additionalTargetIds" multiple collapse-tags filterable placeholder="可同时选择多个目标连接" style="width:100%"><cdc-option v-for="x in store.targets.filter(x=>x.enabled&&x.id!==form.targetId)" :key="x.id" :label="x.name+' · '+x.targetType" :value="x.id"/></cdc-select><div class="form-help">附加目标默认复用主目标映射；如需独立映射，请在任务详情的目标订阅中配置。</div></cdc-form-item>
          <cdc-form-item label="同步模式"><div class="mode-control"><cdc-radio-group v-model="form.syncMode" class="sync-mode-group"><cdc-radio-button value="FULL_INCREMENTAL">全量 + 增量</cdc-radio-button><cdc-radio-button value="INCREMENTAL_ONLY">仅增量</cdc-radio-button></cdc-radio-group><div class="form-help">{{form.syncMode==='FULL_INCREMENTAL'?'首次启动先 Upsert 当前数据，再从一致位点消费变化':'首次启动从当前日志位点开始，不写入历史数据'}}</div></div></cdc-form-item>
          <div v-if="source?.dbType==='STARROCKS'" class="warning-callout">StarRocks 作为源端使用主键快照轮询，无法提供 MySQL/SQL Server 等级的严格一致性。</div>
        </cdc-form>
      </section>
      <section v-else-if="step===1" class="wizard-section">
        <h3><Database :size="18"/>选择源表和目标对象 <small>已选择 {{form.tables.length}} 张表</small></h3>
        <div class="table-picker">
          <aside><button v-for="db in databases" :key="db" :class="{active:db===currentDatabase}" @click="loadDatabase(db)">{{db}}</button></aside>
          <div class="source-table-list">
              <div v-for="meta in sourceTables" :key="(meta.schema||'')+'.'+meta.table" class="source-table-row">
              <cdc-checkbox :model-value="selected(meta)" @change="toggleTable(meta,$event)"><strong>{{meta.schema?meta.schema+'.':''}}{{meta.table}}</strong><span>{{meta.columns?.length||0}} 字段 · {{meta.primaryKeys?.length?'主键 '+meta.primaryKeys.join(', '):'未发现 PRIMARY KEY'}}</span></cdc-checkbox>
            </div>
            <cdc-empty v-if="!sourceTables.length" description="该数据库没有可用表" :image-size="62"/>
          </div>
        </div>
        <cdc-table v-if="form.tables.length" :data="form.tables" border class="selected-table-grid">
          <cdc-table-column label="源表" min-width="210"><template #default="{row}"><span class="mono">{{sourceLabel(row)}}</span></template></cdc-table-column>
          <cdc-table-column :label="isKafka?'目标 Topic':'目标表'" min-width="230"><template #default="{row}"><cdc-select v-if="isKafka" v-model="row.targetTable" filterable placeholder="选择 Topic" style="width:100%" @change="selectedTopicChanged(row)"><cdc-option v-for="topic in targetTopics" :key="topic" :label="topic" :value="topic"/></cdc-select><cdc-select v-else v-model="row.targetSelection" filterable placeholder="选择目标表" style="width:100%" @change="value=>selectedTargetChanged(row,value)"><cdc-option v-for="x in targetOptions" :key="x.key" :label="x.key" :value="x.key"/></cdc-select></template></cdc-table-column>
          <cdc-table-column width="74"><template #default="{$index}"><cdc-button text type="danger" @click="form.tables.splice($index,1)">移除</cdc-button></template></cdc-table-column>
        </cdc-table>
      </section>
      <section v-else-if="step===2" class="wizard-section">
        <h3><SlidersHorizontal :size="18"/>字段映射与运行策略 <small>{{mappedCount}} / {{form.tables.length}} 已映射</small></h3>
        <cdc-collapse accordion>
          <cdc-collapse-item v-for="(row,index) in form.tables" :key="sourceLabel(row)" :name="index">
            <template #title><div class="mapping-title"><span class="mono">{{sourceLabel(row)}}</span><span>→</span><span class="mono">{{targetLabel(row)}}</span><cdc-tag size="small" :type="Object.keys(row.columns||{}).length?'success':'warning'">{{Object.keys(row.columns||{}).length}} 个字段</cdc-tag></div></template>
            <div class="mapping-config">
              <div class="mapping-summary"><cdc-button @click="openMapping(index)">字段映射</cdc-button><span>{{Object.entries(row.columns||{}).slice(0,4).map(([a,b])=>a+' → '+b).join('，')||'尚未配置'}}</span></div>
              <div class="two-cols"><cdc-form label-position="top"><cdc-form-item label="全量数据规模"><cdc-select v-model="row.snapshotProfile"><cdc-option label="轻量" value="LIGHT"/><cdc-option label="重量" value="HEAVY"/></cdc-select></cdc-form-item></cdc-form><cdc-form label-position="top"><cdc-form-item label="增量变化频率"><cdc-select v-model="row.incrementalProfile"><cdc-option label="平稳" value="STEADY"/><cdc-option label="频繁" value="FREQUENT"/></cdc-select></cdc-form-item></cdc-form></div>
              <div class="extension-grid">
                <cdc-switch v-model="row.includeOperation" active-text="写入 CDC 操作类型"/>
                <cdc-select v-if="row.includeOperation" v-model="row.operationColumn" placeholder="目标字段"><cdc-option v-for="x in row.targetColumns" :key="x" :label="x" :value="x"/></cdc-select>
                <cdc-switch v-model="row.includeCapturedAt" active-text="写入 CDC 抓取时间"/>
                <div v-if="row.includeCapturedAt" class="inline-fields"><cdc-select v-model="row.capturedAtColumn" placeholder="目标字段"><cdc-option v-for="x in row.targetColumns" :key="x" :label="x" :value="x"/></cdc-select><cdc-select v-model="row.capturedAtFormat"><cdc-option label="数据库时间戳" value="TIMESTAMP"/><cdc-option label="ISO-8601" value="ISO8601"/></cdc-select></div>
              </div>
              <div class="reconciliation-config" :class="{disabled:isKafka}">
                <div class="reconciliation-head">
                  <div><strong>数据核对</strong></div>
                  <div class="reconciliation-actions"><cdc-tag v-if="isKafka" size="small" type="info" effect="plain">当前目标不支持</cdc-tag><cdc-switch v-model="row.reconciliation.enabled" :disabled="isKafka" active-text="启用" @change="normalizeReconciliation(row)"/></div>
                </div>
                <template v-if="row.reconciliation.enabled && !isKafka">
                  <div class="two-cols reconciliation-time">
                    <cdc-form label-position="top"><cdc-form-item label="源端业务变更时间字段" required><cdc-select v-model="row.reconciliation.sourceTimeColumn" filterable placeholder="选择日期时间字段" style="width:100%" @change="timeChanged(row)"><cdc-option v-for="column in timeColumns(row)" :key="column" :label="column" :value="column"/></cdc-select></cdc-form-item></cdc-form>
                    <cdc-form label-position="top"><cdc-form-item label="目标端映射字段"><cdc-input :model-value="row.reconciliation.targetTimeColumn||''" disabled placeholder="由字段映射自动确定"/></cdc-form-item></cdc-form>
                  </div>
                  <cdc-alert class="index-warning" title="核对时间字段建议配置索引" type="warning" :closable="false" show-icon/>
                  <div class="reconciliation-fields">
                    <div class="field-head"><strong>核数字段</strong></div>
                    <div v-for="column in reconcilableColumns(row)" :key="column" class="reconciliation-field-row">
                      <cdc-checkbox :model-value="fieldChecked(row,column)" @change="toggleReconField(row,column,$event)">{{column}} → {{row.columns[column]}}</cdc-checkbox>
                      <cdc-tag size="small" effect="plain">{{logicalType(detail(row,column).type)}}</cdc-tag>
                      <cdc-select v-if="fieldChecked(row,column)" v-model="row.reconciliation.fields.find(x=>x.sourceColumn===column).metrics" multiple collapse-tags collapse-tags-tooltip placeholder="选择指标">
                        <cdc-option v-for="metric in metricsFor(logicalType(detail(row,column).type))" :key="metric" :label="metric" :value="metric"/>
                      </cdc-select>
                    </div>
                    <cdc-empty v-if="!reconcilableColumns(row).length" description="请先完成字段映射" :image-size="48"/>
                  </div>
                </template>
              </div>
            </div>
          </cdc-collapse-item>
        </cdc-collapse>
      </section>
      <section v-else class="wizard-section narrow">
        <h3><CheckCircle2 :size="18"/>任务信息与启动预检</h3>
        <cdc-form label-position="top"><cdc-form-item label="任务名称" required><cdc-input v-model="form.name" maxlength="160" show-word-limit/></cdc-form-item><cdc-form-item label="描述"><cdc-input v-model="form.description" type="textarea" :rows="3" maxlength="2000"/></cdc-form-item><div class="two-cols"><cdc-form-item label="任务时区"><cdc-select v-model="form.timeZone" style="width:100%"><cdc-option label="Asia/Shanghai" value="Asia/Shanghai"/><cdc-option label="UTC" value="UTC"/></cdc-select></cdc-form-item><cdc-form-item label="延迟告警阈值（秒）"><cdc-input-number v-model="form.alertLagSeconds" :min="1" :max="86400" style="width:100%"/></cdc-form-item></div><cdc-divider content-position="left">数据核对调度</cdc-divider><div class="two-cols"><cdc-form-item label="定时核对"><cdc-select v-model="form.reconciliationCron" clearable placeholder="不启用"><cdc-option label="每小时" value="0 0 * * * *"/><cdc-option label="每天 02:00" value="0 0 2 * * *"/></cdc-select></cdc-form-item><cdc-form-item label="全量同步完成后核对"><cdc-switch v-model="form.reconcileAfterSnapshot"/></cdc-form-item></div><div class="two-cols"><cdc-form-item label="闭合延迟（分钟）"><cdc-input-number v-model="form.reconciliationDelayMinutes" :min="0" :max="10080" style="width:100%"/></cdc-form-item><cdc-form-item label="窗口长度（分钟）"><cdc-input-number v-model="form.reconciliationWindowMinutes" :min="1" :max="10080" style="width:100%"/></cdc-form-item></div></cdc-form>
        <div class="validation-box" v-loading="validating"><div class="validation-head"><strong>启动预检</strong><cdc-button size="small" @click="validate">重新检查</cdc-button></div><cdc-alert v-if="report?.valid" title="连接、主键、目标表和字段映射检查通过" type="success" :closable="false" show-icon/><cdc-alert v-for="error in report?.errors||[]" :key="error" :title="error" type="error" :closable="false" show-icon/><cdc-alert v-for="warning in report?.warnings||[]" :key="warning" :title="warning" type="warning" :closable="false" show-icon/></div>
      </section>
    </div>
    <div class="wizard-footer"><cdc-button v-if="step>0" @click="step--">上一步</cdc-button><span></span><cdc-button v-if="step<3" type="primary" @click="next">下一步</cdc-button><template v-else><cdc-button :loading="loading" @click="save(false)">保存草稿</cdc-button><cdc-button type="primary" :loading="loading" @click="save(true)">保存并启动</cdc-button></template></div>
  </section>
  <FieldMappingDialog v-if="mappingIndex>=0" v-model="mappingOpen" :title="sourceLabel(form.tables[mappingIndex]||{})+' 字段映射'" :source-columns="form.tables[mappingIndex]?.sourceColumns||[]" :target-columns="form.tables[mappingIndex]?.targetColumns||[]" :mapping="form.tables[mappingIndex]?.columns||{}" @apply="applyMapping"/>
</template>





