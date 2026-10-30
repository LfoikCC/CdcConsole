<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRightLeft, Check, LayoutGrid, Workflow } from 'lucide-vue-next'
import { identity, roleNames } from '../auth'

const route = useRoute()
const router = useRouter()

const emit = defineEmits(['profile', 'password', 'signout'])

const user = computed(() => identity.user)
const initial = computed(() => (user.value?.name || user.value?.username || '?').trim().charAt(0).toUpperCase())
const currentApp = computed(() => (route.path.startsWith('/dev') ? 'dev' : 'sync'))

const apps = [
  { id: 'sync', label: '实时同步', icon: ArrowRightLeft, to: '/tasks' },
  { id: 'dev', label: '数据开发', icon: Workflow, to: '/dev/flows' }
]

function open(app) {
  router.push(app.to)
}
</script>

<template>
  <cdc-menu class="app-switcher" label="切换应用" align="end" :offset="9"
            panel-class="app-switcher-panel" panel-width="320px">
    <template #trigger><LayoutGrid :size="16"/></template>

    <button role="menuitem" class="as-user" @click="emit('profile')">
      <span class="as-avatar">{{ initial }}</span>
      <span class="as-user-text">
        <b>{{ user?.name || user?.username }}</b>
        <small>{{ user?.email || user?.username }}</small>
      </span>
      <span class="badge">{{ roleNames[user?.role] }}</span>
    </button>

    <div class="as-divider"/>

    <div class="as-label">应用</div>
    <button v-for="app in apps" :key="app.id" role="menuitem" class="as-app" @click="open(app)">
      <component :is="app.icon" :size="16"/>
      <span>{{ app.label }}</span>
      <Check v-if="currentApp === app.id" :size="14" class="as-check"/>
    </button>

    <div class="as-divider"/>

    <button role="menuitem" @click="emit('password')">修改密码</button>
    <button role="menuitem" class="danger-text" @click="emit('signout')">退出登录</button>
  </cdc-menu>
</template>
