<script setup>
import { ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { SunMoon } from 'lucide-vue-next'
import logoLight from '../assets/fluxa-logo.svg'
import logoDark from '../assets/fluxa-logo-dark.svg'
import { login } from '../auth'
import { toggleTheme } from '../theme'
const username=ref(''),password=ref(''),error=ref(''),busy=ref(false),route=useRoute(),router=useRouter()
async function submit(){if(busy.value)return;error.value='';busy.value=true;try{await login(username.value,password.value);password.value='';const dest=String(route.query.redirect||'/tasks');await router.replace(dest.startsWith('/')&&!dest.startsWith('//')?dest:'/tasks')}catch(e){error.value=e.message}finally{busy.value=false}}
</script>
<template><main class="login-mask"><button class="icon-btn login-theme" title="切换主题" aria-label="切换主题" @click="toggleTheme"><SunMoon :size="16"/></button><form class="login-card" @submit.prevent="submit"><div class="login-brand"><img class="login-logo-img" :src="logoLight" alt="fluxa"/><img class="login-logo-img login-logo-dark" :src="logoDark" alt=""/></div><p class="login-sub">登录以继续</p><div v-if="error" class="alert-bar error" role="alert">{{error}}</div><div class="form-field"><label for="login-user">用户名</label><input id="login-user" v-model="username" required autocomplete="username" placeholder="请输入用户名" :disabled="busy"/></div><div class="form-field"><label for="login-password">密码</label><input id="login-password" v-model="password" required type="password" autocomplete="current-password" placeholder="请输入密码" :disabled="busy"/></div><button class="btn login-btn" type="submit" :disabled="busy">{{busy?'正在登录…':'登录'}}</button></form></main></template>
