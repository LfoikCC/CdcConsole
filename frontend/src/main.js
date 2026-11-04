import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { installUi } from './components/ui'
import './styles.css'
import './monitor.css'
import './operations.css'
import './metabase-theme.css'
// 数据开发的画布与抽屉样式，必须最后加载才能覆盖上面的通用规则
import './styles/dev.css'
// 数据分析：看板网格与卡片
import './styles/bi.css'
import App from './App.vue'
import router from './router'
import { initializeTheme } from './theme'
import { permit } from './auth'
import DatabaseBrand from './components/DatabaseBrand.vue'

async function start() {
  initializeTheme()
  const app = createApp(App).use(createPinia()).use(router)
  installUi(app)
  app.component('DatabaseBrand',DatabaseBrand)
  app.directive('permit', permit)
  app.mount('#app')
}

start()
