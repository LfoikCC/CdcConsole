<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Mail, Save, Send, PlugZap } from 'lucide-vue-next'
import { api } from '../api'
import { CdcMessage } from './ui/feedback'
const form=reactive({enabled:false,host:'',port:587,secure:'starttls',username:'',password:'',fromName:'CDC 监控',fromEmail:'',passwordConfigured:false})
const busy=ref(false),recipient=ref(''),testResult=ref('')
function changeEncryption(event){form.port={none:25,ssl:465,starttls:587}[event.target.value]}
const transportHint=()=>{const host=String(form.host||'').toLowerCase();if(form.port===587&&form.secure==='ssl')return host.includes('smtp.qq.com' )?'QQ 邮箱：587 端口请选择 STARTTLS，SSL/TLS 请改用 465。':'587 端口通常请选择 STARTTLS。';if(form.port===465&&form.secure==='starttls')return '465 端口通常请选择 SSL/TLS。';return ''}
onMounted(async()=>{try{Object.assign(form,await api('/mail/config'))}catch(e){CdcMessage.error(e.message)}})
async function perform(action){busy.value=true;testResult.value='';try{
  if(action==='save'){Object.assign(form,await api('/mail/config',{method:'PUT',body:JSON.stringify(form)}));CdcMessage.success('邮件配置已保存')}
  if(action==='connection'){await api('/mail/test-connection',{method:'POST',body:JSON.stringify(form)});testResult.value='当前配置的 SMTP 连接测试通过（未自动保存）'}
  if(action==='test'){await api('/mail/test',{method:'POST',body:JSON.stringify({recipient:recipient.value.trim()})});CdcMessage.success('测试邮件已加入队列，请在邮件历史中查看发送结果')}
}catch(e){CdcMessage.error(e.message);testResult.value=e.message}finally{busy.value=false}}
</script>
<template>
  <div class="mail-settings">
    <section class="mail-status-card" :class="{enabled:form.enabled}"><div class="mail-status-icon"><Mail :size="22"/></div><div><h3>邮件服务{{form.enabled?'已启用':'未启用'}}</h3><p class="muted">开启后，订阅规则触发的告警将通过 SMTP 推送。修改后请保存并测试连接。</p></div></section>
    <section class="panel mail-form-panel"><h3>SMTP 服务器</h3><label class="inline-check"><input type="checkbox" v-model="form.enabled">启用邮件服务</label><div class="form-row"><div class="form-field"><label>服务器地址</label><input v-model="form.host" placeholder="smtp.example.com"></div><div class="form-field"><label>端口</label><input type="number" v-model.number="form.port" min="1" max="65535"></div><div class="form-field"><label>加密方式</label><select v-model="form.secure" @change="changeEncryption"><option value="starttls">STARTTLS</option><option value="ssl">SSL/TLS</option><option value="none">无</option></select></div></div><div class="form-row"><div class="form-field"><label>SMTP 用户名</label><input v-model="form.username" autocomplete="off"></div><div class="form-field"><label>SMTP 密码／授权码</label><input type="password" v-model="form.password" autocomplete="new-password" :placeholder="form.passwordConfigured?'已配置，留空保留原密码':'请输入密码或授权码'"></div></div><p class="form-help">端口 465 通常使用 SSL/TLS，587 使用 STARTTLS。QQ 邮箱请使用授权码。</p><p v-if="transportHint()" class="mail-transport-warning">{{transportHint()}}</p></section>
    <section class="panel mail-form-panel"><h3>发件人信息</h3><div class="form-row"><div class="form-field"><label>显示名</label><input v-model="form.fromName"></div><div class="form-field"><label>发件人邮箱</label><input type="email" v-model="form.fromEmail"></div></div></section>
    <div class="mail-config-actions"><cdc-button type="primary" :loading="busy" @click="perform('save')"><Save :size="14"/>保存配置</cdc-button><cdc-button type="info" :disabled="busy" @click="perform('connection')"><PlugZap :size="14"/>测试连接</cdc-button></div>
    <div class="mail-test-row"><div class="form-field"><label for="mail-test-recipient">测试收件人</label><input id="mail-test-recipient" type="email" v-model="recipient" aria-label="测试收件人" placeholder="收件人邮箱"></div><cdc-button type="success" :disabled="busy||!recipient.trim()" @click="perform('test')"><Send :size="14"/>发送测试邮件</cdc-button></div><p v-if="testResult" role="status" class="form-help">{{testResult}}</p>
  </div>
</template>
