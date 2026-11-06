<script setup>
import { computed, onMounted, onUnmounted, ref, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from './stores/app'
import { Activity, Bell, Database, Layers, ListChecks, SunMoon, Menu, RefreshCw, X, ShieldCheck, Workflow, PanelLeftClose, PanelLeftOpen, LayoutDashboard } from 'lucide-vue-next'
import AppSwitcher from './components/AppSwitcher.vue'
import FlowFolderTree from './components/dev/FlowFolderTree.vue'
import BiFolderTree from './components/bi/BiFolderTree.vue'
import FeedbackHost from './components/ui/FeedbackHost.vue'
import { CdcMessage } from './components/ui/feedback'
import { api } from './api'
import { identity, roleNames, logout, clearIdentity } from './auth'
import logoLight from './assets/fluxa-logo.svg'
import logoDark from './assets/fluxa-logo-dark.svg'
import { toggleTheme } from './theme'

const route=useRoute(),router=useRouter(),store=useAppStore(),drawer=ref(false),taskCount=ref(null)
// 侧栏折叠状态记在本地：下次打开保持上次的选择
const collapsed=ref(readCollapsed())
function readCollapsed(){try{return localStorage.getItem('cdc.sidebar.collapsed')==='1'}catch{return false}}
function toggleSidebar(){collapsed.value=!collapsed.value;try{localStorage.setItem('cdc.sidebar.collapsed',collapsed.value?'1':'0')}catch{}}
const profile=ref(false),passwordDialog=ref(false),busy=ref(false),password=reactive({currentPassword:'',password:'',confirmation:''})
const appKey=computed(()=>route.path.startsWith('/dev')?'dev':route.path.startsWith('/prep')?'prep':route.path.startsWith('/bi')?'bi':'sync')
// 数据流编辑器是沉浸式页面：左侧导航固定占 240px，画布本来就不够用
const immersive=computed(()=>route.meta?.immersive===true)
const homePath=computed(()=>appKey.value==='dev'?'/dev/flows':appKey.value==='prep'?'/prep/datasets':appKey.value==='bi'?'/bi/dashboards':'/tasks')
const groups=computed(()=>appKey.value==='dev'
  ? [
      {title:'数据开发',items:[{to:'/dev/flows',label:'数据流',icon:Workflow}]}
    ]
  : appKey.value==='prep'
  ? [
      {title:'数据准备',items:[{to:'/prep/datasets',label:'数据集',icon:Layers},{to:'/prep/accounts',label:'数据账户',icon:Database,count:store.accounts.length}]}
    ]
  : appKey.value==='bi'
  ? [
      {title:'数据分析',items:[{to:'/bi/dashboards',label:'数据看板',icon:LayoutDashboard}]}
    ]
  : [
      {title:'工作区',items:[{to:'/tasks',label:'同步任务',icon:ListChecks,count:taskCount.value}]},
      {title:'监控',items:[{to:'/monitor',label:'任务监控',icon:Activity},{to:'/alerts',label:'告警中心',icon:Bell,count:store.openAlerts,danger:true},{to:'/audit',label:'审计日志',icon:ShieldCheck}]}
    ])
const active=computed(()=>route.path.startsWith('/dev/flows')?'/dev/flows':route.path.startsWith('/bi/dashboards')?'/bi/dashboards':route.path.startsWith('/prep/datasets')?'/prep/datasets':route.path.startsWith('/tasks')?'/tasks':route.path)
const title=computed(()=>route.meta.title||(route.path.endsWith('/edit')?'编辑同步任务':route.path==='/tasks/new'?'新建同步任务':groups.value.flatMap(g=>g.items).find(i=>i.to===active.value)?.label||'Fluxa'))
watch(()=>route.fullPath,()=>{drawer.value=false;scheduleCounts()})
/**
 * 侧栏的三个数字（同步任务 / 告警中心 / 数据账户）都在这里取。
 *
 * <p>以前只在登录时取一次，页面里删完任务侧栏还挂着旧数字，必须 F5 才对。
 * 现在三个来源都会触发：写操作（api.js 派发 cdc-counts-change）、SSE 运行时事件、
 * 路由切换。统一走 200ms 防抖，避免批量操作时连打接口。
 */
let countsTimer=null
function scheduleCounts(){clearTimeout(countsTimer);countsTimer=setTimeout(counts,200)}
async function counts(){
  if(!identity.user)return
  await Promise.allSettled([
    api('/tasks/summary').then(body=>{taskCount.value=body.total}),
    store.loadAlerts(),
    store.loadAccounts()
  ])
}
function refresh(){window.dispatchEvent(new Event('cdc-runtime-change'));scheduleCounts()}
function openPassword(){Object.assign(password,{currentPassword:'',password:'',confirmation:''});passwordDialog=true}
function clear(){store.disconnectRuntime();store.$reset();taskCount.value=null;profile.value=false;passwordDialog.value=false}
function expired(){clearIdentity();if(route.path!=='/login')router.replace({path:'/login',query:{redirect:route.fullPath}})}
async function signOut(){try{await logout()}catch(e){CdcMessage.error(e.message)}finally{router.replace('/login')}}
async function changePassword(){if(password.password!==password.confirmation){CdcMessage.error('两次新密码不一致');return}busy.value=true;try{await api('/auth/password',{method:'PUT',body:JSON.stringify(password)});CdcMessage.success('密码已修改，请重新登录');expired()}catch(e){CdcMessage.error(e.message)}finally{busy.value=false}}
watch(()=>identity.user,async user=>{if(user){store.connectRuntime();await Promise.allSettled([store.loadConnections(),counts()])}else clear()},{immediate:true})
onMounted(()=>{window.addEventListener('cdc-session-expired',expired);window.addEventListener('cdc-clear-session',clear);window.addEventListener('cdc-runtime-change',scheduleCounts);window.addEventListener('cdc-counts-change',scheduleCounts)})
onUnmounted(()=>{clearTimeout(countsTimer);store.disconnectRuntime();window.removeEventListener('cdc-session-expired',expired);window.removeEventListener('cdc-clear-session',clear);window.removeEventListener('cdc-runtime-change',scheduleCounts);window.removeEventListener('cdc-counts-change',scheduleCounts)})
</script>
<template>
  <RouterView v-if="route.path==='/login'"/>
  <div v-else-if="identity.user" class="app">
    <button v-if="drawer&&!immersive" class="sidebar-scrim" aria-label="关闭导航" @click="drawer=false"/>
    <aside v-if="!immersive" class="sidebar" :class="{'is-open':drawer,'is-collapsed':collapsed}">
      <div class="sb-head"><RouterLink :to="homePath" class="sb-brand" aria-label="Fluxa"><img class="sb-logo-img" :src="logoLight" alt="fluxa"/><img class="sb-logo-img sb-logo-dark" :src="logoDark" alt=""/></RouterLink><button class="icon-btn sb-collapse desktop-only" title="隐藏菜单" aria-label="隐藏菜单" @click="toggleSidebar"><PanelLeftClose :size="15"/></button><button class="icon-btn mobile-only" aria-label="关闭导航" @click="drawer=false"><X :size="15"/></button></div>
      <nav class="sb-nav" aria-label="主导航"><div v-for="group in groups" :key="group.title"><div class="nav-group">{{group.title}}</div><RouterLink v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item" :class="{active:active===item.to}"><component :is="item.icon" :size="15"/><span>{{item.label}}</span><span v-if="item.count!=null" class="nav-badge" :class="{danger:item.danger&&item.count}">{{item.count}}</span></RouterLink><FlowFolderTree v-if="group.title==='数据开发'"/><BiFolderTree v-if="group.title==='数据分析'"/></div></nav>
      <div class="sb-foot"><span class="svc" :class="{disconnected:!store.connected}"><i class="svc-dot"/>{{store.connected?'服务已连接':'服务未连接'}}</span><button class="icon-btn" title="刷新" aria-label="刷新" @click="refresh"><RefreshCw :size="13"/></button></div>
    </aside>
    <div class="main"><header class="app-toolbar"><button v-if="!immersive" class="icon-btn mobile-only" aria-label="打开导航" @click="drawer=true"><Menu :size="16"/></button><button v-if="collapsed" class="icon-btn desktop-only" title="显示菜单" aria-label="显示菜单" @click="toggleSidebar"><PanelLeftOpen :size="15"/></button><span class="tb-title">{{title}}</span><div class="tb-actions"><button class="icon-btn" title="切换主题" aria-label="切换主题" @click="toggleTheme"><SunMoon :size="15"/></button><AppSwitcher @profile="profile=true" @password="openPassword" @signout="signOut"/></div></header>
      <main class="content" id="main-content"><RouterView :key="route.path"/></main>
    </div>
  </div>
  <cdc-modal v-model="profile" title="个人信息"><dl class="detail-grid"><div><dt>用户名</dt><dd>{{identity.user?.username}}</dd></div><div><dt>姓名</dt><dd>{{identity.user?.name}}</dd></div><div><dt>角色</dt><dd>{{roleNames[identity.user?.role]}}</dd></div><div><dt>邮箱</dt><dd>{{identity.user?.email||'未设置'}}</dd></div></dl></cdc-modal>
  <cdc-modal v-model="passwordDialog" title="修改密码"><cdc-form><cdc-form-item label="当前密码"><cdc-input v-model="password.currentPassword" type="password" autocomplete="current-password"/></cdc-form-item><cdc-form-item label="新密码"><cdc-input v-model="password.password" type="password" autocomplete="new-password"/><div class="form-help">至少 8 位，包含字母和数字</div></cdc-form-item><cdc-form-item label="确认新密码"><cdc-input v-model="password.confirmation" type="password" autocomplete="new-password"/></cdc-form-item></cdc-form><template #footer><cdc-button @click="passwordDialog=false">取消</cdc-button><cdc-button type="primary" :loading="busy" @click="changePassword">修改并重新登录</cdc-button></template></cdc-modal>
  <FeedbackHost/>
</template>
