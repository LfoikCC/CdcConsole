<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from './stores/app'
import { Activity, Bell, Database, ListChecks, Settings, ArrowRightLeft, SunMoon, Menu, RefreshCw, X } from 'lucide-vue-next'
import { dbxHost } from './hostAdapter'
import FeedbackHost from './components/ui/FeedbackHost.vue'
import { api } from './api'

const route = useRoute(), store = useAppStore()
const hostCompatibility = ref(null), checkingHost = ref(dbxHost.isDbx()), drawer = ref(false), taskCount = ref(null)
const standaloneUrl = import.meta.env.VITE_CDC_STANDALONE_URL || ''
const groups = computed(() => [
  { title: '工作区', items: [{ to: '/tasks', label: '同步任务', icon: ListChecks, count: taskCount.value }, { to: '/connections', label: '数据连接', icon: Database, count: store.sources.length + store.targets.length }] },
  { title: '监控', items: [{ to: '/monitor', label: '任务监控', icon: Activity }, { to: '/alerts', label: '告警中心', icon: Bell, count: store.openAlerts, danger: true }] },
  { title: '系统', items: [{ to: '/settings', label: '系统设置', icon: Settings }] }
])
const active = computed(() => route.path.startsWith('/tasks') ? '/tasks' : route.path.startsWith('/connections') ? '/connections' : route.path)
const title = computed(() => route.path.endsWith('/edit') ? '编辑同步任务' : route.path === '/tasks/new' ? '新建同步任务' : groups.value.flatMap(g => g.items).find(item => item.to === active.value)?.label || 'CDC Console')
watch(() => route.fullPath, () => drawer.value = false)
async function refreshCounts() { try { taskCount.value = (await api('/tasks/summary')).total } catch {} }
async function checkHost() {
  checkingHost.value = true
  hostCompatibility.value = await dbxHost.compatibility()
  checkingHost.value = false
  if (hostCompatibility.value.compatible) { store.connectRuntime(); await Promise.allSettled([store.loadConnections(), store.loadAlerts(), refreshCounts()]) }
}
function refresh() { window.dispatchEvent(new Event('cdc-runtime-change')); refreshCounts() }
onMounted(() => { window.addEventListener('cdc-backend-config-change', checkHost); window.addEventListener('cdc-runtime-change', refreshCounts); checkHost() })
onUnmounted(() => { window.removeEventListener('cdc-backend-config-change', checkHost); window.removeEventListener('cdc-runtime-change', refreshCounts) })
</script>

<template>
  <div class="app">
    <button v-if="drawer" class="sidebar-scrim" aria-label="关闭导航" @click="drawer=false"/>
    <aside class="sidebar" :class="{ 'is-open': drawer }">
      <div class="sb-head"><RouterLink to="/tasks" class="sb-brand"><span class="sb-logo"><ArrowRightLeft :size="14"/></span>CDC Console</RouterLink><span class="sb-ver">v0.1</span><button class="icon-btn mobile-only" aria-label="关闭导航" @click="drawer=false"><X :size="15"/></button></div>
      <nav class="sb-nav" aria-label="主导航"><div v-for="group in groups" :key="group.title"><div class="nav-group">{{group.title}}</div><RouterLink v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item" :class="{active:active===item.to}"><component :is="item.icon" :size="15"/><span>{{item.label}}</span><span v-if="item.count!=null" class="nav-badge" :class="{danger:item.danger&&item.count}">{{item.count}}</span></RouterLink></div></nav>
      <div class="sb-foot"><span class="svc" :class="{ disconnected: !store.connected }"><i class="svc-dot"/>{{store.connected?'服务已连接':'服务未连接'}}</span><button class="icon-btn" title="刷新" aria-label="刷新" @click="refresh"><RefreshCw :size="13"/></button><RouterLink class="icon-btn" to="/settings" title="系统设置" aria-label="系统设置"><Settings :size="13"/></RouterLink></div>
    </aside>
    <div class="main"><header class="app-toolbar"><button class="icon-btn mobile-only" aria-label="打开导航" @click="drawer=true"><Menu :size="16"/></button><span class="tb-title">{{title}}</span><div class="tb-actions"><span class="host-chip" :class="{standalone:!dbxHost.isDbx()}"><i class="host-dot"/>{{dbxHost.isDbx()?'DBX 插件':'独立 Web'}}</span><button v-if="!dbxHost.isDbx()" class="icon-btn" aria-label="切换主题" title="切换主题" @click="dbxHost.toggleTheme()"><SunMoon :size="15"/></button></div></header>
      <main class="content" id="main-content">
        <section v-if="checkingHost" class="empty-state" role="status">正在检查 DBX 宿主兼容性</section>
        <section v-else-if="hostCompatibility&&!hostCompatibility.compatible" class="alert-bar error" role="alert"><div><strong>DBX 插件暂不可用</strong><p>当前 Host API：{{hostCompatibility.hostInfo?.apiVersion||'未知'}}；最低要求：1。缺少：{{hostCompatibility.missing.join('、')}}。</p><div class="header-actions"><cdc-button @click="dbxHost.openPluginSettings()">插件设置</cdc-button><cdc-button @click="checkHost">重新检查</cdc-button><a v-if="standaloneUrl" :href="standaloneUrl" target="_blank" rel="noopener">独立访问</a></div></div></section>
        <template v-else-if="hostCompatibility?.compatible"><div v-if="hostCompatibility.warnings?.length" class="alert-bar warning">宿主主题能力不可用，当前使用默认主题。</div><RouterView/></template>
      </main>
    </div>
  </div>
  <FeedbackHost/>
</template>
