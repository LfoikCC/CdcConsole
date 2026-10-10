<script setup>
import { onMounted, reactive, ref } from 'vue'
import { CdcMessage } from '../components/ui/feedback'
import { Save } from 'lucide-vue-next'
import { api } from '../api'
import MailSettings from '../components/MailSettings.vue'

const form=reactive({segmentSizeMb:100,retentionDays:7}), status=ref({}), loading=ref(false)
const tab=ref('general')
onMounted(async()=>{try{const [settings,runtime]=await Promise.all([api('/settings'),api('/status')]);Object.assign(form,settings);status.value=runtime}catch(e){CdcMessage.error(e.message)}})
async function save(){loading.value=true;try{Object.assign(form,await api('/settings',{method:'PUT',body:JSON.stringify(form)}));CdcMessage.success('系统设置已保存')}catch(e){CdcMessage.error(e.message)}finally{loading.value=false}}
</script>

<template>
  <div class="settings-page">

    <div class="page-controls"><div class="tabs" aria-label="系统设置"><button class="tab" :class="{active:tab==='general'}" @click="tab='general'">通用设置</button><button class="tab" :class="{active:tab==='mail'}" @click="tab='mail'">邮件服务</button></div><cdc-button v-if="tab==='general'" type="primary" :loading="loading" @click="save"><Save :size="15"/>保存设置</cdc-button></div>
    <MailSettings v-if="tab==='mail'"/>
    <section v-else class="panel settings-panel"><div class="form-section"><h3>事件缓存</h3><cdc-form label-position="top" class="settings-form"><cdc-form-item label="单个缓存分段上限（MB）"><div class="setting-control"><cdc-input-number v-model="form.segmentSizeMb" :min="1" :max="10240"/><div class="form-help">事件先写入本地分段文件，再投递到目标端。</div></div></cdc-form-item><cdc-form-item label="缓存保留时间（天）"><div class="setting-control"><cdc-input-number v-model="form.retentionDays" :min="1" :max="3650"/><div class="form-help">超过保留期的分段由每日清理任务删除。</div></div></cdc-form-item></cdc-form></div><div class="form-section"><h3>当前运行状态</h3><dl class="detail-grid"><div><dt>源连接</dt><dd>{{status.sources??'-'}}</dd></div><div><dt>目标连接</dt><dd>{{status.targets??'-'}}</dd></div><div><dt>缓存分段</dt><dd>{{status.cacheSegments??'-'}}</dd></div><div><dt>缓存占用</dt><dd>{{status.cacheBytes!=null?(status.cacheBytes/1024/1024).toFixed(2)+' MB':'-'}}</dd></div></dl></div><div class="form-section"><h3>生命周期约定</h3><div class="warning-callout">暂停会保留 checkpoint，可从原位点恢复；停止会清除 checkpoint，下次启动按任务模式重新初始化。修改连接、表或字段映射前必须先停止任务。</div></div></section>
  </div>
</template>
