<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { CdcMessage } from './ui/feedback'
import { Save } from 'lucide-vue-next'
import { api } from '../api'

const props=defineProps({modelValue:Boolean,taskId:{type:Number,required:true},subscription:{type:Object,default:null}})
const emit=defineEmits(['update:modelValue','saved'])
const loading=ref(false),saving=ref(false),form=reactive({tables:[]})
const metricOptions={NUMBER:['SUM','MIN','MAX','NULL_RATE'],STRING:['NULL_RATE'],DATETIME:['MIN','MAX','NULL_RATE'],BOOLEAN:['TRUE_COUNT','FALSE_COUNT','NULL_RATE'],OTHER:['NULL_RATE']}
const metricLabels={SUM:'求和',MIN:'最小值',MAX:'最大值',NULL_RATE:'空值率',TRUE_COUNT:'真值数量',FALSE_COUNT:'假值数量'}

watch(()=>props.modelValue,value=>{if(value)load()},{immediate:true})
function disabled(){return {enabled:false,sourceTimeColumn:null,targetTimeColumn:null,fields:[]}}
function clone(value){return JSON.parse(JSON.stringify(value||disabled()))}
function fieldType(row,column){const raw=String(row.source?.sourceColumnDetails?.find(item=>item.name===column)?.type||'').toUpperCase();if(/INT|DECIMAL|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL/.test(raw))return 'NUMBER';if(/DATE|TIME|YEAR/.test(raw))return 'DATETIME';if(/BOOL|BIT/.test(raw))return 'BOOLEAN';if(/CHAR|TEXT|STRING|JSON/.test(raw))return 'STRING';return 'OTHER'}
function sourceColumns(row){return row.source?.sourceColumns||Object.keys(row.mapping?.columns||{})}
function timeColumns(row){return sourceColumns(row).filter(column=>row.mapping?.columns?.[column]&&/DATE|TIME|YEAR/i.test(String(row.source?.sourceColumnDetails?.find(item=>item.name===column)?.type||'')))}
function selectedFields(row){return (row.reconciliation.fields||[]).map(item=>item.sourceColumn)}
function fieldConfig(row,column){return row.reconciliation.fields.find(item=>item.sourceColumn===column)}
function changeEnabled(row,value){row.reconciliation.enabled=value;if(!value)row.reconciliation=disabled()}
function changeTime(row){row.reconciliation.targetTimeColumn=row.mapping.columns?.[row.reconciliation.sourceTimeColumn]||null}
function changeFields(row,columns){const existing=new Map((row.reconciliation.fields||[]).map(item=>[item.sourceColumn,item]));row.reconciliation.fields=columns.map(column=>existing.get(column)||({sourceColumn:column,targetColumn:row.mapping.columns[column],logicalType:fieldType(row,column),metrics:metricOptions[fieldType(row,column)]?.slice(0,fieldType(row,column)==='DATETIME'?2:1)||['NULL_RATE']}))}
function changeLogicalType(row,field){field.metrics=(metricOptions[field.logicalType]||['NULL_RATE']).slice(0,field.logicalType==='DATETIME'?2:1)}
function tableLabel(row){return [row.mapping?.sourceDatabase,row.mapping?.sourceSchema,row.mapping?.sourceTable].filter(Boolean).join('.')}
async function load(){
  if(!props.subscription)return
  loading.value=true
  try{
    const payload=await api(`/tasks/${props.taskId}/subscriptions/${props.subscription.id}/reconciliation-options`)
    const metadata=new Map((payload.tables||[]).map(item=>[item.id,item]))
    form.tables.splice(0,form.tables.length,...(payload.subscription?.tables||[]).map(binding=>({subscriptionTableId:binding.id,mapping:binding.mapping,source:metadata.get(binding.sourceTaskTableId)||{},reconciliation:clone(binding.mapping?.reconciliation)})))
  }catch(error){CdcMessage.error(error.message)}finally{loading.value=false}
}
async function submit(){
  for(const row of form.tables){
    if(!row.reconciliation.enabled)continue
    if(!row.reconciliation.sourceTimeColumn||!row.reconciliation.targetTimeColumn)return CdcMessage.warning(`${tableLabel(row)}：请选择业务变更时间字段`)
    if(!row.reconciliation.fields?.length)return CdcMessage.warning(`${tableLabel(row)}：至少选择一个核数字段`)
  }
  saving.value=true
  try{await api(`/tasks/${props.taskId}/subscriptions/${props.subscription.id}/reconciliation`,{method:'PUT',body:JSON.stringify({tables:form.tables.map(row=>({subscriptionTableId:row.subscriptionTableId,reconciliation:row.reconciliation}))})});CdcMessage.success('数据核对配置已保存');emit('update:modelValue',false);emit('saved')}catch(error){CdcMessage.error(error.message)}finally{saving.value=false}
}
</script>

<template>
  <cdc-modal :model-value="modelValue" width="min(980px,94vw)" top="6vh" title="编辑数据核对" @close="emit('update:modelValue',false)">
    <div v-loading="loading" class="reconciliation-dialog">
      <cdc-alert type="info" :closable="false" show-icon title="核对规则只影响后续核对任务，不会中断当前 CDC 投递。"/>
      <cdc-collapse accordion>
        <cdc-collapse-item v-for="(row,index) in form.tables" :key="row.subscriptionTableId" :name="index">
          <template #title><strong>{{ tableLabel(row) }}</strong><cdc-tag class="target-tag" size="small" effect="plain">{{ row.mapping?.targetTable }}</cdc-tag></template>
          <div class="config-card">
            <div class="config-head"><strong>数据核对</strong><cdc-switch :model-value="row.reconciliation.enabled" active-text="启用" @change="value=>changeEnabled(row,value)"/></div>
            <template v-if="row.reconciliation.enabled">
              <cdc-alert v-if="!timeColumns(row).length" type="warning" :closable="false" show-icon title="当前已映射字段中没有日期时间字段，请先补充目标字段映射。"/>
              <div class="two-cols">
                <cdc-form label-position="top"><cdc-form-item label="业务变更时间字段" required><cdc-select v-model="row.reconciliation.sourceTimeColumn" :disabled="!timeColumns(row).length" placeholder="选择日期时间字段" style="width:100%" @change="changeTime(row)"><cdc-option v-for="column in timeColumns(row)" :key="column" :label="column" :value="column"/></cdc-select></cdc-form-item></cdc-form>
                <cdc-form label-position="top"><cdc-form-item label="目标映射字段"><cdc-input :model-value="row.reconciliation.targetTimeColumn||''" disabled placeholder="由字段映射自动确定"/></cdc-form-item></cdc-form>
              </div>
              <cdc-form label-position="top"><cdc-form-item label="核数字段" required><cdc-select :model-value="selectedFields(row)" multiple collapse-tags style="width:100%" placeholder="选择已映射字段" @change="columns=>changeFields(row,columns)"><cdc-option v-for="column in sourceColumns(row).filter(column=>row.mapping?.columns?.[column]&&column!==row.reconciliation.sourceTimeColumn)" :key="column" :label="column" :value="column"/></cdc-select></cdc-form-item></cdc-form>
              <cdc-table v-if="row.reconciliation.fields?.length" :data="row.reconciliation.fields" border size="small"><cdc-table-column prop="sourceColumn" label="源字段" min-width="180"/><cdc-table-column prop="targetColumn" label="目标字段" min-width="180"/><cdc-table-column label="逻辑类型" min-width="150"><template #default="{row:field}"><cdc-select v-model="field.logicalType" style="width:100%" @change="changeLogicalType(row,field)"><cdc-option label="数值" value="NUMBER"/><cdc-option label="文本" value="STRING"/><cdc-option label="日期时间" value="DATETIME"/><cdc-option label="布尔" value="BOOLEAN"/><cdc-option label="其他" value="OTHER"/></cdc-select></template></cdc-table-column><cdc-table-column label="指标" min-width="260"><template #default="{row:field}"><cdc-select v-model="field.metrics" multiple collapse-tags style="width:100%"><cdc-option v-for="metric in metricOptions[field.logicalType]||metricOptions.OTHER" :key="metric" :label="metricLabels[metric]" :value="metric"/></cdc-select></template></cdc-table-column></cdc-table>
            </template>
          </div>
        </cdc-collapse-item>
      </cdc-collapse>
    </div>
    <template #footer><cdc-button @click="emit('update:modelValue',false)">取消</cdc-button><cdc-button type="primary" :loading="saving" @click="submit"><Save :size="15"/>保存配置</cdc-button></template>
  </cdc-modal>
</template>

