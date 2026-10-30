<script setup>
import { computed, onMounted, onUnmounted, ref, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from './stores/app'
import { Activity, Bell, Database, ListChecks, Settings, ArrowRightLeft, SunMoon, Menu, RefreshCw, X, ShieldCheck, Workflow } from 'lucide-vue-next'
import AppSwitcher from './components/AppSwitcher.vue'
import FeedbackHost from './components/ui/FeedbackHost.vue'
import { CdcMessage } from './components/ui/feedback'
import { api } from './api'
import { identity, can, roleNames, logout, clearIdentity } from './auth'
import { toggleTheme } from './theme'

const route=useRoute(),router=useRouter(),store=useAppStore(),drawer=ref(false),taskCount=ref(null)
const profile=ref(false),passwordDialog=ref(false),busy=ref(false),password=reactive({currentPassword:'',password:'',confirmation:''})
const isDev=computed(()=>route.path.startsWith('/dev'))
const homePath=computed(()=>isDev.value?'/dev/flows':'/tasks')
const groups=computed(()=>isDev.value
  ? [
      {title:'数据开发',items:[{to:'/dev/flows',label:'数据流',icon:Workflow}]},
      ...(can('admin')?[{title:'系统',items:[{to:'/settings',label:'系统设置',icon:Settings}]}]:[])
    ]
  : [
      {title:'工作区',items:[{to:'/tasks',label:'同步任务',icon:ListChecks,count:taskCount.value},{to:'/connections',label:'数据连接',icon:Database,count:store.sources.length+store.targets.length}]},
      {title:'监控',items:[{to:'/monitor',label:'任务监控',icon:Activity},{to:'/alerts',label:'告警中心',icon:Bell,count:store.openAlerts,danger:true},{to:'/audit',label:'审计日志',icon:ShieldCheck}]},
      ...(can('admin')?[{title:'系统',items:[{to:'/settings',label:'系统设置',icon:Settings}]}]:[])
    ])
const active=computed(()=>route.path.startsWith('/dev/flows')?'/dev/flows':route.path.startsWith('/tasks')?'/tasks':route.path.startsWith('/connections')?'/connections':route.path)
const title=computed(()=>route.meta.title||(route.path.endsWith('/edit')?'编辑同步任务':route.path==='/tasks/new'?'新建同步任务':groups.value.flatMap(g=>g.items).find(i=>i.to===active.value)?.label||'CDC Console'))
watch(()=>route.fullPath,()=>drawer.value=false)
async function counts(){if(!identity.user)return;try{taskCount.value=(await api('/tasks/summary')).total}catch{}}
function refresh(){window.dispatchEvent(new Event('cdc-runtime-change'));counts()}
function openPassword(){Object.assign(password,{currentPassword:'',password:'',confirmation:''});passwordDialog=true}
function clear(){store.disconnectRuntime();store.$reset();taskCount.value=null;profile.value=false;passwordDialog.value=false}
function expired(){clearIdentity();if(route.path!=='/login')router.replace({path:'/login',query:{redirect:route.fullPath}})}
async function signOut(){try{await logout()}catch(e){CdcMessage.error(e.message)}finally{router.replace('/login')}}
async function changePassword(){if(password.password!==password.confirmation){CdcMessage.error('两次新密码不一致');return}busy.value=true;try{await api('/auth/password',{method:'PUT',body:JSON.stringify(password)});CdcMessage.success('密码已修改，请重新登录');expired()}catch(e){CdcMessage.error(e.message)}finally{busy.value=false}}
watch(()=>identity.user,async user=>{if(user){store.connectRuntime();await Promise.allSettled([store.loadConnections(),store.loadAlerts(),counts()])}else clear()},{immediate:true})
onMounted(()=>{window.addEventListener('cdc-session-expired',expired);window.addEventListener('cdc-clear-session',clear);window.addEventListener('cdc-runtime-change',counts)})
onUnmounted(()=>{store.disconnectRuntime();window.removeEventListener('cdc-session-expired',expired);window.removeEventListener('cdc-clear-session',clear);window.removeEventListener('cdc-runtime-change',counts)})
</script>
<template>
  <RouterView v-if="route.path==='/login'"/>
  <div v-else-if="identity.user" class="app">
    <button v-if="drawer" class="sidebar-scrim" aria-label="关闭导航" @click="drawer=false"/>
    <aside class="sidebar" :class="{'is-open':drawer}">
      <div class="sb-head"><RouterLink :to="homePath" class="sb-brand"><span class="sb-logo"><ArrowRightLeft :size="14"/></span>CDC Console</RouterLink><span class="sb-ver">v0.1</span><button class="icon-btn mobile-only" aria-label="关闭导航" @click="drawer=false"><X :size="15"/></button></div>
      <nav class="sb-nav" aria-label="主导航"><div v-for="group in groups" :key="group.title"><div class="nav-group">{{group.title}}</div><RouterLink v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item" :class="{active:active===item.to}"><component :is="item.icon" :size="15"/><span>{{item.label}}</span><span v-if="item.count!=null" class="nav-badge" :class="{danger:item.danger&&item.count}">{{item.count}}</span></RouterLink></div></nav>
      <div class="sb-foot"><span class="svc" :class="{disconnected:!store.connected}"><i class="svc-dot"/>{{store.connected?'服务已连接':'服务未连接'}}</span><button class="icon-btn" title="刷新" aria-label="刷新" @click="refresh"><RefreshCw :size="13"/></button></div>
    </aside>
    <div class="main"><header class="app-toolbar"><button class="icon-btn mobile-only" aria-label="打开导航" @click="drawer=true"><Menu :size="16"/></button><span class="tb-title">{{title}}</span><div class="tb-actions"><span class="host-chip standalone"><i class="host-dot"/>独立 Web</span><button class="icon-btn" title="切换主题" aria-label="切换主题" @click="toggleTheme"><SunMoon :size="15"/></button><AppSwitcher @profile="profile=true" @password="openPassword" @signout="signOut"/></div></header>
      <main class="content" id="main-content"><RouterView :key="route.path"/></main>
    </div>
  </div>
  <cdc-modal v-model="profile" title="个人信息"><dl class="detail-grid"><div><dt>用户名</dt><dd>{{identity.user?.username}}</dd></div><div><dt>姓名</dt><dd>{{identity.user?.name}}</dd></div><div><dt>角色</dt><dd>{{roleNames[identity.user?.role]}}</dd></div><div><dt>邮箱</dt><dd>{{identity.user?.email||'未设置'}}</dd></div></dl></cdc-modal>
  <cdc-modal v-model="passwordDialog" title="修改密码"><cdc-form><cdc-form-item label="当前密码"><cdc-input v-model="password.currentPassword" type="password" autocomplete="current-password"/></cdc-form-item><cdc-form-item label="新密码"><cdc-input v-model="password.password" type="password" autocomplete="new-password"/><div class="form-help">至少 8 位，包含字母和数字</div></cdc-form-item><cdc-form-item label="确认新密码"><cdc-input v-model="password.confirmation" type="password" autocomplete="new-password"/></cdc-form-item></cdc-form><template #footer><cdc-button @click="passwordDialog=false">取消</cdc-button><cdc-button type="primary" :loading="busy" @click="changePassword">修改并重新登录</cdc-button></template></cdc-modal>
  <FeedbackHost/>
</template>
